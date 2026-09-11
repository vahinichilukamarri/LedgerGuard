package com.ledgerguard.detection.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The discrete tails, checked against values worked out by hand rather than
 * against whatever the implementation happens to return.
 */
class DiscreteTailsTest {

    @Nested
    @DisplayName("Poisson upper tail")
    class Poisson {

        /**
         * The worked example from {@code VelocitySignal}: three payments from an
         * account averaging 0.2 an hour. The naive z-score calls this 6.3 and
         * therefore extraordinary; the truth is just under the flagging line,
         * and the difference is the reason this class exists.
         */
        @Test
        @DisplayName("three events against an expected 0.2 is unusual, not extraordinary")
        void smallLambdaIsNotExtraordinary() {
            double p = DiscreteTails.poissonUpperTail(3, 0.2);

            assertThat(p).isCloseTo(0.001148, within(1e-6));
            assertThat(DiscreteTails.surprisal(p))
                    .as("2.94 sits below the flagging threshold of 3, where the naive "
                            + "z-score of 6.3 would have raised an alert")
                    .isCloseTo(2.940, within(0.005));
        }

        @Test
        @DisplayName("an event count at or below zero is certain")
        void zeroOrFewerIsCertain() {
            assertThat(DiscreteTails.poissonUpperTail(0, 5.0)).isEqualTo(1.0);
            assertThat(DiscreteTails.poissonUpperTail(-3, 5.0)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("P(X >= 1) is the complement of nothing happening")
        void oneOrMore() {
            assertThat(DiscreteTails.poissonUpperTail(1, 2.0))
                    .isCloseTo(1 - Math.exp(-2.0), within(1e-12));
        }

        @Test
        @DisplayName("a burst against a near-zero rate is extreme but stays finite")
        void extremeButFinite() {
            double p = DiscreteTails.poissonUpperTail(20, 1e-6);

            assertThat(p).isGreaterThan(0.0);
            assertThat(DiscreteTails.surprisal(p)).isFinite().isGreaterThan(50);
        }

        @Test
        @DisplayName("a rate of zero cannot produce an event, and says so without dividing by it")
        void zeroRate() {
            assertThat(DiscreteTails.poissonUpperTail(1, 0.0)).isPositive();
            assertThat(DiscreteTails.surprisal(DiscreteTails.poissonUpperTail(1, 0.0))).isFinite();
        }

        /**
         * Above the switchover the iterative sum gives way to a normal
         * approximation. The two must agree across the join, or a signal would
         * jump discontinuously as an account got busier.
         */
        @Test
        @DisplayName("the large-lambda approximation agrees with the exact sum at the boundary")
        void approximationAgreesAtTheBoundary() {
            double exact = DiscreteTails.poissonUpperTail(520, 499.0);
            double approximate = DiscreteTails.poissonUpperTail(520, 501.0);

            assertThat(DiscreteTails.surprisal(approximate))
                    .as("the switch to the normal approximation must not be visible as a jump")
                    .isCloseTo(DiscreteTails.surprisal(exact), within(0.15));
        }
    }

    @Nested
    @DisplayName("binomial upper tail")
    class Binomial {

        @Test
        @DisplayName("five of ten against a ten percent rate matches the hand calculation")
        void knownValue() {
            assertThat(DiscreteTails.binomialUpperTail(5, 10, 0.1))
                    .isCloseTo(0.0016349, within(1e-6));
        }

        @Test
        @DisplayName("observing the entire sample is exactly p to the n")
        void allTrials() {
            assertThat(DiscreteTails.binomialUpperTail(4, 4, 0.5))
                    .isCloseTo(Math.pow(0.5, 4), within(1e-12));
        }

        @Test
        @DisplayName("more successes than trials is impossible; none is certain")
        void degenerateCounts() {
            assertThat(DiscreteTails.binomialUpperTail(11, 10, 0.5)).isZero();
            assertThat(DiscreteTails.binomialUpperTail(0, 10, 0.5)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("the whole distribution sums to one")
        void totalProbability() {
            assertThat(DiscreteTails.binomialUpperTail(1, 30, 0.2)
                    + Math.pow(0.8, 30))
                    .isCloseTo(1.0, within(1e-9));
        }

        @Test
        @DisplayName("a rate at either extreme does not produce a NaN")
        void extremeRates() {
            assertThat(DiscreteTails.binomialUpperTail(3, 10, 0.0)).isPositive();
            assertThat(DiscreteTails.binomialUpperTail(3, 10, 1.0)).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("surprisal and smoothing")
    class Scales {

        @Test
        @DisplayName("surprisal turns one in a thousand into three")
        void surprisalScale() {
            assertThat(DiscreteTails.surprisal(0.001)).isCloseTo(3.0, within(1e-12));
            assertThat(DiscreteTails.surprisal(1.0)).isZero();
        }

        @Test
        @DisplayName("surprisal stays finite even at zero probability")
        void surprisalIsBounded() {
            assertThat(DiscreteTails.surprisal(0.0)).isFinite().isGreaterThan(200);
        }

        /**
         * Without smoothing, a population that has never produced the event
         * makes the first one infinitely improbable and the signal saturates on
         * a single observation.
         */
        @Test
        @DisplayName("a never-yet-seen event is rare, not impossible")
        void laplaceSmoothing() {
            double rate = DiscreteTails.smoothedRate(0, 1000);

            assertThat(rate).isPositive().isLessThan(0.002);
            assertThat(DiscreteTails.binomialUpperTail(1, 10, rate))
                    .as("one occurrence must be surprising, not impossible")
                    .isPositive();
        }

        @Test
        @DisplayName("smoothing barely moves a well-evidenced rate")
        void smoothingIsNegligibleWithData() {
            assertThat(DiscreteTails.smoothedRate(500, 1000)).isCloseTo(0.5, within(0.002));
        }
    }
}
