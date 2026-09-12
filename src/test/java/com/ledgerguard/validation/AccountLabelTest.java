package com.ledgerguard.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a label must say about itself before it is allowed to exist.
 *
 * <p>Each refusal here corresponds to a way an evaluation goes quietly wrong. A
 * human label with no stratum cannot be weighted and corrupts recall; one with
 * no reviewer cannot be checked against a second opinion; one observed before
 * the behaviour it describes is a clock error that would otherwise surface much
 * later as an impossible-looking metric.
 */
class AccountLabelTest {

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final Instant SEPTEMBER = Instant.parse("2026-09-12T10:00:00Z");
    private static final Instant NOVEMBER = Instant.parse("2026-11-11T10:00:00Z");

    @Test
    @DisplayName("a reviewer's label carries everything needed to weight and audit it")
    void humanLabel() {
        AccountLabel label = AccountLabel.byReviewer(ACCOUNT, Verdict.ANOMALOUS, "alex",
                Stratum.AUDIT, false, SEPTEMBER, SEPTEMBER, "looked odd");

        assertThat(label.getSource()).isEqualTo(LabelSource.HUMAN_REVIEW);
        assertThat(label.getStratum()).isEqualTo(Stratum.AUDIT);
        assertThat(label.getReviewer()).isEqualTo("alex");
        assertThat(label.isScoresVisible()).isFalse();
        assertThat(label.latency()).isZero();
    }

    @Test
    @DisplayName("a human label without a reviewer is refused")
    void humanLabelNeedsAReviewer() {
        assertThatThrownBy(() -> AccountLabel.byReviewer(ACCOUNT, Verdict.BENIGN, "  ",
                Stratum.FLAGGED, true, SEPTEMBER, SEPTEMBER, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("who made it");
    }

    @Test
    @DisplayName("a human label without a stratum is refused")
    void humanLabelNeedsAStratum() {
        assertThatThrownBy(() -> AccountLabel.byReviewer(ACCOUNT, Verdict.BENIGN, "alex",
                null, true, SEPTEMBER, SEPTEMBER, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("stratum");
    }

    /**
     * The gap between the behaviour and the truth about it is the property that
     * makes these labels hard to use, so it is carried rather than derived
     * somewhere downstream by whoever remembers to.
     */
    @Test
    @DisplayName("a dispute label records how late the truth arrived")
    void disputeLabelCarriesItsLatency() {
        AccountLabel label = AccountLabel.fromDispute(ACCOUNT, Verdict.ANOMALOUS,
                UUID.randomUUID(), SEPTEMBER, NOVEMBER, "chargeback");

        assertThat(label.getSource()).isEqualTo(LabelSource.DISPUTE_FEED);
        assertThat(label.getLabelledAsOf()).isEqualTo(SEPTEMBER);
        assertThat(label.getObservedAt()).isEqualTo(NOVEMBER);
        assertThat(label.latency()).isEqualTo(Duration.between(SEPTEMBER, NOVEMBER));
        assertThat(label.getStratum())
                .as("a scheme does not sample from a frame we designed")
                .isNull();
    }

    @Test
    @DisplayName("a dispute label is never anchored, because no reviewer was involved")
    void disputeLabelsAreNeverAnchored() {
        assertThat(AccountLabel.fromDispute(ACCOUNT, Verdict.ANOMALOUS, UUID.randomUUID(),
                SEPTEMBER, NOVEMBER, null).isScoresVisible())
                .as("a card scheme has never seen this system's scores")
                .isFalse();
    }

    @Test
    @DisplayName("nothing can be observed before the behaviour it describes")
    void observedBeforeSubjectIsRefused() {
        assertThatThrownBy(() -> AccountLabel.fromDispute(ACCOUNT, Verdict.ANOMALOUS,
                UUID.randomUUID(), NOVEMBER, SEPTEMBER, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be observed before");
    }

    @Test
    @DisplayName("a synthetic label is instantaneous and carries no stratum")
    void syntheticLabel() {
        AccountLabel label = AccountLabel.synthetic(ACCOUNT, Verdict.ANOMALOUS, SEPTEMBER, "by construction");

        assertThat(label.getSource()).isEqualTo(LabelSource.SYNTHETIC);
        assertThat(label.getSource().isEvidenceAboutTheRealWorld()).isFalse();
        assertThat(label.latency()).isZero();
    }

    @Test
    @DisplayName("UNCLEAR is a real answer and is excluded from the arithmetic")
    void unclearIsNotDecisive() {
        assertThat(Verdict.UNCLEAR.isDecisive()).isFalse();
        assertThat(Verdict.ANOMALOUS.isDecisive()).isTrue();
        assertThat(Verdict.BENIGN.isDecisive()).isTrue();
    }

    @Test
    @DisplayName("only a fraud chargeback is evidence about fraud")
    void onlyFraudReasonsLabel() {
        assertThat(DisputeReason.FRAUDULENT.isFraudEvidence()).isTrue();
        assertThat(DisputeReason.NOT_RECEIVED.isFraudEvidence()).isFalse();
        assertThat(DisputeReason.DUPLICATE.isFraudEvidence()).isFalse();
        assertThat(DisputeReason.AUTHORISATION.isFraudEvidence()).isFalse();
        assertThat(DisputeReason.OTHER.isFraudEvidence()).isFalse();
    }
}
