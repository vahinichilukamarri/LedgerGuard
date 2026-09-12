import { useLabels } from '../../api/queries';
import { Failure, Pending } from '../States';
import { LabelChip, NoLabel } from '../uncertainty/LabelChip';

/**
 * Every label on the account, oldest and newest alike.
 *
 * <h2>All of them, deliberately</h2>
 *
 * The endpoint returns the full history because a revised verdict does not
 * delete the first one and a second reviewer's disagreement is the most
 * informative thing available. Showing only the latest would present a contested
 * account as a settled one.
 *
 * <h2>Read-only, and the page says why</h2>
 *
 * There is no control here to record a verdict. Phase 12's review queue is blind
 * by default because a reviewer shown "0.87" before deciding produces an opinion
 * about the detector's opinion, and a detector evaluated against anchored labels
 * is largely measuring its own influence. A label submitted from this page —
 * which exists to display scores — would necessarily be anchored. See
 * CONSOLE_REPORT.md.
 */
export function LabelHistory({ accountId }: { accountId: string }) {
  const query = useLabels(accountId);

  return (
    <section className="card" id="labels">
      <h2>Label and review status</h2>
      <p className="card-note">
        What somebody concluded, and what that conclusion is worth. Every verdict carries its
        source and its sampling stratum, because a human reviewer’s judgement, a card scheme’s
        chargeback and a generated account are three different kinds of evidence wearing the same
        word.
      </p>

      {query.isPending && <Pending what="labels for this account" />}

      {query.isError && (
        <Failure
          what="labels for this account"
          error={query.error}
          onRetry={() => void query.refetch()}
        />
      )}

      {query.isSuccess && (query.data?.length ?? 0) === 0 && <NoLabel />}

      {query.isSuccess && (query.data?.length ?? 0) > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {[...(query.data ?? [])]
            .sort((left, right) => right.observedAt.localeCompare(left.observedAt))
            .map((label) => (
              <LabelChip key={label.id} label={label} />
            ))}

          {(query.data?.length ?? 0) > 1 && (
            <p className="card-note">
              More than one label. A revised verdict does not delete the first, and two reviewers
              disagreeing is a finding about how hard this account is to judge rather than an error
              in the record.
            </p>
          )}
        </div>
      )}

      <p className="read-only-note">
        Read-only. Verdicts are recorded through <code>POST /validation/labels</code>, and the
        review queue serves candidates blind by default — a page that shows the scores is the wrong
        place to record an opinion about them.
      </p>
    </section>
  );
}
