package com.ledgerguard.validation;

import com.ledgerguard.detection.CompositeAggregation;
import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.SignalScore;
import com.ledgerguard.detection.ml.Agreement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The composite ceiling: characterised in Phase 12, closed in Phase 13.
 *
 * <h2>What was wrong</h2>
 *
 * Phases 8 to 12 combined the signals with a weighted arithmetic mean
 * renormalised over the applicable ones. Enumerating every applicable set
 * against every saturating subset showed a cliff rather than a ramp:
 *
 * <pre>
 *   applicable   min saturated signals to flag   one signal enough?
 *       1                    1                         yes
 *       2                    1                         yes
 *       3                    2                         NO
 *       4                    2                         NO
 *       5                    3                         NO
 * </pre>
 *
 * The operational property was not "a fully-measured account is hard to flag".
 * It was that a single signal's ability to flag an account <b>vanished the
 * moment a third signal became measurable</b>, and no extremity recovered it —
 * so accruing history could only ever lower an account's composite, and
 * thin-history accounts were easier to flag than fully-measured ones.
 *
 * <h2>What changed</h2>
 *
 * {@link CompositeAggregation} is now a weighted power mean of degree three,
 * unrenormalised. These tests call that class rather than re-deriving the
 * arithmetic, which is the other half of the Phase 12 lesson: the original
 * characterisation computed its own copy of the formula, and went on passing
 * after the formula underneath it changed.
 *
 * <h2>What this file guards</h2>
 *
 * <ol>
 *   <li>the ceiling is closed — one saturated signal flags any account;</li>
 *   <li>the inversion is gone — the bar is identical whatever is applicable;</li>
 *   <li>thin-history accounts did not become trivially easy as a side effect;</li>
 *   <li>noise still does not flag.</li>
 * </ol>
 */
class CompositeCeilingTest {

    private static final double TOTAL_WEIGHT =
            Arrays.stream(Signal.values()).mapToDouble(Signal::weight).sum();

    private static final double THRESHOLD = Agreement.STATISTICAL_ELEVATED;

    // ----------------------------------------------------- the ceiling, closed

    /**
     * The property the whole phase exists to deliver, over all 31 applicable
     * sets and every signal in each.
     */
    @Test
    @DisplayName("one saturated signal flags every account, whatever else is applicable")
    void theCeilingIsClosed() {
        for (Set<Signal> applicable : nonEmptySubsets()) {
            for (Signal lone : applicable) {
                assertThat(composite(applicable, EnumSet.of(lone)))
                        .as("%s saturated, with %d applicable (%s)",
                                lone.wireName(), applicable.size(), wireNames(applicable))
                        .isGreaterThanOrEqualTo(THRESHOLD);
            }
        }
    }

    @Test
    @DisplayName("the minimum saturating count is one, for every applicable set there is")
    void exhaustiveCharacterisation() {
        Map<Integer, Set<Integer>> minimumByApplicableCount = new TreeMap<>();

        for (Set<Signal> applicable : nonEmptySubsets()) {
            minimumByApplicableCount
                    .computeIfAbsent(applicable.size(), key -> new TreeSet<>())
                    .add(minimumSaturatingToFlag(applicable));
        }

        for (int applicableCount = 1; applicableCount <= 5; applicableCount++) {
            assertThat(minimumByApplicableCount.get(applicableCount))
                    .as("%d applicable", applicableCount)
                    .containsExactly(1);
        }
    }

    /**
     * The inversion Phase 12 objected to, gone rather than relocated.
     *
     * <p>Closing the ceiling was the easy half and every candidate did it. This
     * is the half that decided the design: the bar a lone signal must clear is
     * now a property of that signal's weight alone, and owes nothing to how many
     * of its neighbours happened to have enough history to speak.
     */
    @ParameterizedTest
    @EnumSource(Signal.class)
    @DisplayName("a signal's solo bar is the same whatever else is applicable")
    void theBarDoesNotMoveWithMeasurability(Signal signal) {
        double expected = CompositeAggregation.soloScoreToElevate(signal.weight(), THRESHOLD);

        for (Set<Signal> applicable : nonEmptySubsets()) {
            if (!applicable.contains(signal)) {
                continue;
            }
            assertThat(soloScoreToFlag(applicable, signal))
                    .as("%s among %d applicable", signal.wireName(), applicable.size())
                    .isCloseTo(expected, within(1e-6));
        }
    }

