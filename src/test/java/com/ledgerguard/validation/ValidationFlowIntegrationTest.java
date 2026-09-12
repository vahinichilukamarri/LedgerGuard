package com.ledgerguard.validation;

import com.ledgerguard.accounts.AccountService;
import com.ledgerguard.chaos.TickingClock;
import com.ledgerguard.config.Money;
import com.ledgerguard.detection.ml.MlDetectionService;
import com.ledgerguard.detection.CompositeAggregation;
import com.ledgerguard.detection.DetectionService;
import com.ledgerguard.detection.SignalScore;
import com.ledgerguard.detection.ml.Agreement;
import com.ledgerguard.payments.PaymentService;
import com.ledgerguard.support.LedgerPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Labels against a real ledger, and the two mistakes this phase exists to make
 * impossible.
 *
 * <p>The unit tests establish that the arithmetic is right. They cannot
 * establish the two things that actually decide whether an evaluation means
 * anything, because both are properties of how scores and labels are joined:
 * that an account is scored as of the moment its label describes rather than as
 * of now, and that recall is refused when no audit stratum exists. Both are
 * tested here against a ledger that really moves.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "ledgerguard.outbox.publisher.enabled=false",
                "spring.kafka.listener.auto-startup=false",
                "spring.kafka.admin.auto-create=false",
                "logging.level.com.ledgerguard=WARN",
                "logging.level.org.springframework=WARN",
                "logging.level.org.hibernate=WARN"
        })
