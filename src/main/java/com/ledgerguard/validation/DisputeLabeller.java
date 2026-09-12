package com.ledgerguard.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns matured fraud disputes into account labels.
 *
 * <h2>Positives only, and that is not a gap to be fixed</h2>
 *
 * This produces {@link Verdict#ANOMALOUS} labels and never produces
 * {@link Verdict#BENIGN} ones. A payment nobody disputed is <b>not</b> evidence
 * that it was legitimate: it may be fraud nobody noticed, fraud below the
 * threshold anyone bothers to chargeback, or fraud whose dispute window has not
 * closed. Writing a BENIGN label for every undisputed payment would manufacture
 * a vast, cheap, and wrong negative class, and every metric computed against it
 * would look excellent.
 *
 * <p>The consequence runs the opposite way to the intuition, and it is worth
 * being exact about because the first draft of this class got it backwards.
 * With positives and no negatives:
 *
 * <ul>
 *   <li><b>Recall is estimable.</b> The set of known-anomalous accounts is
 *       known, so "how many of them did the detector flag" is a real
 *       calculation — over the accounts disputes identify, which is not the same
 *       population as all fraud, but is a population.</li>
 *   <li><b>Precision is not.</b> It needs every flagged account classified, and
 *       a flagged account with no chargeback is unresolvable: it may be a false
 *       positive, or fraud nobody disputed, and nothing here can tell those
 *       apart.</li>
 * </ul>
 *
 * <p>So precision comes from human review of the flagged stratum, and the
 * dispute feed supplies an independent recall estimate that no amount of
 * reviewing the detector's own output could produce. The two sources are
 * complementary rather than redundant, and neither substitutes for the other.
 *
 * <h2>Maturity, not arrival</h2>
 *
 * Only disputes whose {@code raisedAt} has actually passed become labels. The
 * simulator writes them in advance because it knows what a scheme will do; this
 * system must not act on that knowledge, because a real one would not have it.
 * The gate is here rather than in a query filter somewhere downstream so there
 * is exactly one place where a future dispute could leak into the present.
 */
@Service
public class DisputeLabeller {

    private static final Logger log = LoggerFactory.getLogger(DisputeLabeller.class);

    /**
     * Which account sent the disputed payment, and when.
     *
     * <p>The payment's own timestamp is what the label is a statement about: the
     * behaviour being judged is what this account was doing when it sent the
     * money, not what it was doing when someone complained.
     */
    private static final String PAYING_ACCOUNT = """
            SELECT source_account_id, created_at
            FROM payments
            WHERE transaction_id = ?
            """;

    private final DisputeRepository disputes;
    private final AccountLabelRepository labels;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DisputeLabeller(DisputeRepository disputes, AccountLabelRepository labels,
                           JdbcTemplate jdbc, Clock clock) {
        this.disputes = disputes;
        this.labels = labels;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public int labelMatured() {
        return labelMatured(Instant.now(clock));
    }

    /**
     * @return how many new labels were written
     */
    @Transactional
    public int labelMatured(Instant asOf) {
        int written = 0;

        for (Dispute dispute : disputes.matured(asOf)) {
            if (!dispute.getReason().isFraudEvidence()) {
                // Recorded, deliberately unused. A merchant argument is not
                // evidence about whether a payment was authorised.
                continue;
            }
            if (labels.existsByEvidenceId(dispute.getId())) {
                continue;
            }
            if (dispute.getTransactionReference() == null) {
                log.debug("dispute {} has no usable reference, so it labels nothing",
                        dispute.getExternalId());
                continue;
            }

            Optional<PayingAccount> payer = payingAccount(dispute.getTransactionReference());
            if (payer.isEmpty()) {
                // A dispute referencing a transaction we do not have is a real
                // scenario and not an error: it is the chargeback equivalent of
                // Phase 5's UNEXPECTED_EXTERNAL_TRANSACTION.
                log.debug("dispute {} references unknown transaction {}",
                        dispute.getExternalId(), dispute.getTransactionReference());
                continue;
            }

            labels.save(AccountLabel.fromDispute(
                    payer.get().accountId(),
                    Verdict.ANOMALOUS,
                    dispute.getId(),
                    payer.get().paidAt(),
                    dispute.getRaisedAt(),
                    "chargeback %s, reason %s".formatted(
                            dispute.getExternalId(), dispute.getReason())));
            written++;
        }

        if (written > 0) {
            log.info("dispute labeller: wrote {} label(s) from matured chargebacks", written);
        }
        return written;
    }

    /** Disputes that exist but have not been raised yet. Visible, and unusable. */
    public int pendingCount() {
        Instant now = Instant.now(clock);
        return (int) disputes.findAll().stream()
                .filter(dispute -> !dispute.isMatured(now))
                .count();
    }

    private Optional<PayingAccount> payingAccount(String transactionId) {
        List<PayingAccount> found = jdbc.query(PAYING_ACCOUNT,
                (row, index) -> new PayingAccount(
                        UUID.fromString(row.getString("source_account_id")),
                        row.getTimestamp("created_at").toInstant()),
                transactionId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private record PayingAccount(UUID accountId, Instant paidAt) {
    }

    /** For the admin endpoint: record a dispute directly, as a scheme would. */
    @Transactional
    public Dispute record(String externalId, UUID transactionId, DisputeReason reason,
                          long amountMinor, String currency, Instant raisedAt) {
        Instant now = Instant.now(clock);
        Optional<PayingAccount> payer = payingAccount(transactionId.toString());

        Dispute dispute = Dispute.of(externalId, transactionId.toString(), reason,
                amountMinor, currency,
                payer.map(PayingAccount::paidAt).orElse(null),
                raisedAt == null ? now : raisedAt,
                now);

        return disputes.save(dispute);
    }

    @Transactional(readOnly = true)
    public List<Dispute> all() {
        return disputes.findAll();
    }
}
