import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useTemplateExplanation } from '../api/queries';
import { Failure, Pending } from '../components/States';
import { AgreementChip } from '../components/uncertainty/AgreementChip';
import { ScoreReadout } from '../components/uncertainty/ScoreReadout';
import { SignalShareBar } from '../components/uncertainty/SignalShareBar';
import { CaveatList } from '../components/uncertainty/CaveatList';
import { AttributionTable } from '../components/account/AttributionTable';
import { NarrativeSection } from '../components/account/NarrativeSection';
import { LabelHistory } from '../components/account/LabelHistory';
import { humanise, instant, shortAccount } from '../components/uncertainty/format';
import { useScrollToHash } from '../useScrollToHash';

/**
 * Why one account scores what it scores.
 *
 * <h2>The order of the page is an argument</h2>
 *
 * Caveats first, then the two scores side by side, then each layer's own
 * evidence, then how they relate, then the prose, then the labels. The
 * qualification comes before the numbers because `AccountExplanation` keeps
 * caveats outside the summary for exactly that reason — a limitation folded into
 * a paragraph is a limitation nobody reads, and one placed under the fold is a
 * limitation nobody scrolls to.
 *
 * <h2>Every number comes from the template request</h2>
 *
 * The page renders against `?narrative=template`, which never calls a model. The
 * default narrative is a second, independent query. So the arithmetic is final
 * and on screen before the slowest request in the system has returned, and a
 * model timing out costs prose rather than evidence.
 */
export function AccountPage() {
  const { accountId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const compare = params.get('compare') === 'true';

  const query = useTemplateExplanation(accountId);

  // Deep links to a section only work once that section exists.
  useScrollToHash(query.isSuccess);

  if (query.isPending) {
    return <Pending what="the explanation for this account" />;
  }
  if (query.isError) {
    return (
      <Failure
        what="the explanation for this account"
        error={query.error}
        onRetry={() => void query.refetch()}
      />
    );
  }

  const explanation = query.data;
  const { statistical, ml, reconciliation } = explanation;

  return (
    <>
      <p style={{ fontSize: 12.5, margin: '0 0 12px' }}>
        <Link to="/anomalies">← Ranking</Link>
      </p>

      <CaveatList caveats={explanation.caveats} />

      <section className="card" style={{ marginTop: 16 }}>
        <h2>
          Account <code>{shortAccount(accountId)}</code>
        </h2>
        <p className="card-note">
          Assessed as of {instant(explanation.asOf)}. Two scores, kept apart: there is deliberately
          no single number here, because one would imply a reconciliation of the two that nothing in
          this system has earned.
        </p>

        <div className="grid-two">
          <div>
            <h3 className="card-note">Statistical composite</h3>
            <ScoreReadout value={statistical.composite} kind="statistical" />
            <p className="th-note" style={{ marginTop: 6 }}>
              {statistical.applicableSignals} of 5 signals could judge ·{' '}
              {statistical.wellEvidenced
                ? 'well evidenced'
                : 'thinly evidenced — most of the evidence this system can gather was never available for this account'}{' '}
              · {statistical.applicableWeight.toFixed(2)} of the signal weight was able to speak
            </p>
          </div>

          <div>
            <h3 className="card-note">Isolation score</h3>
            <ScoreReadout
              value={ml ? ml.score : null}
              kind="model"
              absentReason={explanation.mlUnavailableReason}
            />
            {ml && (
              <p className="th-note" style={{ marginTop: 6 }}>
                Expected path length {ml.expectedPathLength.toFixed(2)} · {ml.trainingRows} training
                accounts · seed {ml.model.seed}, {ml.model.trees} trees, trained as of{' '}
                {instant(ml.model.trainedAsOf)}
              </p>
            )}
          </div>
        </div>
      </section>

      <section className="card" id="agreement">
        <h2>How the two layers relate</h2>
        <p className="card-note">
          Stated rather than resolved. The state below is the API’s four-way verdict plus the
          distinction it publishes underneath — whether the layers share an axis, and whether a
          signal fired without carrying the composite over the line.
        </p>
        <AgreementChip
          input={{
            agreement: reconciliation?.agreement ?? null,
            corroborated: reconciliation?.corroborated ?? false,
            modelDriversOutsideView: reconciliation?.modelDriversOutsideView ?? [],
          }}
        />

        {reconciliation && (
          <>
            <p style={{ fontSize: 13.5, marginTop: 12 }}>{reconciliation.narrative}</p>
            <dl className="facts" style={{ marginTop: 10 }}>
              <dt>Statistical drivers</dt>
              <dd>
                {reconciliation.statisticalDrivers.length > 0
                  ? reconciliation.statisticalDrivers.map(humanise).join(', ')
                  : 'none'}
              </dd>
              <dt>Model drivers</dt>
              <dd>
                {reconciliation.modelDrivers.length > 0
                  ? reconciliation.modelDrivers.map(humanise).join(', ')
                  : 'none'}
              </dd>
              <dt>Shared axes</dt>
              <dd>
                {reconciliation.corroboratedSignals.length > 0
                  ? reconciliation.corroboratedSignals.map(humanise).join(', ')
                  : 'none — nothing here corroborates anything'}
              </dd>
              <dt>Outside the statistical view</dt>
              <dd>
                {reconciliation.modelDriversOutsideView.length > 0
                  ? reconciliation.modelDriversOutsideView.map(humanise).join(', ')
                  : 'none'}
              </dd>
            </dl>
          </>
        )}
      </section>

      <section className="card" id="signals">
        <h2>What the statistical layer measured</h2>
        <p className="card-note">
          Each signal’s share of the composite. The shares sum to one whenever anything fired at
          all — that identity is what makes this a breakdown rather than decoration. Signals that
          could not judge appear with no share rather than being dropped.
        </p>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {statistical.contributions.map((contribution) => (
            <SignalShareBar key={contribution.signal} contribution={contribution} />
          ))}
        </div>
      </section>

      <section className="card" id="attribution">
        <h2>What the model isolated on</h2>
        {ml ? (
          <AttributionTable ml={ml} />
        ) : (
          <p className="card-note">
            No model has been trained, so there is no attribution and no second opinion.{' '}
            {explanation.mlUnavailableReason ??
              'This is an absence of a measurement, not a measurement of zero.'}
          </p>
        )}
      </section>

      <NarrativeSection
        template={explanation}
        accountId={accountId}
        compare={compare}
        onCompareChange={(next) => {
          const updated = new URLSearchParams(params);
          if (next) {
            updated.set('compare', 'true');
          } else {
            updated.delete('compare');
          }
          setParams(updated, { replace: true });
        }}
      />

      <LabelHistory accountId={accountId} />
    </>
  );
}
