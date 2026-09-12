import type { MlDetail } from '../../api/types';
import { FeaturePopulationContext } from '../uncertainty/PopulationContext';
import { humanise, share as formatShare } from '../uncertainty/format';

/**
 * All eleven features, with attribution and context on separate rows.
 *
 * <h2>Why every feature appears</h2>
 *
 * Truncating to the isolating ones would let a reviewer read the rest as merely
 * absent. A feature that pushed this account <em>toward</em> the crowd is a
 * finding, and the backend publishes all eleven for that reason.
 *
 * <h2>Why the percentile is not a column</h2>
 *
 * It would fit. It would sort. And a percentile in a cell beside `excessBits`
 * reads as part of the same finding, which is exactly the conflation
 * `FeatureAttribution` documents against: attribution is what the forest did to
 * this point, a percentile is a fact about the training population, and they
 * answer different questions.
 *
 * So each feature gets two rows. The first is attribution and only attribution.
 * The second is the population context, inside its own labelled wrapper, in a
 * different visual register. A reader can still relate them — they are adjacent —
 * but cannot mistake one for the other.
 */
export function AttributionTable({ ml }: { ml: MlDetail }) {
  return (
    <div className="table-scroll">
      <table className="ranking">
        <caption className="card-note" style={{ textAlign: 'left', paddingBottom: 8 }}>
          Reconstructed from this account’s paths through the forest under one credit rule. Not a
          unique decomposition of the score: correlated features can take credit a single one of
          them would have earned alone.
        </caption>
        <thead>
          <tr>
            <th scope="col">Feature</th>
            <th scope="col">
              Value
              <span className="th-note">as measured</span>
            </th>
            <th scope="col">
              Splits per tree
              <span className="th-note">what the model did</span>
            </th>
            <th scope="col">
              Isolation bits
              <span className="th-note">what the model did</span>
            </th>
            <th scope="col">
              Excess bits
              <span className="th-note">versus an even split</span>
            </th>
            <th scope="col">
              Share of the isolation
              <span className="th-note">not a share of the score</span>
            </th>
          </tr>
        </thead>

        <tbody>
          {ml.attributions.map((attribution) => {
            const isolating = attribution.excessBits > 0;
            return (
              <FeatureRows
                key={attribution.feature}
                attribution={attribution}
                isolating={isolating}
                trainingRows={ml.trainingRows}
              />
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function FeatureRows({
  attribution,
  isolating,
  trainingRows,
}: {
  attribution: MlDetail['attributions'][number];
  isolating: boolean;
  trainingRows: number;
}) {
  return (
    <>
      <tr data-feature={attribution.feature} data-isolating={String(isolating)}>
        <th scope="row" style={{ fontWeight: 500 }}>
          {humanise(attribution.feature)}
          {!isolating && (
            <span className="th-note">
              did not isolate — this feature pushed the account toward the crowd
            </span>
          )}
        </th>
        <td className="num">{attribution.value.toFixed(3)}</td>
        <td className="num">{attribution.splitsPerTree.toFixed(2)}</td>
        <td className="num">{attribution.isolationBits.toFixed(3)}</td>
        <td className="num">{attribution.excessBits.toFixed(3)}</td>
        <td className="num">{isolating ? formatShare(attribution.share) : '—'}</td>
      </tr>

      <tr>
        <td colSpan={6} style={{ paddingTop: 0 }}>
          <FeaturePopulationContext attribution={attribution} trainingRows={trainingRows} />
        </td>
      </tr>
    </>
  );
}
