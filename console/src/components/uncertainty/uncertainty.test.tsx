import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { AgreementChip } from './AgreementChip';
import { ScoreReadout, StatisticReadout } from './ScoreReadout';
import { NarrativeBlock } from './NarrativeBlock';
import { LabelChip, LabelSummary, NoLabel } from './LabelChip';
import { PopulationContext, FeaturePopulationContext } from './PopulationContext';
import { SignalShareBar } from './SignalShareBar';
import { CaveatList, StandingCaveat } from './CaveatList';
import { attribution, label, signal } from '../../test/fixtures';
import { textOutsidePopulationContext } from '../../test/dom';

/**
 * The tests item 2 of the phase brief is actually about.
 *
 * Each one asserts a property of what reaches the DOM rather than a snapshot of
 * how it is arranged, because the constraint is about what a reader can be told,
 * not about layout. A snapshot would pass while the copy drifted; these fail.
 */

describe('AgreementChip', () => {
  it('states elevation for both layers in words, not only in colour', () => {
    render(
      <AgreementChip
        input={{ agreement: 'ML_ONLY', corroborated: false, modelDriversOutsideView: ['x'] }}
      />,
    );

    expect(screen.getByText(/statistical: not elevated/i)).toBeInTheDocument();
    expect(screen.getByText(/model: elevated/i)).toBeInTheDocument();
  });

  it('never renders a single combined flag', () => {
    const { container } = render(
      <AgreementChip
        input={{ agreement: 'BOTH_ELEVATED', corroborated: true, modelDriversOutsideView: [] }}
      />,
    );
    expect(container.textContent?.toLowerCase()).not.toMatch(/\bflagged\b/);
  });

  it('distinguishes corroborated agreement from agreement on unrelated evidence', () => {
    const { container: shared } = render(
      <AgreementChip
        input={{ agreement: 'BOTH_ELEVATED', corroborated: true, modelDriversOutsideView: [] }}
      />,
    );
    const { container: unrelated } = render(
      <AgreementChip
        input={{ agreement: 'BOTH_ELEVATED', corroborated: false, modelDriversOutsideView: [] }}
      />,
    );

    expect(shared.querySelector('[data-state]')?.getAttribute('data-state')).toBe(
      'BOTH_ELEVATED_SHARED_AXIS',
    );
    expect(unrelated.querySelector('[data-state]')?.getAttribute('data-state')).toBe(
      'BOTH_ELEVATED_UNRELATED',
    );
    expect(shared.textContent).not.toEqual(unrelated.textContent);
  });

  it('renders the dilution case as its own state', () => {
    const { container } = render(
      <AgreementChip
        input={{ agreement: 'ML_ONLY', corroborated: true, modelDriversOutsideView: [] }}
      />,
    );
    expect(container.querySelector('[data-state]')?.getAttribute('data-state')).toBe(
      'ML_ONLY_DILUTED',
    );
    expect(container.textContent).toMatch(/diluted/i);
  });

  it('carries the API enum alongside the console’s finer reading', () => {
    render(
      <AgreementChip
        input={{ agreement: 'STATISTICAL_ONLY', corroborated: false, modelDriversOutsideView: [] }}
      />,
    );
    expect(screen.getByText('STATISTICAL_ONLY')).toBeInTheDocument();
  });
});

