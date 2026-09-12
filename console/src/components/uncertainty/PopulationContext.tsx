import type { ReactNode } from 'react';
import type { FeatureAttribution } from '../../api/types';
import { ordinalPercentile } from './format';

/**
 * The wrapper every population statistic has to be inside.
 *
 * <h2>Two kinds of number that must not touch</h2>
 *
 * `FeatureAttribution` carries two groups of fields. `splitsPerTree`,
 * `isolationBits`, `excessBits` and `share` are what the model did, read off this
 * account's real paths through the forest. `percentile` and `median` are not:
 * they are facts about the training population, supplied because attribution can
 * name a feature but cannot say which direction it was extreme in.
 *
 * Rendered in adjacent table cells the two groups read as one finding — "the
 * model isolated on this, and it is at the 99th percentile" becomes a single
 * claim, and the percentile silently acquires the model's authority. The
 * backend's own caveat is explicit that they are different questions.
 *
 * So the console makes them structurally distinct rather than merely adjacent:
 * a percentile appears only inside this wrapper, the wrapper always carries its
 * header, and a test asserts no percentile string renders outside one.
 */
export function PopulationContext({
  header = 'Context — population, not the model’s reasoning',
  children,
}: {
  header?: string;
  children: ReactNode;
}) {
  return (
    <div className="population-context" data-population-context="true">
      <div className="context-header">{header}</div>
      <div className="context-body">{children}</div>
    </div>
  );
}

/** One feature's population context, phrased so it cannot be read as attribution. */
export function FeaturePopulationContext({
  attribution,
  trainingRows,
}: {
  attribution: FeatureAttribution;
  trainingRows: number;
}) {
  // `FeatureAttribution.direction()` exists in Java but is a derived accessor,
  // not a record component, so it never reaches the wire. Recomputed here from
  // the two fields that do, by the same rule.
  const direction = attribution.value >= attribution.median ? 'above' : 'below';

  return (
    <PopulationContext>
      Sits {direction} the population median of {attribution.median.toFixed(2)}, at the{' '}
      {ordinalPercentile(attribution.percentile)} percentile of the {trainingRows} accounts in the
      training snapshot. Where a value sits among those accounts is a different question from what
      the forest did with it.
    </PopulationContext>
  );
}
