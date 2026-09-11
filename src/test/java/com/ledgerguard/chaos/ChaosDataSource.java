package com.ledgerguard.chaos;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * A real DataSource that can be made to fail on a nominated statement.
 *
 * <h2>Why this wraps JDBC rather than mocking a repository</h2>
 *
 * A Mockito spy that throws from {@code save()} proves the service handles an
 * exception. It does not prove the <em>database</em> rolls anything back,
 * because no database was involved in the failure. The interesting question in a
 * payments ledger is the second one: when the connection dies half way through
 * writing a transaction and two of its postings, does Postgres leave anything
 * behind?
 *
 * <p>So the fault is injected at the lowest honest level — the JDBC statement —
 * and everything above it is the real thing: real Hibernate flush ordering, real
 * transaction boundaries, real rollback, real constraint enforcement. The
 * failure arrives exactly where a dropped connection would arrive.
 *
 * <h2>How it is installed</h2>
 *
 * Through a {@code BeanPostProcessor} rather than a {@code @Primary} bean, so
 * Spring Boot's autoconfiguration and Testcontainers' {@code @ServiceConnection}
 * still build the pooled DataSource exactly as production does; this only wraps
 * the result. See {@code ChaosConfig}.
 *
 * <p>It is disarmed by default, which matters: Flyway runs the migrations
 * through this same DataSource at startup, and an armed fault would break the
 * context rather than the scenario.
 *
 * <h2>Implementation note</h2>
 *
 * Connections and statements are wrapped with {@link Proxy} rather than by
 * implementing the JDBC interfaces by hand. Those interfaces carry upwards of
 * fifty methods each, of which this class cares about four, and hand-written
 * delegates for the rest would be pure noise with a real chance of a typo
 * changing behaviour.
 */
public class ChaosDataSource implements DataSource {

    /** The statement methods worth failing: everything that actually hits the server. */
    private static final java.util.Set<String> EXECUTING_METHODS =
            java.util.Set.of("execute", "executeUpdate", "executeQuery", "executeLargeUpdate", "executeBatch");

    private final DataSource delegate;

    /** Null when healthy. Volatile because scenarios arm it from the test thread. */
    private volatile Fault fault;

    public ChaosDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    /** An armed fault: which statements it matches, how many to let through, and what to throw. */
    private static final class Fault {
        private final Predicate<String> matches;
        private final int letThrough;
        private final java.util.function.Supplier<SQLException> failure;
        private final boolean repeating;
        private final AtomicInteger seen = new AtomicInteger();
        private final AtomicInteger fired = new AtomicInteger();

        Fault(Predicate<String> matches, int letThrough,
              java.util.function.Supplier<SQLException> failure, boolean repeating) {
            this.matches = matches;
            this.letThrough = letThrough;
            this.failure = failure;
            this.repeating = repeating;
        }
    }

    // ------------------------------------------------------------ arming

    /**
     * Fail the first matching statement, once, then behave normally.
     *
     * @param sqlMatches tested against the lower-cased SQL, so callers can write
     *                   {@code sql -> sql.contains("insert into postings")}
     *                   without caring how Hibernate cased it
     */
    public void failOnce(Predicate<String> sqlMatches, java.util.function.Supplier<SQLException> failure) {
        failAfter(sqlMatches, 0, failure);
    }

    /** Let {@code letThrough} matching statements succeed, then fail the next one, once. */
    public void failAfter(Predicate<String> sqlMatches, int letThrough,
                          java.util.function.Supplier<SQLException> failure) {
        this.fault = new Fault(sqlMatches, letThrough, failure, false);
    }

    /** Fail every matching statement until disarmed: a connection that stays broken. */
    public void failAlways(Predicate<String> sqlMatches, java.util.function.Supplier<SQLException> failure) {
        this.fault = new Fault(sqlMatches, 0, failure, true);
    }

    /** Disarm. Statements behave normally again. */
    public void healthy() {
        this.fault = null;
    }

    /** How many times the armed fault actually fired. Zero means the scenario never reached it. */
    public int firedCount() {
        Fault armed = fault;
        return armed == null ? 0 : armed.fired.get();
    }

    // ------------------------------------------------------- the failures

    /** What a dropped TCP connection looks like to the driver: SQLState 08006. */
    public static java.util.function.Supplier<SQLException> connectionDropped() {
        return () -> new SQLException(
                "chaos: connection to server was lost", "08006");
    }

    /** A statement that ran too long and was cancelled. */
    public static java.util.function.Supplier<SQLException> statementTimeout() {
        return () -> new SQLTimeoutException(
                "chaos: canceling statement due to statement timeout", "57014");
    }

    // -------------------------------------------------------- interception

    private void checkBefore(String sql) throws SQLException {
        Fault armed = fault;
        if (armed == null || sql == null) {
            return;
        }
        if (!armed.matches.test(sql.toLowerCase(Locale.ROOT))) {
            return;
        }
        if (armed.seen.getAndIncrement() < armed.letThrough) {
            return;
        }
        if (!armed.repeating && armed.fired.get() > 0) {
            return;
        }
        armed.fired.incrementAndGet();
        throw armed.failure.get();
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ConnectionHandler(connection));
    }

    private final class ConnectionHandler implements InvocationHandler {
        private final Connection target;

        private ConnectionHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = call(target, method, args);

            // Remember the SQL at prepare time: PreparedStatement.execute() takes
            // no arguments, so this is the only chance to know what it will run.
            if (result instanceof PreparedStatement prepared) {
                String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : null;
                return wrapStatement(prepared, PreparedStatement.class, sql);
            }
            if (result instanceof Statement statement) {
                return wrapStatement(statement, Statement.class, null);
            }
            return result;
        }
    }

    private Object wrapStatement(Statement target, Class<?> iface, String preparedSql) {
        return Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{iface},
                (proxy, method, args) -> {
                    if (EXECUTING_METHODS.contains(method.getName())) {
                        // A plain Statement carries its SQL as the first argument;
                        // a PreparedStatement carried it at prepare time.
                        String sql = preparedSql;
                        if (sql == null && args != null && args.length > 0 && args[0] instanceof String s) {
                            sql = s;
                        }
                        checkBefore(sql);
                    }
                    return call(target, method, args);
                });
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            // Unwrap, so the caller sees the driver's exception and not a
            // reflection wrapper it has no handler for.
            throw e.getCause();
        }
    }

    // ----------------------------------------------------- DataSource API

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(delegate.getConnection(username, password));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() {
        try {
            return delegate.getParentLogger();
        } catch (java.sql.SQLFeatureNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return iface.isInstance(this) ? iface.cast(this) : delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