    /**
     * The same statement from the other side: adding a measurable-but-quiet
     * signal used to strictly lower the composite. It now changes nothing.
     */
    @Test
    @DisplayName("a signal becoming measurable no longer dilutes the ones that are shouting")
    void moreEvidenceNoLongerDilutes() {
        Set<Signal> pair = EnumSet.of(Signal.AMOUNT_OUTLIER, Signal.VELOCITY);
        Set<Signal> withAThird = EnumSet.of(
                Signal.AMOUNT_OUTLIER, Signal.VELOCITY, Signal.BURST);

        double before = composite(pair, EnumSet.of(Signal.AMOUNT_OUTLIER));
        double after = composite(withAThird, EnumSet.of(Signal.AMOUNT_OUTLIER));

        assertThat(after)
                .as("the account did nothing differently; a third signal merely learned to speak")
                .isEqualTo(before);
        assertThat(after).isGreaterThanOrEqualTo(THRESHOLD);
    }

    // ------------------------------------------ no regression on thin history

    /**
     * Phase 8's design goal, and the thing a careless fix breaks.
     *
     * <p>Making the fully-measured case possible must not be paid for by making
     * the thin case trivial. Stating this honestly took a failing test first:
     * the claim "no thin configuration got easier" is false. One did —
     * {@code amount_outlier} alongside {@code velocity} asked 0.900 of a lone
     * signal and now asks 0.794.
     *
     * <p>The true and stronger property is about the floor. Under the legacy
     * function the <em>easiest</em> way to flag anything was a single applicable
     * signal at 0.500; an account with almost no history was the cheapest
     * account in the system to flag. That floor is now 0.794 for every
     * configuration there is. Thin-history accounts as a class became
     * substantially <b>harder</b> to flag, and the one pair that moved the other
     * way moved to a bar far above the old floor.
     */
    @Test
    @DisplayName("the cheapest way to flag any account got harder, not easier")
    void thinHistoryDidNotGetEasier() {
        double legacyFloor = 1.0;
        double floor = 1.0;

        for (Set<Signal> applicable : nonEmptySubsets()) {
            for (Signal lone : applicable) {
                legacyFloor = Math.min(legacyFloor, legacySoloScoreToFlag(applicable, lone));
                floor = Math.min(floor, soloScoreToFlag(applicable, lone));
            }
        }

        assertThat(legacyFloor)
                .as("a lone applicable signal at 0.50 used to be the cheapest flag available")
                .isCloseTo(0.50, within(0.01));
        assertThat(floor)
                .as("nothing can now be flagged by a signal below this")
                .isCloseTo(CompositeAggregation.soloScoreToElevate(
                        Arrays.stream(Signal.values()).mapToDouble(Signal::weight)
                                .max().orElseThrow(), THRESHOLD), within(1e-6));
        assertThat(floor)
                .as("the floor rose from %.3f to %.3f", legacyFloor, floor)
                .isGreaterThan(legacyFloor);
    }

    /**
     * The one configuration that did get easier, pinned deliberately so the
     * report's before/after cannot quietly drift.
     */
    @Test
    @DisplayName("the single configuration that got easier is named, and lands above the old floor")
    void theOneConfigurationThatGotEasier() {
        Set<Signal> pair = EnumSet.of(Signal.AMOUNT_OUTLIER, Signal.VELOCITY);

        double legacyBar = legacySoloScoreToFlag(pair, Signal.AMOUNT_OUTLIER);
        double bar = soloScoreToFlag(pair, Signal.AMOUNT_OUTLIER);

        assertThat(legacyBar).isCloseTo(0.900, within(0.01));
        assertThat(bar).isCloseTo(0.794, within(0.01));
        assertThat(bar)
                .as("easier than it was, and still far above the 0.50 that used to be available")
                .isGreaterThan(0.75);
    }

