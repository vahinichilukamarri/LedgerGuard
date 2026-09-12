package com.ledgerguard.detection.explain;

import com.ledgerguard.accounts.AccountService;
import com.ledgerguard.chaos.TickingClock;
import com.ledgerguard.config.Money;
import com.ledgerguard.detection.DetectionService;
import com.ledgerguard.detection.dto.AccountAssessmentResponse;
import com.ledgerguard.detection.dto.AccountExplanationResponse;
import com.ledgerguard.detection.explain.llm.NarrativeService;
import com.ledgerguard.detection.explain.llm.NarrativeSource;
import com.ledgerguard.detection.ml.Agreement;
import com.ledgerguard.detection.ml.FeatureAttribution;
import com.ledgerguard.detection.ml.FeatureVector;
import com.ledgerguard.detection.ml.MlDetectionService;
import com.ledgerguard.payments.PaymentController;
import com.ledgerguard.payments.dto.CreatePaymentRequest;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explaining a real ledger, through the whole pipeline.
 *
 * <p>The unit tests establish that attribution names the right feature on a
 * synthetic population and that the narratives differ across the four agreement
 * states. What they cannot establish is that the numbers in an explanation are
 * the same numbers the endpoints publish — that the composite in the breakdown
 * is the composite the scorer produced, and that the isolation score in the
 * attribution is the one the model served. An explanation that quietly
 * disagreed with the score beside it would pass every unit test here.
 *
 * <p>Time comes from Phase 7's {@link TickingClock} and is advanced explicitly,
 * so nothing depends on how fast the machine is.
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
@Import(ExplanationFlowIntegrationTest.FixedClockConfig.class)
class ExplanationFlowIntegrationTest {

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
        TickingClock explanationClock() {
            return new TickingClock(EPOCH);
        }
    }

    private static final String TRUNCATE_ALL = """
            TRUNCATE TABLE reconciliation_incidents, reconciliation_runs, settlement_records,
                           processed_events, outbox_events, idempotency_keys,
                           reversals, refunds, postings, payments, transactions, accounts
            RESTART IDENTITY CASCADE
            """;

    /** The features that speak to how much money moved, on any of the three framings. */
    private static final List<String> AMOUNT_AXES = List.of(
            "amountModifiedZ", "log10LargestRecentAmount", "historicalAmountPercentile");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TickingClock clock;
    @Autowired
    private AccountService accounts;
    @Autowired
    private PaymentController payments;
    @Autowired
    private DetectionService detection;
    @Autowired
    private MlDetectionService ml;
    @Autowired
    private ExplanationService explanations;
    @Autowired
    private NarrativeService narratives;

    @BeforeEach
    void knownStartingState() {
        clock.resetTo(EPOCH);
        jdbc.execute(TRUNCATE_ALL);
        ml.forget();
    }

    @Test
    @DisplayName("with no model, the statistical half is explained and the other half says why not")
    void explainsWithoutAModel() {
        UUID payer = account();
        UUID payee = account();
        clock.advance(Duration.ofMinutes(5));
        pay(payer, payee, 1_000);

        AccountExplanation explanation = explanations.explain(payer, clock.instant());

        assertThat(explanation.hasModel()).isFalse();
        assertThat(explanation.ml()).isNull();
        assertThat(explanation.reconciliation())
                .as("there is nothing to reconcile a statistical score with")
                .isNull();
        assertThat(explanation.mlUnavailableReason()).isEqualTo(ExplanationService.NO_MODEL);
        assertThat(explanation.statistical().contributions()).hasSize(5);
        assertThat(explanation.summary()).contains("No model has been trained");
        assertThat(explanation.caveats()).isNotEmpty();
    }

    @Test
    @DisplayName("the explained numbers are the numbers the detection layer published")
    void explanationAgreesWithTheScores() {
        List<UUID> population = accountsWithHistory(ml.minimumTrainingAccounts() + 2, 3);
        Instant asOf = clock.instant();
        ml.train(asOf);

        for (UUID accountId : population) {
            DetectionService.ScoredAccount assessed = detection.assess(accountId, asOf);
            AccountExplanation explanation = explanations.explain(accountId, asOf);

            assertThat(explanation.statistical().composite())
                    .isEqualTo(assessed.score().composite());
            assertThat(explanation.ml().score())
                    .as("an explanation that disagreed with the score beside it would be worse "
                            + "than no explanation")
                    .isEqualTo(ml.score(assessed).orElseThrow());
            assertThat(explanation.reconciliation().agreement())
                    .isEqualTo(Agreement.of(assessed.score().composite(), explanation.ml().score()));
        }
    }

    @Test
    @DisplayName("the breakdown adds up to the composite on real ledger data")
    void contributionsSumToTheCompositeOnRealData() {
        List<UUID> population = accountsWithHistory(ml.minimumTrainingAccounts() + 2, 3);
        Instant asOf = clock.instant();

        for (UUID accountId : population) {
            StatisticalExplanation statistical =
                    explanations.explain(accountId, asOf).statistical();

            assertThat(statistical.contributions().stream()
                    .mapToDouble(SignalContribution::contribution)
                    .sum())
                    .isCloseTo(statistical.composite(), org.assertj.core.data.Offset.offset(1e-12));
        }
    }

    /**
     * The phase brief's requirement on real data: an account that differs from
     * the population in one respect must be explained in that respect.
     *
     * <p>It sends the same number of payments as everyone else, at the same
     * cadence, and differs only in size. Any of the three amount framings is an
     * acceptable answer — which of them the forest reaches for is a property of
     * the population, not something worth pinning — but a driver from some
     * unrelated axis would mean the attribution is not tracking the ledger.
     */
    @Test
    @DisplayName("an account unusual only in amount is explained on an amount axis")
    void amountAnomalyExplainsAsAmount() {
        accountsWithHistory(ml.minimumTrainingAccounts() + 4, 3);

        UUID bigPayer = account();
        UUID payee = account();
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMinutes(7));
            pay(bigPayer, payee, 4_000_000 + i * 1_000L);
        }

        Instant asOf = clock.instant();
        ml.train(asOf);

        AccountExplanation explanation = explanations.explain(bigPayer, asOf);
        List<FeatureAttribution> drivers = explanation.ml().drivers();

        assertThat(drivers)
                .as("something must have isolated an account unlike every other one")
                .isNotEmpty();
        assertThat(drivers.get(0).feature())
                .as("explained on the axis it actually differs on, not vaguely")
                .isIn(AMOUNT_AXES);
        assertThat(explanation.summary()).contains(drivers.get(0).feature());
    }

    @Test
    @DisplayName("every feature is reported, in a stable order, with population context")
    void attributionIsCompleteAndOrdered() {
        List<UUID> population = accountsWithHistory(ml.minimumTrainingAccounts() + 2, 3);
        Instant asOf = clock.instant();
        ml.train(asOf);

        List<FeatureAttribution> attributions =
                explanations.explain(population.get(0), asOf).ml().attributions();

        assertThat(attributions).hasSize(FeatureVector.dimension());
        assertThat(attributions).extracting(FeatureAttribution::feature)
                .containsExactlyInAnyOrder(FeatureVector.NAMES);
        assertThat(attributions).isSortedAccordingTo(
                java.util.Comparator.comparingDouble(FeatureAttribution::excessBits).reversed());
        assertThat(attributions).allSatisfy(attribution ->
                assertThat(attribution.percentile()).isBetween(0.0, 1.0));
    }

    @Test
    @DisplayName("explaining the same ledger twice gives the same explanation")
    void deterministic() {
        List<UUID> population = accountsWithHistory(ml.minimumTrainingAccounts() + 2, 3);
        Instant asOf = clock.instant();
        ml.train(asOf);

        AccountExplanation first = explanations.explain(population.get(0), asOf);
        AccountExplanation second = explanations.explain(population.get(0), asOf);

        assertThat(second).isEqualTo(first);
        assertThat(second.summary()).isEqualTo(first.summary());
    }

    @Test
    @DisplayName("the assessment response carries a digest, and points at the full explanation")
    void digestOnTheAssessmentResponse() {
        List<UUID> population = accountsWithHistory(ml.minimumTrainingAccounts() + 2, 3);
        Instant asOf = clock.instant();
        ml.train(asOf);
        UUID accountId = population.get(0);

        AccountAssessmentResponse response = AccountAssessmentResponse.of(
                explanations.explain(accountId, asOf), ExplanationService.NO_MODEL);

        assertThat(response.signals()).hasSize(5);
        assertThat(response.explanation().summary()).isNotBlank();
        assertThat(response.explanation().agreement()).isNotNull();
        assertThat(response.explanation().detail())
                .isEqualTo("/detection/accounts/%s/explanation".formatted(accountId));
        assertThat(response.explanation().summary())
                .as("the qualification cannot wait for a second request")
                .contains("Neither score is validated");
    }

    @Test
    @DisplayName("the detail response carries everything the digest left out")
    void detailResponseIsComplete() {
        List<UUID> population = accountsWithHistory(ml.minimumTrainingAccounts() + 2, 3);
        Instant asOf = clock.instant();
        ml.train(asOf);

        AccountExplanationResponse response = AccountExplanationResponse.of(
                explanations.explain(population.get(0), asOf));

        assertThat(response.statistical().contributions()).hasSize(5);
        assertThat(response.ml().attributions()).hasSize(FeatureVector.dimension());
        assertThat(response.ml().model().seed()).isEqualTo(ml.metadata().orElseThrow().seed());
        assertThat(response.reconciliation().narrative()).isNotBlank();
        assertThat(response.caveats()).isNotEmpty();
        assertThat(response.mlUnavailableReason()).isNull();
    }

    /**
     * Phase 11 wiring, in the configuration everyone actually runs: no API key,
     * so the model is never called and the deterministic narrative is served.
     *
     * <p>This also checks that the LLM beans construct at all. They are built
     * whether or not a key exists, precisely so that the path every test and
     * every developer checkout exercises is the same object graph production
     * uses, with one step that declines to run.
     */
    @Test
    @DisplayName("with no API key configured the endpoint serves the template, marked as such")
    void narrativeFallsBackToTheTemplateWithoutAKey() {
        List<UUID> population = accountsWithHistory(ml.minimumTrainingAccounts() + 2, 3);
        Instant asOf = clock.instant();
        ml.train(asOf);

        AccountExplanation explanation = explanations.explain(population.get(0), asOf);
        AccountExplanationResponse response = AccountExplanationResponse.of(
                explanation, narratives.narrate(explanation));

        assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
        assertThat(response.summary())
                .as("the reviewer gets the complete Phase 10 narrative, not a stub")
                .isEqualTo(explanation.summary());
        assertThat(narratives.stats().attempted())
                .as("an unconfigured deployment is not a failed attempt")
                .isZero();
    }

    @Test
    @DisplayName("the explicit template request serves the deterministic narrative too")
    void templateCanBeAskedForByName() {
        List<UUID> population = accountsWithHistory(ml.minimumTrainingAccounts() + 2, 3);
        Instant asOf = clock.instant();

        AccountExplanation explanation = explanations.explain(population.get(0), asOf);

        assertThat(narratives.narrate(explanation, true).source())
                .isEqualTo(NarrativeSource.TEMPLATE);
    }

    // --------------------------------------------------------------- helpers

    private UUID account() {
        return accounts.create("explain-" + UUID.randomUUID(), "USD").getId();
    }

    private List<UUID> accountsWithHistory(int count, int paymentsEach) {
        List<UUID> payers = new ArrayList<>(count);
        UUID payee = account();

        for (int i = 0; i < count; i++) {
            UUID payer = account();
            payers.add(payer);
            for (int p = 0; p < paymentsEach; p++) {
                clock.advance(Duration.ofMinutes(7));
                pay(payer, payee, 1_000 + (long) i * 37 + p * 11L);
            }
        }
        return payers;
    }

    private void pay(UUID source, UUID destination, long amountMinor) {
        payments.create("explain-" + UUID.randomUUID(),
                new CreatePaymentRequest(source, destination,
                        Money.toMajorUnits(amountMinor, "USD"), "USD", null));
    }
}
