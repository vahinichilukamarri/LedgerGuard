import { useDefaultNarrative } from '../../api/queries';
import type { AccountExplanation } from '../../api/types';
import {
  NarrativeBlock,
  NarrativePending,
  NarrativeUnavailable,
} from '../uncertainty/NarrativeBlock';
import { describe } from '../States';

/**
 * The narrative, and the deterministic one beside it on request.
 *
 * <h2>Two queries, not one</h2>
 *
 * `?narrative=template` skips the model and is fast. The default path may call a
 * hosted model and is the slowest request in the system. The page fetches the
 * template first and renders every number from it, so the arithmetic is on
 * screen while the prose is still in flight — and if the default request never
 * returns, nothing a reviewer needs is missing, only the wording.
 *
 * <h2>What the comparison is for</h2>
 *
 * The toggle is not a preference. It exists so a reviewer can read the model's
 * prose against the deterministic restatement of the same evidence and see for
 * themselves that the numbers match — which is the only check available to
 * someone who is not going to read the validator's source.
 *
 * When the default narrative is itself the template, the comparison would show
 * one text twice. The page says so rather than presenting an identical pair as
 * if it were a corroboration.
 */
export function NarrativeSection({
  template,
  accountId,
  compare,
  onCompareChange,
}: {
  template: AccountExplanation;
  accountId: string;
  compare: boolean;
  onCompareChange: (next: boolean) => void;
}) {
  const preferred = useDefaultNarrative(accountId, true);

  const defaultIsTemplate = preferred.data?.narrativeSource === 'TEMPLATE';
  const sameText = preferred.data?.summary === template.summary;

  return (
    <section className="card" id="narrative">
      <h2>Narrative</h2>
      <p className="card-note">
        Prose, and the thing that wrote it, named. Whichever generator produced it, it restates the
        evidence below and cannot add to it.
      </p>

      <label className="checkbox" style={{ display: 'flex', gap: 8, marginBottom: 12, fontSize: 12.5 }}>
        <input
          type="checkbox"
          checked={compare}
          onChange={(event) => onCompareChange(event.target.checked)}
        />
        Compare against the deterministic template (<code>?narrative=template</code>)
      </label>

      <div className={`narrative-pair${compare ? ' side-by-side' : ''}`}>
        {preferred.isPending && <NarrativePending />}

        {preferred.isError && <NarrativeUnavailable detail={describe(preferred.error)} />}

        {preferred.isSuccess && preferred.data && (
          <NarrativeBlock
            source={preferred.data.narrativeSource}
            text={preferred.data.summary}
            heading="Default narrative — what the API serves when asked for no particular source"
          />
        )}

        {compare && (
          <NarrativeBlock
            source={template.narrativeSource}
            text={template.summary}
            heading="Deterministic narrative — ?narrative=template"
          />
        )}
      </div>

      {compare && preferred.isSuccess && defaultIsTemplate && (
        <p className="card-note" style={{ marginTop: 10 }}>
          The default narrative was itself the template
          {sameText ? ', and these two texts are identical' : ''}. That is not a comparison of two
          generators — the API does not report why a template was served, so this says nothing about
          whether a model was tried.
        </p>
      )}
    </section>
  );
}
