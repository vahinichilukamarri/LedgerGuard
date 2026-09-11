package com.ledgerguard.chaos;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;

/**
 * Substitutes the three things a scenario needs to control, and nothing else.
 *
 * <p>The rest of the context is the production application, unmodified: real
 * services, real repositories, real Flyway-migrated schema, real transaction
 * manager. That is deliberate — a chaos suite that runs against a specially
 * assembled test rig proves things about the rig.
 *
 * <h2>Why the DataSource is wrapped rather than replaced</h2>
 *
 * A {@code @Primary DataSource} bean would shoulder aside Spring Boot's
 * autoconfiguration and Testcontainers' {@code @ServiceConnection}, and the
 * scenarios would then be running against a connection pool configured by this
 * file instead of the one production uses. A {@link BeanPostProcessor} lets all
 * of that happen normally and wraps whatever it produced, so the only difference
 * from production is the ability to fail a nominated statement.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ChaosConfig {

    /** Where every scenario's clock starts, so timestamps are comparable across runs. */
    public static final Instant EPOCH = Instant.parse("2026-09-11T09:00:00Z");

    /**
     * Static, because a BeanPostProcessor has to be instantiated before the
     * beans it post-processes — including the DataSource, which Flyway needs
     * very early. A non-static factory method would make this container bean
     * depend on the enclosing configuration and register too late to catch it.
     */
    @Bean
    static BeanPostProcessor chaosDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof ChaosDataSource)) {
                    // Disarmed on creation. Flyway runs the migrations through
                    // this very wrapper at startup, and an armed fault would
                    // break the context instead of the scenario.
                    return new ChaosDataSource(dataSource);
                }
                return bean;
            }
        };
    }

    /**
     * The broker. {@code @Primary} so {@code OutboxPublisher} receives this
     * rather than the autoconfigured {@code KafkaTemplate}, which would try to
     * reach a broker that is not there.
     */
    @Bean
    @Primary
    ChaosKafkaTemplate chaosKafkaTemplate() {
        return new ChaosKafkaTemplate();
    }

    /** Replaces {@code ClockConfig.clock()} everywhere, including inside the services. */
    @Bean
    @Primary
    TickingClock tickingClock() {
        return new TickingClock(EPOCH);
    }

    @Bean
    LedgerInvariants ledgerInvariants(JdbcTemplate jdbc) {
        return new LedgerInvariants(jdbc);
    }

    /*
     * There is deliberately no ChaosDataSource @Bean here. Declaring one that
     * takes a DataSource parameter is a self-reference: the post-processed
     * DataSource *is* a ChaosDataSource, so the factory method would be asked to
     * inject the bean it is in the middle of creating. Scenarios get it by
     * casting the autowired DataSource instead; see ChaosScenario.
     */
}
