import type { NarrativeSource } from '../../api/types';

/**
 * Prose, with the thing that wrote it named.
 *
 * <h2>The badge is a required prop</h2>
 *
 * Not optional, not defaulted. A component that could render a narrative without
 * saying where it came from would eventually be called that way, and the two
 * sources are not interchangeable: one is a deterministic restatement of the
 * numbers below it, the other is a hosted model's prose that happened to pass
 * validation. Making the source unforgettable in the type system is cheaper than
 * catching it in review.
 *
 * <h2>What the console cannot say about TEMPLATE</h2>
 *
 * `NarrativeSource` has two values and deliberately no third. Its own
 * documentation explains why: a template served because the model timed out is
 * the same artefact as one served because no key is configured, and naming that
 * difference on the wire would turn an ordinary degradation into something a
 * reviewer feels they should act on.
 *
 * So the console does not claim a fallback happened. It says what is knowable —
 * this is the deterministic template — and states plainly that the API does not
 * distinguish the reasons. Inventing "the model call failed" from a TEMPLATE
 * badge would be the console asserting something the backend refused to.
 */
export function NarrativeBlock({
  source,
  text,
  heading,
}: {
  source: NarrativeSource;
  text: string;
  heading?: string;
}) {
  const fromModel = source === 'LLM';

  return (
    <section
      className={`narrative ${fromModel ? 'from-llm' : 'from-template'}`}
      data-narrative-source={source}
      aria-label={heading ?? (fromModel ? 'Model-written narrative' : 'Template narrative')}
    >
      {heading && <h3 className="card-note">{heading}</h3>}

      <span className="narrative-badge">
        {fromModel ? 'WRITTEN BY A HOSTED MODEL' : 'DETERMINISTIC TEMPLATE'}
      </span>

      <p className="narrative-provenance">
        {fromModel
          ? 'A hosted model wrote this from a closed evidence payload and it was checked ' +
            'name-by-name and number-by-number before serving. It restates the evidence below; ' +
            'it cannot add to it.'
          : 'Generated from the numbers below by a fixed template. The API reports only that a ' +
            'template was served — it does not distinguish a template served because no model is ' +
            'configured from one served because a model call did not succeed, so neither does ' +
            'this console.'}
      </p>

      <p className="narrative-text">{text}</p>
    </section>
  );
}

/**
 * The default narrative while it is still in flight.
 *
 * Its own state rather than a spinner over the page. The model call is the one
 * request in this system slower than a simple read, and holding four kilobytes
 * of arithmetic behind it would make the slowest component the gatekeeper of the
 * fastest ones.
 */
export function NarrativePending() {
  return (
    <section className="narrative from-template" aria-live="polite">
      <span className="narrative-badge">NARRATIVE PENDING</span>
      <p className="narrative-pending">
        Asking for the default narrative, which may involve a hosted model. Every number on this
        page is already final and will not change when it arrives.
      </p>
    </section>
  );
}

/**
 * The default narrative could not be fetched.
 *
 * Stated as a missing narrative, not as a failed account. The template beside it
 * is already on screen and says the same things in the same numbers, so nothing
 * a reviewer needs is absent — only the prose is.
 */
export function NarrativeUnavailable({ detail }: { detail: string }) {
  return (
    <section className="narrative from-template" role="status">
      <span className="narrative-badge">NARRATIVE NOT RETRIEVED</span>
      <p className="narrative-provenance">
        The default narrative request did not return: {detail}. This says nothing about the
        account. The deterministic narrative is shown alongside and rests on the same evidence.
      </p>
    </section>
  );
}
