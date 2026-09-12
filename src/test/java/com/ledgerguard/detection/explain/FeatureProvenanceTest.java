package com.ledgerguard.detection.explain;

import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.ml.FeatureVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The map from model features to the statistical layer.
 *
 * <p>This enum restates {@link FeatureVector#NAMES}, and the failure mode if
 * the two part company is quiet and bad: an explanation would attribute a
 * feature's isolation to the wrong signal, or claim the statistical layer
 * cannot see something it measures. So the correspondence is pinned rather than
 * trusted.
 */
class FeatureProvenanceTest {

    @Test
    @DisplayName("every feature has a provenance, in the model's own index order")
    void namesAndOrderMatchTheFeatureVector() {
        assertThat(FeatureProvenance.values()).hasSameSizeAs(FeatureVector.NAMES);

        assertThat(Arrays.stream(FeatureProvenance.values())
                .map(FeatureProvenance::featureName)
                .toList())
                .as("index order is how attribution addresses features; a shifted map "
                        + "would attribute isolation to the wrong axis")
                .containsExactly(FeatureVector.NAMES);
    }

    @ParameterizedTest
    @EnumSource(FeatureProvenance.class)
    @DisplayName("lookup by index and by name agree with the declaration")
    void lookupsAgree(FeatureProvenance provenance) {
        int index = Arrays.asList(FeatureVector.NAMES).indexOf(provenance.featureName());

        assertThat(FeatureProvenance.byIndex(index)).isEqualTo(provenance);
        assertThat(FeatureProvenance.byName(provenance.featureName())).contains(provenance);
    }

    @Test
    @DisplayName("the first five features are the five signals' own statistics")
    void signalStatisticsMapOneToOne() {
        assertThat(Arrays.stream(FeatureProvenance.values())
                .filter(provenance ->
                        provenance.visibility() == FeatureProvenance.Visibility.SIGNAL_STATISTIC)
                .map(provenance -> provenance.signal().orElseThrow())
                .toList())
                .containsExactlyInAnyOrder(Signal.values());
    }

    /**
     * The two Phase 9 added precisely because the statistical layer structurally
     * cannot express them. If a later change made something else invisible, the
     * disagreement narrative would start claiming so, and this test is where
     * that decision gets made deliberately.
     */
    @Test
    @DisplayName("exactly two features are outside the statistical layer's view")
    void outsideFeaturesAreTheExpectedTwo() {
        assertThat(Arrays.stream(FeatureProvenance.values())
                .filter(FeatureProvenance::outsideStatisticalLayer)
                .map(FeatureProvenance::featureName)
                .toList())
                .containsExactly("log10LargestRecentAmount", "log10SecondsSinceLastPayment");
    }

    @Test
    @DisplayName("a feature outside the view claims no signal; one inside it names one")
    void visibilityAndSignalAgree() {
        for (FeatureProvenance provenance : FeatureProvenance.values()) {
            if (provenance.outsideStatisticalLayer()) {
                assertThat(provenance.signal()).isEmpty();
            } else if (provenance.visibility() == FeatureProvenance.Visibility.SIGNAL_STATISTIC) {
                assertThat(provenance.signal()).isPresent();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(FeatureProvenance.class)
    @DisplayName("every feature can describe itself in words a summary can use")
    void descriptionsExist(FeatureProvenance provenance) {
        assertThat(provenance.description()).isNotBlank();
    }
}
