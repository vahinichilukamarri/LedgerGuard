package com.ledgerguard.properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.config.Money;
import com.ledgerguard.support.PropertyLedger;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exactly one financial effect per idempotency key, however the replay arrives.
 *
 * <p>{@code IdempotencyFlowIntegrationTest} proves this for one shape of request
 * and one concurrency level. These properties vary the amount, the currency, the
 * number of replays, the delay between them, and whether they are sequential or
 * simultaneous — and check the effect count after every replay rather than only
 * at the end.
 *
 * <h2>Tries</h2>
 *
 * 100 for the sequential properties, 25 for the concurrent one. The concurrent
 * property spawns up to eight threads per try, each of which genuinely blocks on
 * a PostgreSQL unique index while the winner finishes its ledger writes, so a
 * try there costs far more than a sequential one. 25 tries still runs the race
 * several hundred times over the property as a whole.
 */
@Tag("property")
class IdempotencyPropertyTest {

    /**
     * Replaying a payment under its original key never produces a second
     * payment, never moves money again, and hands back the same bytes.
     */
    @Property(tries = 100)
    void aReplayedPaymentHasExactlyOneFinancialEffect(@ForAll("supported") String currency,
                                                      @ForAll("amounts") long amountMinor,
                                                      @ForAll @IntRange(min = 1, max = 7) int replays,
                                                      @ForAll @LongRange(min = 0, max = 25) long gapMillis) {
        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);
        BigDecimal amount = Money.toMajorUnits(amountMinor, currency);
        String key = PropertyLedger.freshKey();

        JsonNode first = PropertyLedger.pay(key, payer, payee, amount, currency);
        UUID paymentId = PropertyLedger.paymentIdOf(first);

        long postingsAfterFirst = PropertyLedger.postingCount();
        long paymentsAfterFirst = PropertyLedger.paymentCount();

        for (int i = 0; i < replays; i++) {
            pause(gapMillis);
            JsonNode replayed = PropertyLedger.pay(key, payer, payee, amount, currency);

            assertThat(replayed)
                    .as("replay %d must return the original response, not a new one", i + 1)
                    .isEqualTo(first);
            assertThat(PropertyLedger.paymentIdOf(replayed)).isEqualTo(paymentId);

            // Financial state, checked after every replay rather than once at the end.
            assertThat(PropertyLedger.postingCount()).isEqualTo(postingsAfterFirst);
            assertThat(PropertyLedger.paymentCount()).isEqualTo(paymentsAfterFirst);
            assertThat(PropertyLedger.balanceMinorUnits(payer, currency)).isEqualTo(-amountMinor);
            assertThat(PropertyLedger.balanceMinorUnits(payee, currency)).isEqualTo(amountMinor);
        }

