package com.ledgerguard.detection.explain.llm;

/**
 * Which generator produced the narrative a reviewer is reading.
 *
 * <p>Two values, and deliberately not three. There is no {@code LLM_FAILED} or
 * {@code FALLBACK}: a template served because the model timed out is the same
 * artefact as a template served because no key is configured, and naming the
 * difference on the wire would turn an ordinary degradation into something a
 * reviewer feels they should act on. Why a template was served is a question
 * for the logs and the counters, not for the person reading the account.
 */
public enum NarrativeSource {

    /** Phase 10's deterministic templates. Always available, always groundable. */
    TEMPLATE,

    /** A hosted model's prose, validated against the same evidence before serving. */
    LLM
}
