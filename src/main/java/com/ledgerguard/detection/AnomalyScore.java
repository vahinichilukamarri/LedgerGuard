package com.ledgerguard.detection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One account's composite anomaly score, with every signal that produced it.
 *
 * <h2>The breakdown is not decoration</h2>
 *
 * A single number is not actionable. "Account X scores 0.72" tells an
 * investigator nothing about where to look; "0.72, because four payments landed
 * within 900 ms against a baseline of two an hour" tells them what to open
 * first. Every {@link SignalScore} carries its own explanation and, where it
 * can, the id of the payment that caused it, so the composite can always be
 * taken apart again.
 *
 * <p>This matters more than usual here, because the weights are unfitted. A
 * score whose provenance is visible can be argued with; a score that is only a
 * number has to be trusted, and this one has not yet earned that.
 *
 * @param applicableSignals how many signals had enough data to judge. Carried
 *                          beside the score because the composite is
 *                          renormalised over exactly those, so a score of 0.9
 *                          from one applicable signal and one from all five are
 *                          very different claims wearing similar numbers
 */
public record AnomalyScore(
        UUID accountId,
        Instant asOf,
        double composite,
        int applicableSignals,
        List<SignalScore> signals) {

    public AnomalyScore {
        signals = List.copyOf(signals);
    }

    /** Signals that actually fired, worst first: the investigator's reading order. */
    public List<SignalScore> firedSignals() {
        return signals.stream()
                .filter(SignalScore::fired)
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .toList();
    }

    /**
     * Whether the score rests on enough evidence to act on.
     *
     * <p>Two applicable signals is a deliberately low bar, and it is a floor
     * rather than a recommendation: it excludes the case where a brand-new
     * account's single measurable signal fires and renormalisation turns that
     * into a composite of 1.0.
     */
    public boolean isWellEvidenced() {
        return applicableSignals >= 2;
    }
}