        assertThat(PropertyLedger.idempotencyKeyCount(key))
                .as("one key, one row, however many times it was sent")
                .isEqualTo(1L);
        assertThat(PropertyLedger.ledgerNetMinorUnits()).isZero();
    }

    /**
     * The same guarantee under a genuine race: N threads, one key, all released
     * at once. Exactly one of them does the ledger writes; the rest replay it.
     */
    @Property(tries = 25)
    void concurrentDuplicatesStillProduceExactlyOneEffect(@ForAll("supported") String currency,
                                                          @ForAll("amounts") long amountMinor,
                                                          @ForAll @IntRange(min = 2, max = 8) int threads) {
        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);
        BigDecimal amount = Money.toMajorUnits(amountMinor, currency);
        String key = PropertyLedger.freshKey();

        long postingsBefore = PropertyLedger.postingCount();
        long paymentsBefore = PropertyLedger.paymentCount();

        List<JsonNode> responses = fireAtOnce(threads,
                () -> PropertyLedger.pay(key, payer, payee, amount, currency));

        assertThat(responses).hasSize(threads);
        assertThat(responses).as("every racer must come back with the same response")
                .allMatch(response -> response.equals(responses.get(0)));

        assertThat(PropertyLedger.idempotencyKeyCount(key)).isEqualTo(1L);
        assertThat(PropertyLedger.paymentCount())
                .as("%d concurrent requests under one key must create one payment", threads)
                .isEqualTo(paymentsBefore + 1);
        assertThat(PropertyLedger.postingCount()).isEqualTo(postingsBefore + 2);
        assertThat(PropertyLedger.balanceMinorUnits(payer, currency)).isEqualTo(-amountMinor);
        assertThat(PropertyLedger.balanceMinorUnits(payee, currency)).isEqualTo(amountMinor);
        assertThat(PropertyLedger.ledgerNetMinorUnits()).isZero();
    }

    /**
     * Replay safety is not a payment-only feature. A refund replayed under its
     * key refunds once, and the refundable amount moves once.
     */
    @Property(tries = 100)
    void aReplayedRefundHasExactlyOneFinancialEffect(@ForAll("supported") String currency,
                                                     @ForAll("refundableAmounts") long amountMinor,
                                                     @ForAll @IntRange(min = 1, max = 5) int replays) {
        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);

        JsonNode payment = PropertyLedger.pay(
                payer, payee, Money.toMajorUnits(amountMinor, currency), currency);
        UUID paymentId = PropertyLedger.paymentIdOf(payment);

        long half = Math.max(1L, amountMinor / 2);
        BigDecimal refundAmount = Money.toMajorUnits(half, currency);
        String key = PropertyLedger.freshKey();

        JsonNode first = PropertyLedger.refund(key, paymentId, refundAmount);

        for (int i = 0; i < replays; i++) {
            assertThat(PropertyLedger.refund(key, paymentId, refundAmount)).isEqualTo(first);

            assertThat(PropertyLedger.refundCountFor(paymentId))
                    .as("a replayed refund must not refund again")
                    .isEqualTo(1L);
            assertThat(PropertyLedger.refundedTotalFor(paymentId)).isEqualTo(half);
            assertThat(PropertyLedger.balanceMinorUnits(payer, currency))
                    .isEqualTo(-(amountMinor - half));
        }
        assertThat(PropertyLedger.ledgerNetMinorUnits()).isZero();
    }

    /**
     * And a reversal, which is single-use by a UNIQUE constraint anyway. Under a
     * key, the retry replays the original 201 instead of surfacing the
     * already-reversed refusal — the behaviour {@code ReversalController}
     * documents, here across arbitrary inputs.
     */
    @Property(tries = 100)
    void aReplayedReversalHasExactlyOneFinancialEffect(@ForAll("supported") String currency,
                                                       @ForAll("amounts") long amountMinor,
                                                       @ForAll @IntRange(min = 1, max = 5) int replays) {
        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);

        JsonNode payment = PropertyLedger.pay(
                payer, payee, Money.toMajorUnits(amountMinor, currency), currency);
        UUID transactionId = PropertyLedger.transactionIdOf(payment);

        String key = PropertyLedger.freshKey();
        JsonNode first = PropertyLedger.reverse(key, transactionId);
        long postingsAfterFirst = PropertyLedger.postingCount();

        for (int i = 0; i < replays; i++) {
            assertThat(PropertyLedger.reverse(key, transactionId)).isEqualTo(first);

            assertThat(PropertyLedger.reversalCountFor(transactionId)).isEqualTo(1L);
            assertThat(PropertyLedger.postingCount()).isEqualTo(postingsAfterFirst);
            // Payment plus its reversal: both accounts are back to nothing.
            assertThat(PropertyLedger.balanceMinorUnits(payer, currency)).isZero();
            assertThat(PropertyLedger.balanceMinorUnits(payee, currency)).isZero();
        }
        assertThat(PropertyLedger.ledgerNetMinorUnits()).isZero();
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

    // ---------------------------------------------------------------- helpers

    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Release every caller from a latch so they hit the key together, rather
     * than submitting them one at a time and hoping the pool overlaps them.
     */
    private static List<JsonNode> fireAtOnce(int threads, Callable<JsonNode> call) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JsonNode>> futures = new ArrayList<>(threads);

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();

            List<JsonNode> responses = new ArrayList<>(threads);
            for (Future<JsonNode> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("a concurrent duplicate failed outright", e);
        } finally {
            pool.shutdownNow();
        }
    }
}
