package com.ledgerguard.detection.ml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The population context an attribution carries.
 *
 * <p>Small and worth pinning anyway: the percentile is the only thing in the
 * explanation that says which <em>direction</em> a feature was extreme in, and
 * an off-by-one at the boundary would mean a reviewer reading "at the 100th
 * percentile" for a perfectly median account.
 */
class PopulationProfileTest {

    private static PopulationProfile profile() {
        return PopulationProfile.of(List.of(
                new double[]{1.0, 10.0},
                new double[]{2.0, 20.0},
                new double[]{3.0, 30.0},
                new double[]{4.0, 40.0},
                new double[]{5.0, 50.0}));
    }

    @Test
    @DisplayName("a percentile is the fraction of training rows at or below the value")
    void percentileIsARank() {
        PopulationProfile profile = profile();

        assertThat(profile.percentileOf(0, 3.0)).isCloseTo(0.6, within(1e-12));
        assertThat(profile.percentileOf(0, 5.0)).isEqualTo(1.0);
        assertThat(profile.percentileOf(0, 0.5)).isZero();
    }

    @Test
    @DisplayName("a value beyond either end of the population saturates rather than overflows")
    void outsideThePopulation() {
        PopulationProfile profile = profile();

        assertThat(profile.percentileOf(1, 10_000.0)).isEqualTo(1.0);
        assertThat(profile.percentileOf(1, -10_000.0)).isZero();
    }

    @Test
    @DisplayName("ties count as at-or-below, so a repeated value does not read as rare")
    void tiesAreInclusive() {
        PopulationProfile profile = PopulationProfile.of(List.of(
                new double[]{7.0}, new double[]{7.0}, new double[]{7.0}, new double[]{9.0}));

        assertThat(profile.percentileOf(0, 7.0)).isCloseTo(0.75, within(1e-12));
    }

    @Test
    @DisplayName("the median is the middle row, or the mean of the two middle rows")
    void median() {
        assertThat(profile().medianOf(0)).isEqualTo(3.0);
        assertThat(PopulationProfile.of(List.of(
                new double[]{1.0}, new double[]{2.0}, new double[]{3.0}, new double[]{6.0}))
                .medianOf(0)).isEqualTo(2.5);
    }

    @Test
    @DisplayName("the profile describes the population it was given, and says how large it was")
    void shape() {
        assertThat(profile().size()).isEqualTo(5);
        assertThat(profile().dimension()).isEqualTo(2);
    }

    @Test
    @DisplayName("a ragged or empty population is refused rather than silently indexed")
    void refusesBadInput() {
        assertThatThrownBy(() -> PopulationProfile.of(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PopulationProfile.of(List.of(
                new double[]{1.0, 2.0}, new double[]{3.0})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same length");
    }
}
