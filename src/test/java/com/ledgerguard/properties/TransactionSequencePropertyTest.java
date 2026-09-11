package com.ledgerguard.properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.config.Money;
import com.ledgerguard.properties.LedgerArbitraries.Step;
import com.ledgerguard.support.PropertyLedger;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whole chains of operations against one payment, legal and illegal mixed.
 *
 * <p>This is the property class that goes looking for trouble. Every other class
 * here tests one operation family in isolation; this one runs generated
 * sequences — payment, refund, refund, reversal, replay, in whatever order the
 * generator produces — and asserts what must be true of the ledger no matter
 * which steps succeeded and which were refused.
 *
 * <h2>The conservation property</h2>
 *
 * The invariant worth stating over a whole chain is not "every transaction
 * balances", which is a per-transaction fact and is checked elsewhere. It is
 * that <b>money is never created</b>:
 *
 * <ul>
 *   <li>the payer's balance never rises above zero — they can be made whole, but
 *       never end up better off than before they paid;</li>
 *   <li>the payee's balance never falls below zero;</li>
 *   <li>the two always sum to zero, because nothing else touches these
 *       accounts.</li>
 * </ul>
 *
 * <p>Every individual transaction in such a chain can balance perfectly while
 * this property is violated, which is precisely why it is stated separately.
 *
 * <h2>Tries</h2>
 *
 * 80 per property, each running up to seven operations with full state
 * assertions between them.
 */
@Tag("property")
class TransactionSequencePropertyTest {

    @Property(tries = 80)
    void noChainOfOperationsEverCreatesMoney(@ForAll("supported") String currency,
                                             @ForAll("amounts") long paymentMinor,
                                             @ForAll("chains") List<Step> chain,
                                             @ForAll("percents") List<Integer> refundPercents) {

        Run run = new Run(currency, paymentMinor);

        for (int i = 0; i < chain.size(); i++) {
            run.apply(chain.get(i), refundPercents.get(i % refundPercents.size()));

            long payerBalance = PropertyLedger.balanceMinorUnits(run.payer, currency);
            long payeeBalance = PropertyLedger.balanceMinorUnits(run.payee, currency);

            assertThat(payerBalance + payeeBalance)
                    .as("the pair must always net to zero%s", run.history())
                    .isZero();
            assertThat(payerBalance)
                    .as("the payer must never end up better off than before paying%s", run.history())
                    .isLessThanOrEqualTo(0L);
            assertThat(payeeBalance)
                    .as("the payee must never end up worse off than before being paid%s", run.history())
                    .isGreaterThanOrEqualTo(0L);
            assertThat(-payerBalance)
                    .as("the payer can never be out of pocket more than they paid%s", run.history())
                    .isLessThanOrEqualTo(paymentMinor);
        }
    }

    /**
     * The same chains, checked against the invariant this project is built
     * around: whatever happened, every persisted transaction still balances per
     * currency and the ledger as a whole nets to zero.
     */
    @Property(tries = 80)
    void noChainOfOperationsEverUnbalancesTheLedger(@ForAll("supported") String currency,
                                                    @ForAll("amounts") long paymentMinor,
                                                    @ForAll("chains") List<Step> chain,
                                                    @ForAll("percents") List<Integer> refundPercents) {

        Run run = new Run(currency, paymentMinor);

        for (int i = 0; i < chain.size(); i++) {
            run.apply(chain.get(i), refundPercents.get(i % refundPercents.size()));

            assertThat(PropertyLedger.unbalancedTransactionCurrencyPairs())
                    .as("no transaction may be unbalanced in any currency%s", run.history())
                    .isEmpty();
            assertThat(PropertyLedger.ledgerNetMinorUnits())
                    .as("the whole ledger must net to zero%s", run.history())
                    .isZero();
        }
    }

    /**
     * Refunds are capped by the payment, whatever else the chain did. Stated
     * over chains rather than over refunds alone because reversals and replays
     * are interleaved with them here.
     */
    @Property(tries = 80)
    void refundsAcrossAnyChainNeverExceedThePayment(@ForAll("supported") String currency,
                                                    @ForAll("refundableAmounts") long paymentMinor,
                                                    @ForAll("chains") List<Step> chain,
                                                    @ForAll("percents") List<Integer> refundPercents) {

        Run run = new Run(currency, paymentMinor);

        for (int i = 0; i < chain.size(); i++) {
            run.apply(chain.get(i), refundPercents.get(i % refundPercents.size()));

            assertThat(PropertyLedger.refundedTotalFor(run.paymentId))
                    .as("refunds may never sum past the payment%s", run.history())
                    .isLessThanOrEqualTo(paymentMinor);
        }
    }

    // ------------------------------------------------------------- generators

    @Provide
    Arbitrary<String> supported() {
        return LedgerArbitraries.supportedCurrencies();
    }

    @Provide
    Arbitrary<Long> amounts() {
        return LedgerArbitraries.chainAmountsMinor();
    }

    @Provide
    Arbitrary<Long> refundableAmounts() {
        return LedgerArbitraries.refundableAmountsMinor();
    }

