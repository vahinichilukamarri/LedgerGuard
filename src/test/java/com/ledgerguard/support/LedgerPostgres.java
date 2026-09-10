package com.ledgerguard.support;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The one place the test PostgreSQL image and its settings are configured.
 *
 * <p>Two access patterns, deliberately different:
 *
 * <ul>
 *   <li>{@link #newContainer()} hands back a fresh, unstarted container for a
 *       JUnit Jupiter test class to manage with {@code @Container}. Each such
 *       class keeps its own database. That isolation is load-bearing for at
 *       least one existing suite: {@code ReconciliationFlowIntegrationTest}
 *       reconciles the <em>entire</em> ledger, so rows written by another test
 *       class would show up in its results as discrepancies.</li>
 *   <li>{@link #shared()} hands back one already-started container reused by
 *       every property test. jqwik runs thousands of tries; paying container
 *       startup per property class would dominate the run. The property suite
 *       can share safely because every property either scopes its assertions to
 *       accounts it created in that try, or asserts something that holds over
 *       any ledger (the whole-ledger sum is zero no matter who else wrote to
 *       it).</li>
 * </ul>
 *
 * <p>The shared container is never stopped. Testcontainers' Ryuk sidecar reaps
 * it when the JVM exits, which is the documented singleton-container pattern.
 */
public final class LedgerPostgres {

    private static final String IMAGE = "postgres:16-alpine";

    private LedgerPostgres() {
    }

    /** A fresh container, not yet started. The caller owns its lifecycle. */
    public static PostgreSQLContainer<?> newContainer() {
        return new PostgreSQLContainer<>(IMAGE);
    }

    /** The started, process-wide container used by the property suite. */
    public static PostgreSQLContainer<?> shared() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        private static final PostgreSQLContainer<?> INSTANCE = start();

        private static PostgreSQLContainer<?> start() {
            PostgreSQLContainer<?> container = newContainer();
            container.start();
            return container;
        }
    }
}
