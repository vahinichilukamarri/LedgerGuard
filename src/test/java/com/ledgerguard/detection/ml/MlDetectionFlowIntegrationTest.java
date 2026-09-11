package com.ledgerguard.detection.ml;

import com.ledgerguard.accounts.AccountService;
import com.ledgerguard.chaos.TickingClock;
import com.ledgerguard.config.Money;
import com.ledgerguard.detection.DetectionService;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Training and scoring against a real ledger.
 *
 * <p>The unit tests prove the forest is correct on synthetic points and the
 * extractor is correct on hand-built activity. Neither proves the two meet
 * properly over real rows — that the features a model trains on are the features
 * it later scores against, that the population gate fires on a real population,
 * and that two trainings over one unchanged ledger produce the same numbers.
 *
 * <p>Time comes from Phase 7's {@link TickingClock} and is advanced explicitly,
 * so payment timestamps land where the scenario puts them and nothing depends on
 * how fast the machine is.
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
@Import(MlDetectionFlowIntegrationTest.FixedClockConfig.class)
class MlDetectionFlowIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = start();

    private static PostgreSQLContainer<?> start() {
        PostgreSQLContainer<?> container = LedgerPostgres.newContainer();
        container.start();
        return container;
    }

    static final Instant EPOCH = Instant.parse("2026-09-11T09:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        @Primary
        TickingClock mlClock() {
            return new TickingClock(EPOCH);
        }
    }

    private static final String TRUNCATE_ALL = """
            TRUNCATE TABLE reconciliation_incidents, reconciliation_runs, settlement_records,
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
    private PaymentController payments;
    @Autowired
    private DetectionService detection;
    @Autowired
    private MlDetectionService ml;

    @BeforeEach
    void knownStartingState() {
        clock.resetTo(EPOCH);
        jdbc.execute(TRUNCATE_ALL);
        ml.forget();
    }

    @Test
    @DisplayName("scoring before any training reports no model, rather than a zero")
    void noModelIsNotAZero() {
        UUID payer = account();
        UUID payee = account();
        clock.advance(Duration.ofMinutes(5));
        pay(payer, payee, 1_000);

        assertThat(ml.isTrained()).isFalse();
        assertThat(ml.metadata()).isEmpty();
        assertThat(ml.score(payer, clock.instant()))
                .as("empty means the question has not been asked of a model; "
                        + "a zero would be a claim about the account")
                .isEmpty();
    }

    /**
     * The gate, on a real population. A forest over a handful of accounts
     * isolates almost every one of them, so the refusal is the honest outcome.
     */
    @Test
    @DisplayName("training is refused on a population too small to learn anything from")
    void populationGate() {
        List<UUID> few = accountsWithHistory(6, 4);
        assertThat(few).hasSize(6);

        assertThatThrownBy(() -> ml.train(clock.instant()))
                .isInstanceOf(MlDetectionService.InsufficientTrainingDataException.class)
                .hasMessageContaining("too few")
                .hasMessageContaining(String.valueOf(ml.minimumTrainingAccounts()));

        assertThat(ml.isTrained())
                .as("a refused training must leave no model behind")
                .isFalse();
    }

    @Test
    @DisplayName("training on a sufficient population records exactly what produced the model")
    void trainingRecordsItsProvenance() {
        accountsWithHistory(ml.minimumTrainingAccounts() + 2, 3);

        ModelMetadata metadata = ml.train(clock.instant());

        // Every account is a training row, including the shared payee - the
        // model trains on the population, not on the payers the test happened
        // to name. Asserted against the table rather than against the test's own
        // arithmetic, which is what got this wrong the first time.
        long population = accountCount();
        assertThat(metadata.trainingSampleSize()).isEqualTo((int) population);
        assertThat(metadata.trainedAsOf()).isEqualTo(clock.instant());
        assertThat(metadata.featureNames())
                .as("stored scores stay interpretable only if the feature order travels with them")
                .containsExactly(FeatureVector.NAMES);
        assertThat(metadata.treeCount()).isPositive();
        assertThat(metadata.subSampleSize()).isEqualTo((int) population);
        assertThat(ml.isTrained()).isTrue();
    }

    /**
     * The determinism requirement, end to end rather than at the forest alone:
     * the same ledger and the same seed must give the same scores, through the
     * whole feature pipeline and a full retraining.
     */
    @Test
    @DisplayName("retraining an unchanged ledger reproduces every score exactly")
    void trainingIsDeterministic() {
        List<UUID> population = accountsWithHistory(ml.minimumTrainingAccounts() + 2, 3);
        Instant asOf = clock.instant();

        ml.train(asOf);
        List<Double> first = scoresFor(population, asOf);

        ml.forget();
        ml.train(asOf);
        List<Double> second = scoresFor(population, asOf);

        assertThat(second)
                .as("a model that varied between runs could not be reasoned about at all")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("the ledger the model trains on is the ledger it scores against")
    void featuresMatchBetweenTrainingAndScoring() {
        List<UUID> population = accountsWithHistory(ml.minimumTrainingAccounts() + 2, 3);
        Instant asOf = clock.instant();
        ml.train(asOf);

        for (UUID accountId : population) {
            DetectionService.ScoredAccount assessed = detection.assess(accountId, asOf);

            Optional<Double> viaActivity = ml.score(assessed);
            Optional<Double> viaId = ml.score(accountId, asOf);

            assertThat(viaActivity).isPresent();
            assertThat(viaId)
                    .as("the two scoring paths must agree, or training and serving have diverged")
                    .isEqualTo(viaActivity);
        }
    }

    /**
     * A behavioural sanity check, and no more than that. It shows the pipeline
     * carries an extreme account through to an elevated score; it shows nothing
     * about whether real fraud looks like this in this feature space.
     */
    @Test
    @DisplayName("an account behaving unlike the rest of the population scores above the median")
    void anomalousAccountScoresAboveTheMedian() {
        List<UUID> ordinary = accountsWithHistory(ml.minimumTrainingAccounts() + 4, 3);

        // One account unlike any of them: a burst of payments hundreds of times
        // the size the rest of the population ever sends.
        UUID oddPayer = account();
        UUID oddPayee = account();
        for (int i = 0; i < 8; i++) {
            clock.advance(Duration.ofSeconds(3));
            pay(oddPayer, oddPayee, 9_000_000);
        }

        Instant asOf = clock.instant();
        ml.train(asOf);

        double odd = ml.score(oddPayer, asOf).orElseThrow();
        List<Double> ordinaryScores = scoresFor(ordinary, asOf).stream().sorted().toList();
        double median = ordinaryScores.get(ordinaryScores.size() / 2);

        assertThat(odd)
                .as("sanity check only: this says the pipeline is not broken, "
                        + "not that the model is useful")
                .isGreaterThan(median);
    }

    @Test
    @DisplayName("the two layers are reported side by side, never blended")
    void scoresStaySeparate() {
        List<UUID> population = accountsWithHistory(ml.minimumTrainingAccounts() + 2, 3);
        Instant asOf = clock.instant();
        ml.train(asOf);

        UUID accountId = population.get(0);
        DetectionService.ScoredAccount assessed = detection.assess(accountId, asOf);
        double statistical = assessed.score().composite();
        double model = ml.score(assessed).orElseThrow();

        Agreement agreement = Agreement.of(statistical, model);

        assertThat(agreement).isNotNull();
        assertThat(statistical).isBetween(0.0, 1.0);
        assertThat(model)
                .as("both scores are on [0,1] so they can be read together, "
                        + "which is not the same as being averaged")
                .isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("a model trained at one instant keeps scoring against that snapshot")
    void modelIsTiedToItsTrainingSnapshot() {
        accountsWithHistory(ml.minimumTrainingAccounts() + 2, 3);
        Instant trainedAt = clock.instant();
        ModelMetadata metadata = ml.train(trainedAt);

        // The ledger moves on. The model does not.
        clock.advance(Duration.ofDays(2));
        UUID payer = account();
        UUID payee = account();
        pay(payer, payee, 5_000);

        assertThat(ml.metadata().orElseThrow().trainedAsOf())
                .as("a stale model must be visibly stale rather than quietly current")
                .isEqualTo(metadata.trainedAsOf())
                .isNotEqualTo(clock.instant());
    }

    // --------------------------------------------------------------- helpers

    private long accountCount() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Long.class);
        return value == null ? 0L : value;
    }

    private UUID account() {
        return accounts.create("ml-" + UUID.randomUUID(), "USD").getId();
    }

    /**
     * {@code count} accounts, each having sent {@code payments} modest amounts.
     *
     * <p>Deliberately light on history: these exist to make the population large
     * enough to train on, not to exercise the signals, which have their own
     * tests. Keeping it small keeps the suite quick.
     */
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
        payments.create("ml-" + UUID.randomUUID(),
                new CreatePaymentRequest(source, destination,
                        Money.toMajorUnits(amountMinor, "USD"), "USD", null));
    }

    private List<Double> scoresFor(List<UUID> accountIds, Instant asOf) {
        return accountIds.stream()
                .map(id -> ml.score(id, asOf).orElseThrow())
                .toList();
    }
}