    @Provide
    Arbitrary<List<Step>> chains() {
        return LedgerArbitraries.operationChains();
    }

    @Provide
    Arbitrary<List<Integer>> percents() {
        return LedgerArbitraries.refundPercents();
    }

    // ------------------------------------------------------------------- run

    /**
     * One payment plus the chain applied to it.
     *
     * <p>Steps that are refused are swallowed on purpose. A generated chain will
     * often contain illegal steps — a second reversal, a refund past the cap —
     * and the property is about what the ledger looks like afterwards, not about
     * which steps were legal. What each step did, including how it was refused,
     * is recorded in {@link #history()} so a shrunk counterexample says what
     * actually happened rather than only which enum constants were drawn.
     */
    private static final class Run {

        private final String currency;
        private final long paymentMinor;
        private final UUID payer;
        private final UUID payee;
        private final UUID paymentId;
        private final UUID paymentTransactionId;
        private final List<String> log = new ArrayList<>();

        private UUID lastRefundTransactionId;
        /** The transaction the last accepted reversal was applied TO, for a keyed replay. */
        private UUID lastReversedTransactionId;
        /** The transaction the last accepted reversal PRODUCED, so it can be reversed in turn. */
        private UUID lastReversalTransactionId;
        private String lastKey;
        private Step lastStep;
        private BigDecimal lastAmount;

        Run(String currency, long paymentMinor) {
            this.currency = currency;
            this.paymentMinor = paymentMinor;
            this.payer = PropertyLedger.createAccount(currency);
            this.payee = PropertyLedger.createAccount(currency);

            JsonNode payment = PropertyLedger.pay(
                    payer, payee, Money.toMajorUnits(paymentMinor, currency), currency);
            this.paymentId = PropertyLedger.paymentIdOf(payment);
            this.paymentTransactionId = PropertyLedger.transactionIdOf(payment);
            log.add("PAY " + paymentMinor + " " + currency);
        }

        void apply(Step step, int refundPercent) {
            switch (step) {
                case REFUND -> refund(refundPercent);
                case REVERSE_PAYMENT -> reverse(paymentTransactionId, "REVERSE_PAYMENT");
                case REVERSE_LAST_REFUND -> {
                    if (lastRefundTransactionId == null) {
                        log.add("REVERSE_LAST_REFUND (no refund yet, skipped)");
                    } else {
                        reverse(lastRefundTransactionId, "REVERSE_LAST_REFUND");
                    }
                }
                case REVERSE_LAST_REVERSAL -> {
                    if (lastReversalTransactionId == null) {
                        log.add("REVERSE_LAST_REVERSAL (no reversal yet, skipped)");
                    } else {
                        reverse(lastReversalTransactionId, "REVERSE_LAST_REVERSAL");
                    }
                }
                case REPLAY_LAST -> replayLast();
            }
        }

        private void refund(int percent) {
            long attempt = Math.max(1L, paymentMinor * percent / 100);
            BigDecimal amount = Money.toMajorUnits(attempt, currency);
            String key = PropertyLedger.freshKey();
            try {
                JsonNode refund = PropertyLedger.refund(key, paymentId, amount);
                lastRefundTransactionId = PropertyLedger.transactionIdOf(refund);
                remember(Step.REFUND, key, amount);
                log.add("REFUND " + attempt + " -> accepted");
            } catch (RuntimeException refused) {
                log.add("REFUND " + attempt + " -> " + refused.getClass().getSimpleName());
            }
        }

        private void reverse(UUID transactionId, String label) {
            String key = PropertyLedger.freshKey();
            try {
                JsonNode reversal = PropertyLedger.reverse(key, transactionId);
                remember(Step.REVERSE_PAYMENT, key, null);
                lastReversedTransactionId = transactionId;
                // Point the next REVERSE_LAST_REVERSAL at what this one produced,
                // so repeated steps go deeper instead of re-attempting the same
                // transaction and being refused every time after the first.
                lastReversalTransactionId = PropertyLedger.reversalTransactionIdOf(reversal);
                log.add(label + " -> accepted");
            } catch (RuntimeException refused) {
                log.add(label + " -> " + refused.getClass().getSimpleName());
            }
        }

        /**
         * Re-send the previous accepted operation under its original key. This is
         * the replay leg of the chain: it must be absorbed, changing nothing.
         */
        private void replayLast() {
            if (lastStep == null) {
                log.add("REPLAY_LAST (nothing to replay, skipped)");
                return;
            }
            try {
                if (lastStep == Step.REFUND) {
                    PropertyLedger.refund(lastKey, paymentId, lastAmount);
                } else {
                    PropertyLedger.reverse(lastKey, lastReversedTransactionId);
                }
                log.add("REPLAY_LAST " + lastStep + " -> replayed");
            } catch (RuntimeException refused) {
                log.add("REPLAY_LAST " + lastStep + " -> " + refused.getClass().getSimpleName());
            }
        }

        private void remember(Step step, String key, BigDecimal amount) {
            this.lastStep = step;
            this.lastKey = key;
            this.lastAmount = amount;
        }

        String history() {
            return "\n  sequence: " + String.join("\n            ", log);
        }
    }
}