describe('ScoreReadout', () => {
  it('never prints a score without the convention it is compared against', () => {
    render(<ScoreReadout value={0.62} kind="statistical" />);
    const readout = screen.getByTestId('score-statistical');

    expect(readout).toHaveTextContent('0.62');
    expect(readout).toHaveTextContent(/at or above the 0.50 convention \(not fitted\)/i);
  });

  it('names a different convention for the isolation scale', () => {
    render(<ScoreReadout value={0.62} kind="model" />);
    const readout = screen.getByTestId('score-model');

    // 0.62 is elevated on the model scale and would be elevated on the
    // statistical one too, but the cut-offs are different numbers and the
    // readout must not borrow the other scale's.
    expect(readout).toHaveTextContent(/0.60 convention/);
    expect(readout).toHaveTextContent(/middle of the distribution/i);
  });

  it('renders a missing score as an absence, never as a zero', () => {
    render(<ScoreReadout value={null} kind="model" absentReason="no model has been trained" />);
    const absent = screen.getByTestId('score-absent-model');

    expect(absent).toHaveTextContent(/no score/i);
    expect(absent).toHaveTextContent(/no model has been trained/i);
    expect(absent.textContent).not.toMatch(/0\.00/);
  });

  it('compares a raw statistic against its own scale’s flagging point', () => {
    const { container: surprisal } = render(
      <StatisticReadout value={3.2} threshold={3} scale="surprisal" />,
    );
    const { container: modifiedZ } = render(
      <StatisticReadout value={3.2} threshold={3.5} scale="modified z" />,
    );

    // The same number fires on one scale and not the other. A single shared
    // threshold would report one of these wrongly.
    expect(surprisal.textContent).toMatch(/at or above its 3.0 flagging point/i);
    expect(modifiedZ.textContent).toMatch(/below its 3.5 flagging point/i);
  });
});

describe('NarrativeBlock', () => {
  it('marks model-written prose as model-written', () => {
    render(<NarrativeBlock source="LLM" text="Some prose about an account." />);

    expect(screen.getByText(/written by a hosted model/i)).toBeInTheDocument();
    expect(screen.getByText(/it cannot add to it/i)).toBeInTheDocument();
  });

  it('renders the two sources differently, never identically', () => {
    const { container: llm } = render(<NarrativeBlock source="LLM" text="Identical text." />);
    const { container: template } = render(
      <NarrativeBlock source="TEMPLATE" text="Identical text." />,
    );

    expect(llm.querySelector('[data-narrative-source]')?.getAttribute('data-narrative-source')).toBe(
      'LLM',
    );
    expect(
      template.querySelector('[data-narrative-source]')?.getAttribute('data-narrative-source'),
    ).toBe('TEMPLATE');
    // Same text, different presentation: the badge and provenance line differ.
    expect(llm.textContent).not.toEqual(template.textContent);
  });

  it('does not claim a fallback the API does not report', () => {
    const { container } = render(<NarrativeBlock source="TEMPLATE" text="Template prose." />);

    expect(container.textContent).toMatch(/does not distinguish/i);
    expect(container.textContent?.toLowerCase()).not.toMatch(/fell back|fallback|model failed/);
  });
});

describe('LabelChip', () => {
  it('never renders a verdict without its source and stratum', () => {
    render(<LabelChip label={label({ verdict: 'ANOMALOUS' })} />);
    const chip = screen.getByText(/judged anomalous/i).closest('.label-chip');

    expect(chip).not.toBeNull();
    expect(within(chip as HTMLElement).getByText(/source: dispute feed/i)).toBeInTheDocument();
    expect(within(chip as HTMLElement).getByText(/stratum: flagged pool/i)).toBeInTheDocument();
  });

  it('says whether the reviewer could see the scores', () => {
    const { container: anchored } = render(
      <LabelChip label={label({ source: 'HUMAN_REVIEW', scoresVisible: true })} />,
    );
    const { container: blind } = render(
      <LabelChip label={label({ source: 'HUMAN_REVIEW', scoresVisible: false })} />,
    );

    expect(anchored.textContent).toMatch(/anchored, not independent/i);
    expect(blind.textContent).toMatch(/judged blind/i);
  });

  it('warns that a synthetic label is circular as evidence', () => {
    render(<LabelChip label={label({ source: 'SYNTHETIC', verdict: 'ANOMALOUS' })} />);
    expect(screen.getByText(/circular as evidence about detection quality/i)).toBeInTheDocument();
  });

  it('reports an unlabelled account as an absence of evidence, not a verdict', () => {
    render(<NoLabel />);
    expect(screen.getByText(/absence of evidence, not a verdict of ordinary/i)).toBeInTheDocument();
  });

  it('keeps provenance in the compact list form too', () => {
    const { container } = render(
      <LabelSummary labels={[label({ verdict: 'ANOMALOUS', source: 'SYNTHETIC' })]} />,
    );

    expect(container.textContent).toMatch(/anomalous/i);
    expect(container.textContent).toMatch(/synthetic/i);
    expect(container.textContent).toMatch(/flagged pool/i);
  });

  it('shows the newest of several labels and says there are several', () => {
    const { container } = render(
      <LabelSummary
        labels={[
          label({ id: 'a', verdict: 'BENIGN', observedAt: '2026-01-01T00:00:00Z' }),
          label({ id: 'b', verdict: 'ANOMALOUS', observedAt: '2026-06-01T00:00:00Z' }),
        ]}
      />,
    );
    expect(container.textContent).toMatch(/anomalous/i);
    expect(container.textContent).toMatch(/2 labels/);
  });
});

