package com.ledgerguard.detection;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds {@link AccountActivity} samples for the signal unit tests.
 *
 * <p>Signals are pure functions of that record, so a test can state the exact
 * history it wants and assert on the answer, with no database, no clock and
 * nothing to seed. Every timestamp here is derived from a fixed instant, so two
 * runs of a test see byte-identical input.
 */
public final class ActivityFixtures {

    /** A fixed point in time. Nothing in these tests reads a real clock. */
    public static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");

    public static final String USD = "USD";

    private ActivityFixtures() {
    }

    public static Builder account() {
        return new Builder();
    }

    public static final class Builder {
        private final UUID accountId = UUID.randomUUID();
        private final List<AccountActivity.PaymentEvent> payments = new ArrayList<>();
        private Duration recentWindow = Duration.ofHours(1);
        private Duration baselineWindow = Duration.ofDays(30);
        private long transactionsInWindow;
        private long mismatchedInWindow;
        private long paymentsInWindow;
        private long returnedInWindow;
        private AccountActivity.GlobalRates rates = AccountActivity.GlobalRates.none();

        /** {@code count} payments of the same amount, one per day, ending before the window. */
        public Builder withRegularHistory(int count, long amountMinor) {
            for (int i = count; i >= 1; i--) {
                payments.add(payment(amountMinor, NOW.minus(Duration.ofDays(i))));
            }
            return this;
        }

        /** History with genuine spread, so MAD is non-zero: amounts step by {@code step}. */
        public Builder withVariedHistory(int count, long baseMinor, long step) {
            for (int i = count; i >= 1; i--) {
                // Alternating above and below the base keeps the median at base
                // while giving the deviations something to measure.
                long offset = (i % 2 == 0 ? 1 : -1) * step * ((i + 1) / 2);
                payments.add(payment(baseMinor + offset, NOW.minus(Duration.ofDays(i))));
            }
            return this;
        }

        /** A payment inside the recent window, {@code minutesAgo} before now. */
        public Builder withRecentPayment(long amountMinor, long minutesAgo) {
            payments.add(payment(amountMinor, NOW.minus(Duration.ofMinutes(minutesAgo))));
            return this;
        }

        /** {@code count} payments inside the recent window, spaced {@code spacing} apart. */
        public Builder withRecentRun(int count, long amountMinor, Duration spacing, Duration endingBefore) {
            for (int i = 0; i < count; i++) {
                payments.add(payment(amountMinor,
                        NOW.minus(endingBefore).minus(spacing.multipliedBy(i))));
            }
            return this;
        }

        public Builder withWindowTransactions(long total, long mismatched) {
            this.transactionsInWindow = total;
            this.mismatchedInWindow = mismatched;
            return this;
        }

        public Builder withWindowPayments(long total, long returned) {
            this.paymentsInWindow = total;
            this.returnedInWindow = returned;
            return this;
        }

        public Builder withGlobalRates(long totalTransactions, long mismatched,
                                long totalPayments, long returned) {
            this.rates = new AccountActivity.GlobalRates(
                    totalTransactions, mismatched, totalPayments, returned);
            return this;
        }

        public Builder withRecentWindow(Duration window) {
            this.recentWindow = window;
            return this;
        }

        public AccountActivity build() {
            return new AccountActivity(accountId, USD, NOW, recentWindow, baselineWindow,
                    List.copyOf(payments), transactionsInWindow, mismatchedInWindow,
                    paymentsInWindow, returnedInWindow, rates);
        }

        private static AccountActivity.PaymentEvent payment(long amountMinor, Instant at) {
            return new AccountActivity.PaymentEvent(UUID.randomUUID(), amountMinor, at);
        }
    }
}
