package com.ledgerguard.detection.explain.llm;

/**
 * One account's narrative, and where it came from.
 *
 * <h2>The discriminator is not optional detail</h2>
 *
 * Two narratives describing the same evidence can differ in how much they can
 * be trusted to be describing it. The template is a function of the numbers:
 * it cannot say anything the numbers do not, because it was written by someone
 * who could see them. The model's version has been validated against those
 * numbers but was not derived from them, and validation catches grounded parts
 * assembled wrongly less reliably than it catches ungrounded parts.
 *
 * <p>A reviewer who cannot tell which one they are reading has to apply the
 * weaker standard to both. So {@link #source} travels on every response, and
 * {@code ?narrative=template} will return the deterministic one on demand.
 */
public record Narrative(String text, NarrativeSource source) {

    public static Narrative template(String text) {
        return new Narrative(text, NarrativeSource.TEMPLATE);
    }

    public static Narrative llm(String text) {
        return new Narrative(text, NarrativeSource.LLM);
    }

    public boolean isFromModel() {
        return source == NarrativeSource.LLM;
    }
}