    /**
     * The other half of "not trivially easy": a signal barely over its own
     * firing threshold must still not flag an account on its own.
     */
    @Test
    @DisplayName("a signal that merely fires does not flag an account by itself")
    void aBarelyFiringSignalDoesNotFlagAlone() {
        for (Signal signal : Signal.values()) {
            for (double score : new double[]{0.1, 0.25, 0.5, 0.7}) {
                assertThat(composite(EnumSet.allOf(Signal.class), Map.of(signal, score)))
                        .as("%s alone at %.2f", signal.wireName(), score)
                        .isLessThan(THRESHOLD);
            }
        }
    }

    @Test
    @DisplayName("ordinary noise across several signals still does not flag")
    void noiseDoesNotFlag() {
        Map<Signal, Double> noisy = Map.of(
                Signal.AMOUNT_OUTLIER, 0.5,
                Signal.VELOCITY, 0.5,
                Signal.BURST, 0.3);

        assertThat(composite(EnumSet.allOf(Signal.class), noisy))
                .as("three partially-firing signals are not three saturated ones")
                .isLessThan(THRESHOLD);
    }

    // -------------------------------------------------------- the scale itself

    @Test
    @DisplayName("everything saturated is still exactly 1.0")
    void everythingSaturatedIsOne() {
        assertThat(composite(EnumSet.allOf(Signal.class), EnumSet.allOf(Signal.class)))
                .isCloseTo(1.0, within(1e-12));
    }

    @Test
    @DisplayName("nothing firing is still exactly 0")
    void nothingFiringIsZero() {
        assertThat(composite(EnumSet.allOf(Signal.class), EnumSet.noneOf(Signal.class))).isZero();
        assertThat(CompositeAggregation.combine(List.of())).isZero();
    }

    /**
     * The pathology Phase 8 documented and accepted, retired as a side effect.
     *
     * <p>One applicable signal, saturated, used to report total certainty. It
     * now reports 0.63: elevated, which is right, and nothing like certain,
     * which is also right, because one signal was all the evidence there was.
     */
    @Test
    @DisplayName("a lone applicable signal no longer reports a composite of 1.0")
    void aSingleApplicableSignalNoLongerReportsCertainty() {
        for (Signal signal : Signal.values()) {
            assertThat(composite(EnumSet.of(signal), EnumSet.of(signal)))
                    .as("%s alone", signal.wireName())
                    .isGreaterThanOrEqualTo(THRESHOLD)
                    .isLessThan(0.8);
        }
    }

    /**
     * A thin account can no longer reach the top of the scale, which is the
     * deliberate cost of dropping renormalisation: the composite now says how
     * much alarming evidence there is, and there is less of it.
     */
    @Test
    @DisplayName("a thin account cannot reach 1.0 even with everything it has saturated")
    void thinAccountsCapBelowOne() {
        Set<Signal> pair = EnumSet.of(Signal.AMOUNT_OUTLIER, Signal.VELOCITY);

        assertThat(composite(pair, pair))
                .isGreaterThanOrEqualTo(THRESHOLD)
                .isLessThan(0.8);
    }

    // ------------------------------------------------------------ the exponent

    /**
     * The exponent is derived from the lightest weight and the threshold, so it
     * is not a constant anybody chose. This pins the derivation: three is the
     * smallest integer that lets the lightest signal in the system flag an
     * account alone.
     */
    @Test
    @DisplayName("three is the smallest exponent that closes the ceiling")
    void theExponentIsTheSmallestThatWorks() {
        double lightest = Arrays.stream(Signal.values())
                .mapToDouble(Signal::weight)
                .min()
                .orElseThrow();

        assertThat(Math.pow(lightest, 1.0 / 2))
                .as("degree two leaves the lightest signal short of the bar")
                .isLessThan(THRESHOLD);
        assertThat(Math.pow(lightest, 1.0 / CompositeAggregation.DEGREE))
                .as("degree three clears it")
                .isGreaterThanOrEqualTo(THRESHOLD);
        assertThat(CompositeAggregation.DEGREE).isEqualTo(3);
    }

