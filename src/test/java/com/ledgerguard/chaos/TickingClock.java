package com.ledgerguard.chaos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the scenario moves by hand.
 *
 * <h2>Why not {@code Thread.sleep}</h2>
 *
 * Reconciliation skips transactions younger than a grace window, so any scenario
 * about a missing settlement has to get past it. Sleeping for the real duration
 * makes the suite slower and, worse, makes it flaky: a loaded CI machine can
 * take longer to schedule the thread than the window it was waiting for, and the
 * test starts failing for reasons that have nothing to do with the ledger.
 *
 * <p>Advancing an injected clock instead makes the passage of time explicit and
 * instantaneous. {@code ledgerguard.reconciliation.grace-seconds} is read from
 * this clock, and so is every {@code created_at} the application writes, so the
 * two stay consistent however far the scenario jumps.
 *
 * <p>The application already injects {@code Clock} everywhere rather than
 * calling {@code Instant.now()}, which is what makes this possible without
 * touching production code — see {@code ClockConfig}.
 */
public class TickingClock extends Clock {

    private final ZoneId zone;
    private volatile Instant now;

    public TickingClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    private TickingClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    /** Move time forward. Never backwards: a ledger with a reversing clock is a different phase. */
    public void advance(Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("time only moves forward here, got: " + amount);
        }
        now = now.plus(amount);
    }

    public void advanceSeconds(long seconds) {
        advance(Duration.ofSeconds(seconds));
    }

    /** Back to a known instant, so each scenario starts from the same place. */
    public void resetTo(Instant instant) {
        this.now = instant;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return new TickingClock(now, other);
    }

    @Override
    public String toString() {
        return "TickingClock(" + now + ")";
    }
}
