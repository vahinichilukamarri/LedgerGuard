package com.ledgerguard.reconciliation;

/**
 * How much attention a discrepancy deserves.
 *
 * <p>The scheme is two-part and deliberately simple: <b>the discrepancy type
 * sets a floor, the amount escalates from there, and nothing ever escalates
 * downwards.</b>
 *
 * <p>Type sets the floor because the <em>kind</em> of disagreement tells you how
 * uncontrolled the money is. A duplicate settlement means a control failed and
 * somebody was probably paid or charged twice; that is worth waking up for at
 * any size. Amount escalates because exposure scales with money: a one-cent
 * mismatch and a ten-thousand-dollar mismatch are not the same alert, even
 * though they are the same bug shape.
 */
public enum Severity {

    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /** Escalate to at least HIGH once this much money is in question. */
    public static final long HIGH_THRESHOLD_MINOR = 10_000L;      // $100.00

    /** Escalate to CRITICAL once this much is. */
    public static final long CRITICAL_THRESHOLD_MINOR = 100_000L; // $1,000.00

    public Severity atLeast(Severity floor) {
        return this.compareTo(floor) >= 0 ? this : floor;
    }

    /**
     * Raise {@code base} according to how much money the discrepancy puts in
     * question. Never lowers it.
     *
     * @param amountAtRiskMinor absolute exposure in minor units
     */
    public static Severity escalateFor(Severity base, long amountAtRiskMinor) {
        long exposure = Math.abs(amountAtRiskMinor);
        if (exposure >= CRITICAL_THRESHOLD_MINOR) {
            return CRITICAL;
        }
        if (exposure >= HIGH_THRESHOLD_MINOR) {
            return HIGH.atLeast(base);
        }
        return base;
    }
}
