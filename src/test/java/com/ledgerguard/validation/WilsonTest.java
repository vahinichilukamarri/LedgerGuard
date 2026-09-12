package com.ledgerguard.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The interval that stops a point estimate being quoted as a fact.
 *
 * <p>The test that matters is {@link #perfectScoreIsNotCertainty()}. The
 * textbook interval reports zero width at ten successes out of ten, which would
 * let this system publish "precision 1.00" from ten accounts with no visible
 * hedge at all.
 */
class WilsonTest {

    @Test
    @DisplayName("nothing measured yields the whole interval, not zero")
    void nothingMeasured() {
        Wilson.Interval interval = Wilson.interval(0, 0);

        assertThat(interval.point()).isNaN();
        assertThat(interval.low()).isZero();
        assertThat(interval.high()).isEqualTo(1.0);
        assertThat(interval.isInformative()).isFalse();
    }

    @Test
    @DisplayName("ten out of ten is not certainty")
    void perfectScoreIsNotCertainty() {
        Wilson.Interval interval = Wilson.interval(10, 10);

        assertThat(interval.point()).isEqualTo(1.0);
        assertThat(interval.width())
                .as("the normal approximation reports zero here, which would publish "
                        + "certainty from ten accounts")
                .isGreaterThan(0.2);
        assertThat(interval.low()).isLessThan(1.0);
        // The upper bound is exactly 1 in real arithmetic and one ulp below it
        // in doubles, which is a fact about floating point rather than about
        // the statistic; snapping it in the implementation would mean rounding
        // a published figure to make a test read nicely.
        assertThat(interval.high()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("zero out of ten is not impossibility")
    void zeroIsNotImpossible() {
        Wilson.Interval interval = Wilson.interval(0, 10);

        assertThat(interval.point()).isZero();
        assertThat(interval.low()).isZero();
        assertThat(interval.high()).isGreaterThan(0.2);
    }

    @Test
    @DisplayName("the interval stays inside [0,1] however extreme the count")
    void staysInsideTheUnitInterval() {
        for (long trials : new long[]{1, 2, 5, 40, 1000}) {
            for (long successes : new long[]{0, 1, trials / 2, trials}) {
                Wilson.Interval interval = Wilson.interval(Math.min(successes, trials), trials);
                assertThat(interval.low()).isBetween(0.0, 1.0);
                assertThat(interval.high()).isBetween(0.0, 1.0);
                assertThat(interval.low()).isLessThanOrEqualTo(interval.high());
            }
        }
    }

    @Test
    @DisplayName("more evidence narrows it")
    void moreEvidenceNarrows() {
        double small = Wilson.interval(8, 10).width();
        double medium = Wilson.interval(80, 100).width();
        double large = Wilson.interval(800, 1000).width();

        assertThat(small).isGreaterThan(medium);
        assertThat(medium).isGreaterThan(large);
    }

    @Test
    @DisplayName("the interval brackets the observed proportion")
    void bracketsThePointEstimate() {
        Wilson.Interval interval = Wilson.interval(37, 100);

        assertThat(interval.point()).isCloseTo(0.37, within(1e-12));
        assertThat(interval.low()).isLessThan(0.37);
        assertThat(interval.high()).isGreaterThan(0.37);
    }

    @Test
    @DisplayName("a small sample is reported as uninformative")
    void smallSamplesAreNotInformative() {
        assertThat(Wilson.interval(9, 12).isInformative())
                .as("twelve accounts is an anecdote with a decimal point")
                .isFalse();
        assertThat(Wilson.interval(700, 1000).isInformative()).isTrue();
    }
}