@Import(ValidationFlowIntegrationTest.FixedClockConfig.class)
class ValidationFlowIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = start();

    private static PostgreSQLContainer<?> start() {
        PostgreSQLContainer<?> container = LedgerPostgres.newContainer();
        container.start();
        return container;
    }

    static final Instant EPOCH = Instant.parse("2026-09-12T09:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        @Primary
        TickingClock validationClock() {
            return new TickingClock(EPOCH);
        }
    }

    private static final String TRUNCATE_ALL = """
            TRUNCATE TABLE account_labels, disputes,
                           reconciliation_incidents, reconciliation_runs, settlement_records,
                           processed_events, outbox_events, idempotency_keys,
                           reversals, refunds, postings, payments, transactions, accounts
            RESTART IDENTITY CASCADE
            """;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TickingClock clock;
    @Autowired
    private AccountService accounts;
    @Autowired
    private PaymentService payments;
    @Autowired
    private DetectionService detection;
    @Autowired
    private MlDetectionService ml;
    @Autowired
    private LabelService labels;
    @Autowired
    private ReviewQueue queue;
    @Autowired
    private Evaluator evaluator;
    @Autowired
    private DisputeLabeller disputes;
    @Autowired
    private AccountLabelRepository labelRepository;

    @BeforeEach
    void knownStartingState() {
        clock.resetTo(EPOCH);
        jdbc.execute(TRUNCATE_ALL);
        ml.forget();
    }

    // ------------------------------------------------------------- leakage

    /**
     * The mistake that produces a superb evaluation and a detector that fails in
     * production, because in production the future has not happened yet.
     */
    @Test
    @DisplayName("an account is scored as of its label, not as of now")
    void scoresAreComputedAsOfTheLabel() {
        UUID payer = account();
        UUID payee = account();

        // Forty ordinary payments, one a day. Nothing to see.
        for (int day = 0; day < 40; day++) {
            clock.advance(Duration.ofDays(1));
            pay(payer, payee, 10_000 + day * 13L);
        }
        Instant quiet = clock.instant();
        double scoreWhenQuiet = statisticalScoreAt(payer, quiet);

        // A reviewer judges the account on that day, seeing a quiet account.
        labels.record(payer, Verdict.BENIGN, "alex", Stratum.AUDIT, false, quiet, "ordinary");

        // Later the same account does something wild.
        clock.advance(Duration.ofDays(1));
        for (int i = 0; i < 8; i++) {
            clock.advance(Duration.ofSeconds(2));
            pay(payer, payee, 9_000_000);
        }
        double scoreNow = statisticalScoreAt(payer, clock.instant());

        assertThat(scoreNow)
                .as("the account really is far more alarming later, or this test proves nothing")
                .isGreaterThan(scoreWhenQuiet);

        List<LabelledScore> scored = evaluator.labelledScores(
                EnumSet.of(LabelSource.HUMAN_REVIEW), clock.instant());

        assertThat(scored).hasSize(1);
        assertThat(scored.get(0).statisticalScore())
                .as("the evaluation must see what the detector saw on the day of the label, "
                        + "not what the fraud itself later caused")
                .isEqualTo(scoreWhenQuiet);
        assertThat(scored.get(0).asOf()).isEqualTo(quiet);
    }

    // -------------------------------------------------------------- recall

    @Test
    @DisplayName("without an audit stratum, recall is refused rather than reported as 1.0")
    void recallIsUnmeasurableWithoutAnAuditStratum() {
        List<UUID> population = ordinaryPopulation(6);
        UUID flagged = anomalousAccount();
        Instant asOf = clock.instant();

        labels.record(flagged, Verdict.ANOMALOUS, "alex", Stratum.FLAGGED, false, asOf, null);
        labels.record(population.get(0), Verdict.BENIGN, "alex", Stratum.FLAGGED, false, asOf, null);

        EvaluationReport report = evaluator.evaluate(EnumSet.of(LabelSource.HUMAN_REVIEW), asOf);

        assertThat(report.statistical().recallMeasurable()).isFalse();
        assertThat(report.statistical().recall().point()).isNaN();
        assertThat(report.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("recall is unmeasurable"));
        assertThat(report.statistical().precision().trials())
                .as("precision is unaffected: the flagged stratum is exactly its denominator")
                .isPositive();
    }

    @Test
    @DisplayName("one audit label makes recall measurable")
    void auditStratumRestoresRecall() {
        List<UUID> population = ordinaryPopulation(6);
        UUID flagged = anomalousAccount();
        Instant asOf = clock.instant();

        labels.record(flagged, Verdict.ANOMALOUS, "alex", Stratum.FLAGGED, false, asOf, null);
        labels.record(population.get(0), Verdict.BENIGN, "alex", Stratum.AUDIT, false, asOf, null);

        EvaluationReport report = evaluator.evaluate(EnumSet.of(LabelSource.HUMAN_REVIEW), asOf);

        assertThat(report.statistical().recallMeasurable()).isTrue();
        assertThat(report.warnings())
                .noneSatisfy(warning -> assertThat(warning).contains("recall is unmeasurable"));
    }

    /**
     * The strata are sampled at completely different rates, so their raw counts
     * describe a population that does not exist.
     */
    @Test
    @DisplayName("an audit label speaks for every unflagged account it was drawn from")
    void auditLabelsAreWeightedToTheirStratum() {
        List<UUID> population = ordinaryPopulation(8);
        Instant asOf = clock.instant();
        ReviewQueue.Census census = queue.census(asOf);

        labels.record(population.get(0), Verdict.BENIGN, "alex", Stratum.AUDIT, false, asOf, null);

        List<LabelledScore> scored = evaluator.labelledScores(
                EnumSet.of(LabelSource.HUMAN_REVIEW), asOf);

        assertThat(census.unflagged()).isGreaterThan(1);
        assertThat(scored.get(0).weight())
                .as("one reviewed account out of %d unflagged stands for all of them",
                        census.unflagged())
                .isEqualTo(census.unflagged());
    }

    // ------------------------------------------------------------ disputes

    @Test
    @DisplayName("a dispute that has not been raised yet is not a label")
    void disputesDoNotLabelBeforeTheyMature() {
        UUID payer = account();
        UUID payee = account();
        clock.advance(Duration.ofMinutes(5));
        UUID transactionId = pay(payer, payee, 50_000);

        Instant raisedAt = clock.instant().plus(Duration.ofDays(45));
        disputes.record("CB-TEST-1", transactionId, DisputeReason.FRAUDULENT,
                50_000, "USD", raisedAt);

        assertThat(disputes.labelMatured(clock.instant()))
                .as("the scheme will raise this in 45 days; today it is not evidence of anything")
                .isZero();
        assertThat(disputes.pendingCount()).isEqualTo(1);

        clock.advance(Duration.ofDays(46));
        assertThat(disputes.labelMatured(clock.instant())).isEqualTo(1);

        List<AccountLabel> written = labelRepository.findByAccountId(payer);
        assertThat(written).hasSize(1);
        assertThat(written.get(0).getVerdict()).isEqualTo(Verdict.ANOMALOUS);
        assertThat(written.get(0).getSource()).isEqualTo(LabelSource.DISPUTE_FEED);
        assertThat(written.get(0).latency().toDays())
                .as("the label describes September and arrived in November")
                .isGreaterThanOrEqualTo(45);
    }

    @Test
    @DisplayName("a merchant dispute is recorded and never becomes a fraud label")
    void nonFraudDisputesDoNotLabel() {
        UUID payer = account();
        UUID payee = account();
        clock.advance(Duration.ofMinutes(5));
        UUID transactionId = pay(payer, payee, 50_000);

        disputes.record("CB-TEST-2", transactionId, DisputeReason.NOT_RECEIVED,
                50_000, "USD", clock.instant());

        assertThat(disputes.labelMatured(clock.instant())).isZero();
        assertThat(labelRepository.findByAccountId(payer)).isEmpty();
    }

    @Test
    @DisplayName("labelling twice from the same dispute does not double-count it")
    void disputeLabellingIsIdempotent() {
        UUID payer = account();
        UUID payee = account();
        clock.advance(Duration.ofMinutes(5));
        UUID transactionId = pay(payer, payee, 50_000);
        disputes.record("CB-TEST-3", transactionId, DisputeReason.FRAUDULENT,
                50_000, "USD", clock.instant());

        assertThat(disputes.labelMatured(clock.instant())).isEqualTo(1);
        assertThat(disputes.labelMatured(clock.instant())).isZero();
        assertThat(labelRepository.findByAccountId(payer)).hasSize(1);
    }

    // --------------------------------------------------------- review queue

    @Test
    @DisplayName("the two strata draw from disjoint pools")
    void strataAreDisjoint() {
        ordinaryPopulation(6);
        UUID alarming = anomalousAccount();
        Instant asOf = clock.instant();

        Optional<ReviewQueue.Candidate> flagged = queue.next(Stratum.FLAGGED, asOf);
        Optional<ReviewQueue.Candidate> audit = queue.next(Stratum.AUDIT, asOf);

        assertThat(flagged).isPresent();
        assertThat(flagged.orElseThrow().accountId()).isEqualTo(alarming);
        assertThat(audit).isPresent();
        assertThat(audit.orElseThrow().accountId())
                .as("the audit stratum must never hand back an account the detector flagged")
                .isNotEqualTo(alarming);
    }

    @Test
    @DisplayName("an account anyone has labelled is not offered again")
    void labelledAccountsLeaveTheQueue() {
        ordinaryPopulation(6);
        UUID alarming = anomalousAccount();
        Instant asOf = clock.instant();

        labels.record(alarming, Verdict.ANOMALOUS, "alex", Stratum.FLAGGED, false, asOf, null);

        assertThat(queue.next(Stratum.FLAGGED, asOf))
                .as("two reviewers working the queue should not collide")
                .isEmpty();
    }

    @Test
    @DisplayName("the census counts every account into exactly one pool")
    void censusPartitionsThePopulation() {
        ordinaryPopulation(6);
        anomalousAccount();
        ReviewQueue.Census census = queue.census(clock.instant());

        assertThat(census.total()).isEqualTo(accountCount());
        assertThat(census.flagged()).isPositive();
        assertThat(census.unflagged()).isPositive();
    }

    // ------------------------------------------------------------- labelling

    @Test
    @DisplayName("a resubmitted verdict is a replay, not a second opinion")
    void labellingIsIdempotent() {
        UUID subject = account();
        Instant asOf = clock.instant();

        AccountLabel first = labels.record(subject, Verdict.BENIGN, "alex", Stratum.AUDIT,
                false, asOf, "fine");
        AccountLabel replay = labels.record(subject, Verdict.BENIGN, "alex", Stratum.AUDIT,
                false, asOf, "fine");

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(labelRepository.findByAccountId(subject))
                .as("a retry must not read as a reviewer disagreeing with themselves")
                .hasSize(1);
    }

    @Test
    @DisplayName("two reviewers disagreeing is recorded, and measured")
    void disagreementIsMeasured() {
        UUID contested = account();
        UUID agreed = account();
        Instant asOf = clock.instant();

        labels.record(contested, Verdict.ANOMALOUS, "alex", Stratum.FLAGGED, false, asOf, null);
        labels.record(contested, Verdict.BENIGN, "sam", Stratum.FLAGGED, false, asOf, null);
        labels.record(agreed, Verdict.BENIGN, "alex", Stratum.AUDIT, false, asOf, null);
        labels.record(agreed, Verdict.BENIGN, "sam", Stratum.AUDIT, false, asOf, null);

        LabelService.Agreement agreement = labels.agreement();

        assertThat(labelRepository.findByAccountId(contested))
                .as("both verdicts survive; the first is not edited away")
                .hasSize(2);
        assertThat(agreement.doublyReviewed()).isEqualTo(2);
        assertThat(agreement.agreed()).isEqualTo(1);
        assertThat(agreement.rate()).contains(0.5);
    }

    @Test
    @DisplayName("with nobody re-reviewed, the agreement rate is absent rather than perfect")
    void unmeasuredAgreementIsNotPerfectAgreement() {
        UUID subject = account();
        labels.record(subject, Verdict.BENIGN, "alex", Stratum.AUDIT, false, clock.instant(), null);

        assertThat(labels.agreement().rate()).isEmpty();
    }

    @Test
    @DisplayName("an UNCLEAR verdict is counted and kept out of the arithmetic")
    void unclearVerdictsAreExcluded() {
        List<UUID> population = ordinaryPopulation(6);
        Instant asOf = clock.instant();

        labels.record(population.get(0), Verdict.UNCLEAR, "alex", Stratum.AUDIT, false, asOf, null);
        labels.record(population.get(1), Verdict.BENIGN, "alex", Stratum.AUDIT, false, asOf, null);

        EvaluationReport report = evaluator.evaluate(EnumSet.of(LabelSource.HUMAN_REVIEW), asOf);

        assertThat(report.labels().unclear()).isEqualTo(1);
        assertThat(report.labels().decisive()).isEqualTo(1);
        assertThat(report.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("could not be judged"));
    }

    // -------------------------------------------------------------- warnings

    @Test
    @DisplayName("an anchored label is reported as anchored")
    void anchoredLabelsAreFlagged() {
        List<UUID> population = ordinaryPopulation(6);
        Instant asOf = clock.instant();

        labels.record(population.get(0), Verdict.BENIGN, "alex", Stratum.AUDIT, true, asOf, null);

        EvaluationReport report = evaluator.evaluate(EnumSet.of(LabelSource.HUMAN_REVIEW), asOf);

        assertThat(report.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("scores visible"));
        assertThat(report.isQuotable()).isFalse();
    }

    @Test
    @DisplayName("including synthetic labels says so, in the report")
    void syntheticLabelsWarn() {
        UUID subject = account();
        Instant asOf = clock.instant();
        labelRepository.save(AccountLabel.synthetic(subject, Verdict.ANOMALOUS, asOf, "by construction"));

        EvaluationReport report = evaluator.evaluate(EnumSet.of(LabelSource.SYNTHETIC), asOf);

        assertThat(report.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("circular"));
    }

    @Test
    @DisplayName("an empty label set measures nothing, and says so")
    void noLabelsMeasuresNothing() {
        ordinaryPopulation(4);
        EvaluationReport report = evaluator.evaluate(
                EnumSet.of(LabelSource.HUMAN_REVIEW), clock.instant());

        assertThat(report.warnings()).contains("no decisive labels: nothing here is measured");
        assertThat(report.statistical().precision().point()).isNaN();
    }

    // ------------------------------------------------------- synthetic bench

    /**
     * The circular benchmark, run deliberately and labelled as circular.
     *
     * <h2>What this measures</h2>
     *
     * A population is built in which some accounts are anomalous <em>by
     * construction</em> — they send amounts hundreds of times their own history,
     * in bursts — and every account is labelled with the ground truth used to
     * build it. The detector is then measured against those labels.
     *
     * <p>It establishes one thing: that the pipeline carries an
     * anomalous-by-construction account through to a high score, and that the
     * harness computes sensible numbers end to end. It establishes <b>nothing
     * about detection quality</b>, because the anomalies were designed by
     * someone who knew what the detector looks for. A high average precision
     * here is a statement about this test, not about fraud.
     *
     * <p>It is here anyway, because a harness that has never produced a number
     * is a harness nobody has checked, and because the alternative — quoting
     * figures from a handful of human labels — would be worse.
     */
    @Test
    @DisplayName("synthetic benchmark: the harness produces sensible numbers end to end")
    void syntheticBenchmark() {
        List<UUID> ordinary = new ArrayList<>();
        List<UUID> anomalous = new ArrayList<>();
        UUID payee = account();

        for (int i = 0; i < 40; i++) {
            ordinary.add(account());
        }
        for (int i = 0; i < 10; i++) {
            anomalous.add(account());
        }

        // ALSO CORRECTED IN PHASE 13. The histories used to be built one
        // account at a time, which pushed the earliest accounts' payments
        // outside the thirty-day baseline window by the time the last account
        // was finished -- so their amount signal had no history to judge
        // against and reported insufficient data. Interleaving puts every
        // account's history in the same six-day span, which is both more
        // realistic and the only way every account is actually measurable when
        // the benchmark asks.
        quietHistories(ordinary, payee, 5_000);
        quietHistories(anomalous, payee, 4_000);

        // CORRECTED IN PHASE 13. The bursts used to happen inside the loop
        // above, which advanced the clock by 150 hours per account afterwards,
        // so by the time the benchmark scored anything only the LAST anomalous
        // account still had its burst inside the one-hour recent window. The
        // other nine had no recent payments at all, so the amount and burst
        // signals reported insufficient data and could not fire whatever the
        // aggregation did.
        //
        // That is what Phase 12 measured and attributed to the composite
        // ceiling. The ceiling is real and provable independently of any data
        // -- see CompositeCeilingTest -- but this benchmark never exercised it.
        // Doing the bursts together, at the end, is what makes the anomalies
        // visible to the detector at the moment it is asked.
        for (int i = 0; i < anomalous.size(); i++) {
            clock.advance(Duration.ofSeconds(20));
            for (int burst = 0; burst < 5; burst++) {
                clock.advance(Duration.ofSeconds(3));
                pay(anomalous.get(i), payee, 6_000_000 + (long) i * 1_000);
            }
        }

        Instant asOf = clock.instant();
        ml.train(asOf);

        ordinary.forEach(id -> labelRepository.save(
                AccountLabel.synthetic(id, Verdict.BENIGN, asOf, "quiet by construction")));
        anomalous.forEach(id -> labelRepository.save(
                AccountLabel.synthetic(id, Verdict.ANOMALOUS, asOf, "anomalous by construction")));

        EvaluationReport report = evaluator.evaluate(EnumSet.of(LabelSource.SYNTHETIC), asOf);

        assertThat(report.warnings())
                .as("the circularity must be stated in the payload, not just in a report")
                .anySatisfy(warning -> assertThat(warning).contains("circular"));

        EvaluationReport.LayerResult statistical = report.statistical();
        assertThat(statistical.curve().averagePrecision())
                .as("a pipeline that could not separate these could not separate anything")
                .isGreaterThan(statistical.curve().chanceLevel());
        assertThat(statistical.recallMeasurable())
                .as("synthetic ground truth is complete, so recall is defined here")
                .isTrue();

        printBeforeAndAfter(ordinary, anomalous, asOf);
        print(report, anomalous.size(), ordinary.size());
    }

    /**
     * How far the ceiling reaches on a real ledger, which turned out to be the
     * more interesting question.
     *
     * <h2>What this was written to show, and did not</h2>
     *
     * The intent was a population the legacy aggregation could not flag and the
     * new one can: accounts anomalous on exactly one axis. It builds them
     * correctly — a single payment far outside the account's own history, no
     * burst because one payment is not a cluster, no rate spike because one
     * payment in an hour is unremarkable against four a day — and all ten
     * saturate the amount signal.
     *
     * <p>Both aggregations flag all ten. The reason is the applicable count:
     * such an account has <b>two</b> applicable signals, not five. The burst
     * signal needs three recent payments, and the two rate signals each need
     * ten transactions in the recent window, so a lone payment leaves only
     * amount and velocity able to judge — and at two applicable signals the
     * legacy weighted mean gave 0.25/0.45 = 0.556 and flagged perfectly well.
     *
     * <h2>The reachability boundary</h2>
     *
     * The ceiling bites from three applicable signals upward. Reaching that on
     * this ledger needs an account with enough recent activity to make the
     * burst or rate signals measurable, while remaining unremarkable on those
     * axes — a high-volume account making one out-of-character payment. Keeping
     * velocity quiet at ten transactions an hour requires a baseline rate of
     * about ten an hour, which across the thirty-day baseline span is roughly
     * seven thousand payments. That is a real and important account type and it
     * is not one a test should build.
     *
     * <p>So the ceiling is proven where it can be proven exactly — over all 31
     * applicable sets in {@code CompositeCeilingTest}, from the weights alone —
     * and this test records the boundary of what a ledger-level benchmark can
     * reach. An honest "no change here, and here is why" is worth more than a
     * fixture contorted until the number moves.
     */
    @Test
    @DisplayName("a lone anomalous payment leaves two applicable signals, below the ceiling")
    void singleAxisBenchmark() {
        List<UUID> ordinary = new ArrayList<>();
        List<UUID> anomalous = new ArrayList<>();
        UUID payee = account();

        for (int i = 0; i < 20; i++) {
            ordinary.add(account());
        }
        for (int i = 0; i < 10; i++) {
            anomalous.add(account());
        }
        quietHistories(ordinary, payee, 5_000);
        quietHistories(anomalous, payee, 4_000);

        // One payment each, far outside their own history, all inside the
        // recent window. Deliberately not a burst and deliberately not a rate
        // spike: exactly one signal is meant to fire.
        for (int i = 0; i < anomalous.size(); i++) {
            clock.advance(Duration.ofSeconds(30));
            pay(anomalous.get(i), payee, 8_000_000 + (long) i * 1_000);
        }

        Instant asOf = clock.instant();

        int legacyFlagged = 0;
        int flagged = 0;
        int legacyFalsePositives = 0;
        int falsePositives = 0;
        int amountSaturated = 0;

        for (UUID accountId : anomalous) {
            List<SignalScore> signals = detection.assess(accountId, asOf).score().signals();
            if (signals.stream().anyMatch(signal ->
                    signal.signal() == com.ledgerguard.detection.Signal.AMOUNT_OUTLIER
                            && signal.score() >= 1.0)) {
                amountSaturated++;
            }
            if (legacyComposite(signals) >= Agreement.STATISTICAL_ELEVATED) {
                legacyFlagged++;
            }
            if (CompositeAggregation.combine(signals) >= Agreement.STATISTICAL_ELEVATED) {
                flagged++;
            }
        }
        for (UUID accountId : ordinary) {
            List<SignalScore> signals = detection.assess(accountId, asOf).score().signals();
            if (legacyComposite(signals) >= Agreement.STATISTICAL_ELEVATED) {
                legacyFalsePositives++;
            }
            if (CompositeAggregation.combine(signals) >= Agreement.STATISTICAL_ELEVATED) {
                falsePositives++;
            }
        }

        int applicable = detection.assess(anomalous.get(0), asOf).score().applicableSignals();

        System.out.println("=== SINGLE-AXIS ANOMALIES (reachability of the ceiling) ===");
        System.out.printf("  %d of %d saturate the amount signal; %d signals applicable each%n",
                amountSaturated, anomalous.size(), applicable);
        System.out.printf("  Phase 8 weighted mean : recall %d/%d, false positives %d/%d%n",
                legacyFlagged, anomalous.size(), legacyFalsePositives, ordinary.size());
        System.out.printf("  Phase 13 power mean   : recall %d/%d, false positives %d/%d%n",
                flagged, anomalous.size(), falsePositives, ordinary.size());

        assertThat(amountSaturated)
                .as("the fixture must actually produce single-axis anomalies, or it tests nothing")
                .isEqualTo(anomalous.size());
        assertThat(applicable)
                .as("a lone payment cannot make burst or the rate signals measurable, which is "
                        + "why this population sits below the ceiling rather than under it")
                .isEqualTo(2);
        assertThat(legacyFlagged)
                .as("at two applicable signals the legacy mean already reached 0.556")
                .isEqualTo(anomalous.size());
        assertThat(flagged)
                .as("and the fix flags them too: no regression on the population that worked")
                .isEqualTo(anomalous.size());
        assertThat(falsePositives)
                .as("neither aggregation flags an ordinary account")
                .isZero();
        assertThat(legacyFalsePositives).isZero();
    }

    /**
     * Recall under the Phase 8 aggregation and under Phase 13's, on identical
     * scores.
     *
     * <p>Both numbers come from the same {@link SignalScore} lists, so nothing
     * differs but the function that combines them. This is validation of the
     * fix and not calibration of anything: no weight and no threshold was
     * chosen with reference to these labels, and the exponent was derived from
     * the weights before the benchmark was run.
     */
    private void printBeforeAndAfter(List<UUID> ordinary, List<UUID> anomalous, Instant asOf) {
        int legacyFlagged = 0;
        int flagged = 0;
        int legacyFalsePositives = 0;
        int falsePositives = 0;

        for (UUID accountId : anomalous) {
            List<SignalScore> signals = detection.assess(accountId, asOf).score().signals();
            if (legacyComposite(signals) >= Agreement.STATISTICAL_ELEVATED) {
                legacyFlagged++;
            }
            if (CompositeAggregation.combine(signals) >= Agreement.STATISTICAL_ELEVATED) {
                flagged++;
            }
        }
        for (UUID accountId : ordinary) {
            List<SignalScore> signals = detection.assess(accountId, asOf).score().signals();
            if (legacyComposite(signals) >= Agreement.STATISTICAL_ELEVATED) {
                legacyFalsePositives++;
            }
            if (CompositeAggregation.combine(signals) >= Agreement.STATISTICAL_ELEVATED) {
                falsePositives++;
            }
        }

        System.out.println("=== AGGREGATION BEFORE/AFTER, identical signal scores ===");
        System.out.printf("  Phase 8 weighted mean : recall %d/%d, false positives %d/%d%n",
                legacyFlagged, anomalous.size(), legacyFalsePositives, ordinary.size());
        System.out.printf("  Phase 13 power mean   : recall %d/%d, false positives %d/%d%n",
                flagged, anomalous.size(), falsePositives, ordinary.size());
    }

    /**
     * Twenty-five regular payments each, interleaved so every account's history
     * occupies the same span and all of it stays inside the baseline window.
     */
    private void quietHistories(List<UUID> payers, UUID payee, long base) {
        for (int day = 0; day < 25; day++) {
            clock.advance(Duration.ofHours(6));
            for (int i = 0; i < payers.size(); i++) {
                pay(payers.get(i), payee, base + (long) i * 31 + day * 7L);
            }
        }
    }

    /** Phases 8-12's aggregation, for the comparison above only. */
    private static double legacyComposite(List<SignalScore> signals) {
        double applicableWeight = signals.stream()
                .filter(SignalScore::applicable)
                .mapToDouble(signal -> signal.signal().weight())
                .sum();
        double weighted = signals.stream()
                .filter(SignalScore::applicable)
                .mapToDouble(signal -> signal.signal().weight() * signal.score())
                .sum();
        return applicableWeight == 0 ? 0 : weighted / applicableWeight;
    }

    /**
     * Prints the benchmark figures for VALIDATION_REPORT.md.
     *
     * <p>A test that prints is unusual and deliberate: these numbers belong in
     * the report, and copying them out of a run is how Phases 3 and 7 recorded
     * their measured evidence too. Regenerating the report means re-running this
     * test rather than trusting a figure somebody typed.
     */
    private static void print(EvaluationReport report, int anomalous, int ordinary) {
        System.out.println("=== SYNTHETIC BENCHMARK (circular; see VALIDATION_REPORT.md) ===");
        System.out.printf("population: %d anomalous by construction, %d ordinary%n",
                anomalous, ordinary);
        System.out.println("warnings:");
        report.warnings().forEach(warning -> System.out.println("  - " + warning));

        printLayer("statistical composite", report.statistical());
        printLayer("isolation score", report.model());

        System.out.println("-- by agreement state --");
        report.agreementStates().forEach((state, result) ->
                System.out.printf("  %-17s n=%-3d anomalous share=%.2f  [%.2f, %.2f]%n",
                        state, result.labelled(), result.anomalousShare(),
                        result.interval().low(), result.interval().high()));
    }

    private static void printLayer(String name, EvaluationReport.LayerResult layer) {
        ConfusionMatrix confusion = layer.confusion();
        System.out.printf("-- %s, flagging at %.2f --%n", name, layer.threshold());
        System.out.printf("  tp=%.0f fp=%.0f tn=%.0f fn=%.0f  (%d accounts)%n",
                confusion.truePositives(), confusion.falsePositives(),
                confusion.trueNegatives(), confusion.falseNegatives(), confusion.labelled());
        System.out.printf("  precision=%.3f [%.2f, %.2f]   recall=%.3f [%.2f, %.2f]%n",
                layer.precision().point(), layer.precision().low(), layer.precision().high(),
                layer.recall().point(), layer.recall().low(), layer.recall().high());
        System.out.printf("  f1=%.3f  base rate=%.3f  lift=%.1f%n",
                confusion.f1(), confusion.baseRate(), confusion.lift());
        System.out.printf("  average precision=%.3f  (chance=%.3f)%n",
                layer.curve().averagePrecision(), layer.curve().chanceLevel());
        layer.curve().bestByF1().ifPresent(best ->
                System.out.printf("  best F1 at threshold %.3f: precision=%.3f recall=%.3f%n",
                        best.threshold(), best.precision(), best.recall()));
    }

    // --------------------------------------------------------------- helpers

    private long accountCount() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Long.class);
        return value == null ? 0L : value;
    }

    private double statisticalScoreAt(UUID accountId, Instant asOf) {
        return detection.assess(accountId, asOf).score().composite();
    }

    private UUID account() {
        return accounts.create("val-" + UUID.randomUUID(), "USD").getId();
    }

    /** Accounts with quiet, regular histories. None of them should be flagged. */
    private List<UUID> ordinaryPopulation(int count) {
        List<UUID> payers = new ArrayList<>(count);
        UUID payee = account();

        for (int i = 0; i < count; i++) {
            UUID payer = account();
            payers.add(payer);
            for (int p = 0; p < 3; p++) {
                clock.advance(Duration.ofMinutes(7));
                pay(payer, payee, 1_000 + (long) i * 37 + p * 11L);
            }
        }
        return payers;
    }

    /** An account the statistical layer will flag: a long quiet history, then one huge payment. */
    private UUID anomalousAccount() {
        UUID payer = account();
        UUID payee = account();

        for (int day = 0; day < 30; day++) {
            clock.advance(Duration.ofDays(1));
            pay(payer, payee, 3_000 + day * 7L);
        }
        clock.advance(Duration.ofMinutes(3));
        pay(payer, payee, 9_000_000);
        return payer;
    }

    /**
     * Through {@code PaymentService} rather than the controller, which returns
     * pre-serialized JSON so that an idempotent replay is byte-identical. The
     * money path is the same either way, and this one hands back the
     * transaction id a dispute needs to reference.
     */
    private UUID pay(UUID source, UUID destination, long amountMinor) {
        return payments.create(source, destination, amountMinor, "USD", null)
                .transaction()
                .id();
    }
}
