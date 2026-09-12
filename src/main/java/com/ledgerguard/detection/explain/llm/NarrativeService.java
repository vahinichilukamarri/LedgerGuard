package com.ledgerguard.detection.explain.llm;

import com.ledgerguard.detection.explain.AccountExplanation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * Produces the narrative a reviewer reads: the model's, if it earned it.
 *
 * <h2>Five ways to not call the model, one way to serve it</h2>
 *
 * The order below is the whole design. The template is not an error path taken
 * when something breaks — it is the default, and the model's prose replaces it
 * only after clearing every gate:
 *
 * <ol>
 *   <li>the caller asked for the deterministic one, or the layer is off;</li>
 *   <li>this evidence has been narrated before, so serve those words again;</li>
 *   <li>the gateway declines — no key, breaker open, attempts exhausted;</li>
 *   <li>the narrative fails validation against its own evidence;</li>
 *   <li>otherwise, cache it and serve it.</li>
 * </ol>
 *
 * <h2>Failure is logged, never surfaced</h2>
 *
 * A reviewer looking at a flagged account is doing real work, and an error
 * banner about a model provider is an interruption that tells them nothing they
 * can act on. Every failure here produces a correct, complete, deterministic
 * narrative — the one this system served for the whole of Phase 10 — and the
 * only trace is a log line and a counter. The response says {@code TEMPLATE}
 * because that is what it is, not because something went wrong.
 *
 * <h2>The counters</h2>
 *
 * {@link #stats()} exists because "the small model is good enough for this
 * task" should be a measurement rather than an opinion. Rejections over
 * attempts is the rejection rate, and the validator's reasons say whether the
 * answer is a better prompt or a bigger model.
 */
@Service
public class NarrativeService {

    private static final Logger log = LoggerFactory.getLogger(NarrativeService.class);

    private final GroqGateway gateway;
    private final NarrativeValidator validator;
    private final NarrativeCache cache;
    private final GroqProperties properties;

    private final LongAdder attempted = new LongAdder();
    private final LongAdder fromCache = new LongAdder();
    private final LongAdder fromModel = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder unavailable = new LongAdder();

    public NarrativeService(GroqGateway gateway, GroqProperties properties) {
        this(gateway, new NarrativeValidator(), new NarrativeCache(properties.cacheSize()), properties);
    }

    public NarrativeService(GroqGateway gateway, NarrativeValidator validator,
                            NarrativeCache cache, GroqProperties properties) {
        this.gateway = gateway;
        this.validator = validator;
        this.cache = cache;
        this.properties = properties;
    }

    /** The narrative for this account, from the model where possible. */
    public Narrative narrate(AccountExplanation explanation) {
        return narrate(explanation, false);
    }

    /**
     * @param preferTemplate skip the model entirely. The escape hatch behind
     *                       {@code ?narrative=template}, for a caller that
     *                       wants the reproducible wording — an audit trail, a
     *                       regression test, a second opinion on prose that
     *                       reads oddly
     */
    public Narrative narrate(AccountExplanation explanation, boolean preferTemplate) {
        Narrative template = Narrative.template(explanation.summary());

        if (preferTemplate || !properties.active()) {
            return template;
        }

        NarrativeEvidence evidence = NarrativeEvidence.of(explanation);
        String evidenceJson = PromptBuilder.toJson(evidence);
        String key = NarrativeCache.keyFor(
                properties.model(), PromptBuilder.PROMPT_VERSION, evidenceJson);

        Optional<String> cached = cache.get(key);
        if (cached.isPresent()) {
            fromCache.increment();
            return Narrative.llm(cached.get());
        }

        attempted.increment();
        Optional<String> completion = gateway.complete(
                PromptBuilder.systemPrompt(),
                PromptBuilder.userPrompt(evidence));

        if (completion.isEmpty()) {
            // The gateway has already logged why at the appropriate level.
            unavailable.increment();
            return template;
        }

        ValidationResult validation = validator.validate(completion.get(), evidence);
        if (!validation.valid()) {
            rejected.increment();
            // WARN, because a model contradicting its own evidence is worth
            // someone's attention eventually -- but the reviewer still gets a
            // complete narrative and no indication anything happened.
            log.warn("llm: narrative rejected for account {}, serving the template instead [{}]",
                    explanation.accountId(), validation.summary());
            return template;
        }

        cache.put(key, completion.get());
        fromModel.increment();
        return Narrative.llm(completion.get());
    }

    public Stats stats() {
        return new Stats(attempted.sum(), fromCache.sum(), fromModel.sum(),
                rejected.sum(), unavailable.sum());
    }

    /**
     * @param attempted   calls made to the model, cache hits excluded
     * @param rejected    completions that failed validation
     * @param unavailable attempts the gateway declined or could not complete
     */
    public record Stats(long attempted, long fromCache, long fromModel,
                        long rejected, long unavailable) {

        /** Share of completions that failed validation. The model-quality number. */
        public double rejectionRate() {
            long judged = fromModel + rejected;
            return judged == 0 ? 0.0 : rejected / (double) judged;
        }
    }
}