    @Test
    @DisplayName("the five weights still sum to one, which the derivation assumes")
    void weightsSumToOne() {
        assertThat(TOTAL_WEIGHT).isCloseTo(1.0, within(1e-12));
    }

    // --------------------------------------------------------------- helpers

    private static double composite(Set<Signal> applicable, Set<Signal> saturating) {
        Map<Signal, Double> scores = new java.util.EnumMap<>(Signal.class);
        saturating.forEach(signal -> scores.put(signal, 1.0));
        return composite(applicable, scores);
    }

    /** Through the real aggregation, so this file cannot drift from it again. */
    private static double composite(Set<Signal> applicable, Map<Signal, Double> scores) {
        List<SignalScore> outcomes = new ArrayList<>();
        for (Signal signal : Signal.values()) {
            if (applicable.contains(signal)) {
                outcomes.add(new SignalScore(signal, true, Double.NaN,
                        scores.getOrDefault(signal, 0.0), "fixture", null));
            } else {
                outcomes.add(SignalScore.insufficientData(signal, "not applicable in this fixture"));
            }
        }
        return CompositeAggregation.combine(outcomes);
    }

    /** Phases 8-12's aggregation, kept so the before/after in the report is pinned. */
    private static double legacyComposite(Set<Signal> applicable, Map<Signal, Double> scores) {
        double applicableWeight = applicable.stream().mapToDouble(Signal::weight).sum();
        double weighted = applicable.stream()
                .mapToDouble(signal -> signal.weight() * scores.getOrDefault(signal, 0.0))
                .sum();
        return applicableWeight == 0 ? 0 : weighted / applicableWeight;
    }

    private static double soloScoreToFlag(Set<Signal> applicable, Signal lone) {
        return bisect(score -> composite(applicable, Map.of(lone, score)));
    }

    private static double legacySoloScoreToFlag(Set<Signal> applicable, Signal lone) {
        return bisect(score -> legacyComposite(applicable, Map.of(lone, score)));
    }

    /** The lowest score at which this arrangement flags; 1.0 when it never does. */
    private static double bisect(java.util.function.DoubleUnaryOperator compositeOf) {
        double low = 0.0;
        double high = 1.0;
        for (int step = 0; step < 60; step++) {
            double mid = (low + high) / 2;
            if (compositeOf.applyAsDouble(mid) >= THRESHOLD) {
                high = mid;
            } else {
                low = mid;
            }
        }
        return high;
    }

    private static int minimumSaturatingToFlag(Set<Signal> applicable) {
        List<Signal> heaviestFirst = applicable.stream()
                .sorted(java.util.Comparator.comparingDouble(Signal::weight).reversed())
                .toList();

        Set<Signal> saturating = EnumSet.noneOf(Signal.class);
        for (int count = 0; count < heaviestFirst.size(); count++) {
            saturating.add(heaviestFirst.get(count));
            if (composite(applicable, saturating) >= THRESHOLD) {
                return count + 1;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static List<Set<Signal>> nonEmptySubsets() {
        List<Set<Signal>> subsets = new ArrayList<>();
        Signal[] all = Signal.values();

        for (int mask = 1; mask < (1 << all.length); mask++) {
            Set<Signal> subset = EnumSet.noneOf(Signal.class);
            for (int bit = 0; bit < all.length; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    subset.add(all[bit]);
                }
            }
            subsets.add(subset);
        }
        return subsets;
    }

    private static String wireNames(Set<Signal> signals) {
        return signals.stream().map(Signal::wireName)
                .collect(java.util.stream.Collectors.joining(","));
    }
}
