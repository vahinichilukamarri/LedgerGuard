package com.ledgerguard.detection;

/**
 * One statistical signal.
 *
 * <p>Deliberately a pure function of {@link AccountActivity}: no repository, no
 * clock, no state. Every implementation is therefore testable with a hand-built
 * sample and nothing else, and cannot become slow by querying inside a loop.
 */
public interface AnomalySignal {

    Signal signal();

    /**
     * Judge this account's activity.
     *
     * <p>Must return {@link SignalScore#insufficientData} rather than a zero
     * score when there is too little history to judge. The two are different
     * claims and the composite treats them differently.
     */
    SignalScore evaluate(AccountActivity activity, DetectionSettings settings);
}
