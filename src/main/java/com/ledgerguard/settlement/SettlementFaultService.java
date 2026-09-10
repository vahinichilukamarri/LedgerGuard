package com.ledgerguard.settlement;

import com.ledgerguard.settlement.dto.InjectFaultRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Makes the simulated processor misbehave on demand.
 *
 * <p>Every method here edits the external world only. Nothing in this class can
 * touch a ledger table, which is what keeps the demonstration honest: the
 * discrepancies it produces are genuine disagreements between two systems, not
 * a doctored ledger.
 */
@Service
public class SettlementFaultService {

    private static final Logger log = LoggerFactory.getLogger(SettlementFaultService.class);

    private final SettlementRecordRepository records;
    private final Clock clock;

    public SettlementFaultService(SettlementRecordRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    @Transactional
    public String inject(InjectFaultRequest request) {
        return switch (request.type()) {
            case DROP_SETTLEMENT -> drop(required(request.transactionId()));
            case RESTATE_AMOUNT -> restate(required(request.transactionId()),
                    request.amountDeltaMinor() == null ? -5_000L : request.amountDeltaMinor());
            case DUPLICATE_SETTLEMENT -> duplicate(required(request.transactionId()));
            case CHANGE_STATUS -> changeStatus(required(request.transactionId()),
                    request.newStatus() == null ? SettlementStatus.PENDING : request.newStatus());
            case PHANTOM_SETTLEMENT -> phantom(
                    request.amountMinor() == null ? 9_900L : request.amountMinor(),
                    request.currency() == null ? "USD" : request.currency());
        };
    }

    /** The processor loses the record. Reconciliation should then see MISSING_SETTLEMENT. */
    private String drop(UUID transactionId) {
        List<SettlementRecord> existing = existingFor(transactionId);
        records.deleteAll(existing);
        log.info("fault: dropped {} settlement record(s) for transaction {}", existing.size(), transactionId);
        return "dropped %d settlement record(s) for transaction %s".formatted(existing.size(), transactionId);
    }

    /** The processor settles a different amount. Reconciliation should see AMOUNT_MISMATCH. */
    private String restate(UUID transactionId, long deltaMinor) {
        SettlementRecord record = singleFor(transactionId);
        long before = record.getAmountMinor();
        long after = Math.max(0L, before + deltaMinor);
        record.restate(after);
        log.info("fault: restated settlement for transaction {} from {} to {}", transactionId, before, after);
        return "restated external amount for transaction %s from %d to %d minor units"
                .formatted(transactionId, before, after);
    }

    /** The processor settles twice. Reconciliation should see DUPLICATE_SETTLEMENT. */
    private String duplicate(UUID transactionId) {
        SettlementRecord original = singleFor(transactionId);
        SettlementRecord copy = SettlementRecord.of(
                "SIM-DUP-" + UUID.randomUUID(),
                original.getExternalReference(),
                original.getAmountMinor(),
                original.getCurrency(),
                original.getStatus(),
                Instant.now(clock));
        records.save(copy);
        log.info("fault: duplicated settlement for transaction {} as {}", transactionId, copy.getExternalId());
        return "added a second settlement record %s for transaction %s".formatted(
                copy.getExternalId(), transactionId);
    }

    /** The processor disagrees about state while agreeing about money. Should see STATUS_MISMATCH. */
    private String changeStatus(UUID transactionId, SettlementStatus newStatus) {
        SettlementRecord record = singleFor(transactionId);
        SettlementStatus before = record.getStatus();
        record.moveTo(newStatus);
        log.info("fault: moved settlement for transaction {} from {} to {}", transactionId, before, newStatus);
        return "moved external status for transaction %s from %s to %s".formatted(
                transactionId, before, newStatus);
    }

    /** Money moves externally that the ledger never authorised. Should see UNEXPECTED_EXTERNAL_TRANSACTION. */
    private String phantom(long amountMinor, String currency) {
        // A reference that resolves to nothing: the shape of an external record
        // arriving for a transaction we have never heard of.
        SettlementRecord record = SettlementRecord.of(
                "SIM-PHANTOM-" + UUID.randomUUID(),
                UUID.randomUUID().toString(),
                amountMinor, currency.toUpperCase(), SettlementStatus.SETTLED, Instant.now(clock));
        records.save(record);
        log.info("fault: invented phantom settlement {} for {} {}", record.getExternalId(), amountMinor, currency);
        return "created phantom settlement %s for %d %s referencing no internal transaction".formatted(
                record.getExternalId(), amountMinor, currency);
    }

    // --- helpers ---

    private static UUID required(UUID transactionId) {
        if (transactionId == null) {
            throw new IllegalArgumentException("transactionId is required for this fault type");
        }
        return transactionId;
    }

    private List<SettlementRecord> existingFor(UUID transactionId) {
        List<SettlementRecord> existing = records.findByExternalReference(transactionId.toString());
        if (existing.isEmpty()) {
            throw new IllegalArgumentException(
                    "no settlement record for transaction %s yet; the simulator consumes events asynchronously, "
                            .formatted(transactionId)
                            + "so give it a moment after the payment before injecting a fault");
        }
        return existing;
    }

    private SettlementRecord singleFor(UUID transactionId) {
        return existingFor(transactionId).get(0);
    }
}
