package com.ledgerguard.detection;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Everything the signals are allowed to look at, gathered once.
 *
 * <h2>Why a snapshot rather than repository access</h2>
 *
 * Signals are pure functions of this record. They issue no queries, read no
 * clock and hold no state, which is the same split Phase 5 draws between
 * {@code Reconciler} and {@code ReconciliationService} and it buys the same
 * things: a signal can be unit-tested with a hand-built sample and no database,
 * every signal in one scoring pass sees exactly the same view of the ledger, and
 * a signal cannot accidentally become expensive by querying inside a loop.
 *
 * <p>It also makes the tests deterministic by construction. Nothing here is read
 * from {@code Instant.now()}; {@code asOf} is supplied, so a scenario can place
 * itself anywhere in time without waiting.
 *
 * @param outboundPayments payments <em>from</em> this account within
 *                         {@code baselineWindow}, ascending by time. Outbound is
 *                         the fraud-relevant direction: money leaving is what an
 *                         attacker wants, and an account cannot control what
 *                         arrives.
 * @param globalRates      population rates for the proportion signals. Per-account
 *                         baselines are far too thin to estimate a rate from —
 *                         most accounts have never had a mismatch at all — so the
 *                         comparison is against the ledger as a whole.
 */
public record AccountActivity(
        UUID accountId,
        String currency,
        Instant asOf,
        Duration recentWindow,
        Duration baselineWindow,
        List<PaymentEvent> outboundPayments,
        long transactionsInWindow,
        long mismatchedTransactionsInWindow,
        long paymentsInWindow,
        long refundedOrReversedPaymentsInWindow,
        GlobalRates globalRates) {

    public AccountActivity {
        outboundPayments = outboundPayments.stream()
                .sorted(Comparator.comparing(PaymentEvent::occurredAt)
                        .thenComparing(PaymentEvent::paymentId))
                .toList();
    }

    /**
     * One payment, reduced to what a signal needs.
     *
     * <p>The id travels with it so a fired signal can name the transaction that
     * caused it. A score nobody can trace back to a row is an alert nobody can
     * action.
     */
    public record PaymentEvent(UUID paymentId, long amountMinor, Instant occurredAt) {
    }

    /** Ledger-wide counts, used as the prior for the two proportion signals. */
    public record GlobalRates(long totalTransactions, long mismatchedTransactions,
                              long totalPayments, long refundedOrReversedPayments) {

        public static GlobalRates none() {
            return new GlobalRates(0, 0, 0, 0);
        }
    }

    /** The instant at which the recent window opens. */
    public Instant windowStart() {
        return asOf.minus(recentWindow);
    }

    /** Payments inside the recent window: what is being judged. */
    public List<PaymentEvent> paymentsInRecentWindow() {
        Instant start = windowStart();
        return outboundPayments.stream()
                .filter(payment -> !payment.occurredAt().isBefore(start))
                .toList();
    }

    /**
     * Payments strictly before the recent window: what it is judged against.
     *
     * <p>The split is load-bearing. If the recent window were included in its own
     * baseline, a burst would raise the rate it is being compared to and partly
     * hide itself — the same masking problem that rules out the mean and standard
     * deviation for amounts, arriving by a different route.
     */
    public List<PaymentEvent> paymentsInBaseline() {
        Instant start = windowStart();
        return outboundPayments.stream()
                .filter(payment -> payment.occurredAt().isBefore(start))
                .toList();
    }

    /** How long the baseline period covers, once the recent window is removed. */
    public Duration baselineSpan() {
        Duration span = baselineWindow.minus(recentWindow);
        return span.isNegative() || span.isZero() ? Duration.ofSeconds(1) : span;
    }
}