describe('PopulationContext', () => {
  it('labels the block as context rather than as the model’s reasoning', () => {
    render(<PopulationContext>99th percentile</PopulationContext>);
    expect(screen.getByText(/context — population, not the model’s reasoning/i)).toBeInTheDocument();
  });

  it('keeps a percentile inside the wrapper', () => {
    const { container } = render(
      <FeaturePopulationContext
        attribution={attribution('amountModifiedZ', { value: 9, median: 1, percentile: 0.99 })}
        trainingRows={512}
      />,
    );

    const wrapper = container.querySelector('[data-population-context="true"]');
    expect(wrapper).not.toBeNull();
    expect(wrapper?.textContent).toMatch(/99th percentile/);

    // And nothing outside the wrapper mentions it. Measured on a clone, because
    // detaching a node React still owns corrupts its commit and would make this
    // test fail for a reason that has nothing to do with the claim.
    expect(textOutsidePopulationContext(container)).not.toMatch(/percentile/i);
  });

  it('derives the direction from value and median, which is all the wire carries', () => {
    const { container: above } = render(
      <FeaturePopulationContext
        attribution={attribution('f', { value: 9, median: 1 })}
        trainingRows={10}
      />,
    );
    const { container: below } = render(
      <FeaturePopulationContext
        attribution={attribution('f', { value: 0.5, median: 1 })}
        trainingRows={10}
      />,
    );

    expect(above.textContent).toMatch(/sits above the population median/i);
    expect(below.textContent).toMatch(/sits below the population median/i);
  });
});

describe('SignalShareBar', () => {
  it('names the share as a share and the weight as unfitted', () => {
    const { container } = render(
      <SignalShareBar
        contribution={signal('amount_outlier', {
          fired: true,
          statistic: 7.4,
          score: 0.82,
          contribution: 0.6,
          weight: 0.25,
        })}
      />,
    );

    expect(container.textContent).toMatch(/60% of the score/);
    expect(container.textContent).toMatch(/declaration weight 0.25 \(unfitted, and not the share above\)/);
  });

  it('draws a signal that could not judge rather than dropping it', () => {
    const { container } = render(
      <SignalShareBar
        contribution={signal('refund_reversal_rate', { applicable: false, statistic: null })}
      />,
    );

    expect(container.textContent).toMatch(/could not judge/i);
    expect(container.textContent).toMatch(/absence of evidence, not evidence of absence/i);
  });

  it('does not round a small but non-zero share down to nothing', () => {
    const { container } = render(
      <SignalShareBar contribution={signal('burst', { fired: true, contribution: 0.004 })} />,
    );
    expect(container.textContent).toMatch(/<1% of the score/);
  });
});

describe('caveats', () => {
  it('renders each caveat as its own item rather than a paragraph', () => {
    render(<CaveatList caveats={['First limitation.', 'Second limitation.']} />);
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('states the standing caveat without offering a way to dismiss it', () => {
    const { container } = render(<StandingCaveat />);

    expect(container.textContent).toMatch(/unvalidated/i);
    expect(container.textContent).toMatch(/unfitted judgement/i);
    expect(container.querySelector('button')).toBeNull();
  });
});
