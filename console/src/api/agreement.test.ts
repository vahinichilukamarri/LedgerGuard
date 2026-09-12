import { describe, expect, it } from 'vitest';
import { displayAgreement, type DisplayState } from './agreement';

/**
 * The derivation, tested apart from any DOM.
 *
 * These eight states are the console's central claim about the data, and the
 * risk they carry is that a refinement of the API's four-way enum quietly
 * becomes an invention. So each case asserts both the state and that the base
 * enum survives it.
 */
describe('displayAgreement', () => {
  it('splits BOTH_ELEVATED on whether the layers share an axis', () => {
    const shared = displayAgreement({
      agreement: 'BOTH_ELEVATED',
      corroborated: true,
      modelDriversOutsideView: [],
    });
    const unrelated = displayAgreement({
      agreement: 'BOTH_ELEVATED',
      corroborated: false,
      modelDriversOutsideView: [],
    });

    expect(shared.state).toBe<DisplayState>('BOTH_ELEVATED_SHARED_AXIS');
    expect(unrelated.state).toBe<DisplayState>('BOTH_ELEVATED_UNRELATED');

    // The distinction is the point: two elevated scores on unrelated evidence
    // must not read as corroboration.
    expect(unrelated.qualifier).toMatch(/unrelated/i);
    expect(unrelated.reading).toMatch(/nothing here corroborates anything/i);
    expect(shared.qualifier).not.toEqual(unrelated.qualifier);
  });

  it('calls a fired-but-uncarried signal dilution, not disagreement', () => {
    const diluted = displayAgreement({
      agreement: 'ML_ONLY',
      corroborated: true,
      modelDriversOutsideView: ['log10SecondsSinceLastPayment'],
    });

    expect(diluted.state).toBe<DisplayState>('ML_ONLY_DILUTED');
    expect(diluted.qualifier).toMatch(/diluted/i);
    expect(diluted.reading).toMatch(/not disagreeing/i);
  });

  it('separates a model-only finding outside the statistical view from one inside it', () => {
    const outside = displayAgreement({
      agreement: 'ML_ONLY',
      corroborated: false,
      modelDriversOutsideView: ['log10SecondsSinceLastPayment'],
    });
    const sameAxis = displayAgreement({
      agreement: 'ML_ONLY',
      corroborated: false,
      modelDriversOutsideView: [],
    });

    expect(outside.state).toBe<DisplayState>('ML_ONLY_OUTSIDE_VIEW');
    expect(outside.reading).toContain('log10SecondsSinceLastPayment');
    expect(sameAxis.state).toBe<DisplayState>('ML_ONLY_SAME_AXIS');
  });

  it('never describes a quiet account as clear', () => {
    const quiet = displayAgreement({
      agreement: 'BOTH_QUIET',
      corroborated: false,
      modelDriversOutsideView: [],
    });

    expect(quiet.state).toBe<DisplayState>('BOTH_QUIET');
    expect(quiet.reading).toMatch(/not a clean bill of health/i);
    expect(quiet.reading).not.toMatch(/\b(safe|clear|fine|benign)\b/i);
  });

  it('treats an absent model as an absent second opinion, never as a quiet one', () => {
    const none = displayAgreement({
      agreement: null,
      corroborated: false,
      modelDriversOutsideView: [],
    });

    expect(none.state).toBe<DisplayState>('NO_MODEL');
    expect(none.modelElevated).toBe(false);
    expect(none.reading).toMatch(/absence of a second opinion/i);
    expect(none.base).toBeNull();
  });

  it.each([
    ['BOTH_QUIET', false, false],
    ['STATISTICAL_ONLY', true, false],
    ['BOTH_ELEVATED', true, true],
    ['ML_ONLY', false, true],
  ] as const)('reports layer elevation for %s as (%s, %s)', (agreement, statistical, model) => {
    const view = displayAgreement({ agreement, corroborated: false, modelDriversOutsideView: [] });
    expect(view.statisticalElevated).toBe(statistical);
    expect(view.modelElevated).toBe(model);
    // The enum the API returned is always carried through unchanged.
    expect(view.base).toBe(agreement);
  });

  it('never labels any state with a single combined word', () => {
    const states = (
      [
        { agreement: 'BOTH_QUIET', corroborated: false, modelDriversOutsideView: [] },
        { agreement: 'STATISTICAL_ONLY', corroborated: false, modelDriversOutsideView: [] },
        { agreement: 'BOTH_ELEVATED', corroborated: true, modelDriversOutsideView: [] },
        { agreement: 'BOTH_ELEVATED', corroborated: false, modelDriversOutsideView: [] },
        { agreement: 'ML_ONLY', corroborated: true, modelDriversOutsideView: [] },
        { agreement: 'ML_ONLY', corroborated: false, modelDriversOutsideView: ['x'] },
        { agreement: 'ML_ONLY', corroborated: false, modelDriversOutsideView: [] },
        { agreement: null, corroborated: false, modelDriversOutsideView: [] },
      ] as const
    ).map((input) => displayAgreement(input));

    for (const view of states) {
      expect(view.label.toLowerCase()).not.toMatch(/\b(flagged|risk|suspicious|anomalous)\b/);
    }

    // Eight inputs, eight distinct rendered states: nothing is collapsed.
    expect(new Set(states.map((view) => view.state)).size).toBe(8);
  });
});
