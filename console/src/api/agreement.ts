import type { AgreementState } from './types';

/**
 * The agreement state as the console renders it, which is finer than the enum.
 *
 * `Agreement` has four values. Two of them cover pairs of situations that mean
 * materially different things, and the backend already publishes enough to tell
 * them apart — so collapsing them here would be the console throwing away
 * information the API went to the trouble of exposing.
 *
 * <ul>
 *   <li><b>BOTH_ELEVATED</b> splits on `corroborated`. `Reconciliation.java`
 *       calls the false case the hardest one in the system: two elevated scores
 *       resting on unrelated evidence, which looks like corroboration in the
 *       numbers and is not.</li>
 *   <li><b>ML_ONLY</b> splits three ways. If a signal did fire but the composite
 *       stayed under the line, the layers are not disagreeing at all — that is
 *       dilution, and calling it a disagreement would misreport it. Otherwise it
 *       matters whether the model isolated on axes the statistical layer cannot
 *       see, or on axes it shares and read differently.</li>
 * </ul>
 *
 * Nothing here is inferred beyond what the fields say. Each branch is a
 * restatement of a condition `Reconciliation.narrate` already switches on.
 */
export type DisplayState =
  | 'NO_MODEL'
  | 'BOTH_QUIET'
  | 'STATISTICAL_ONLY'
  | 'BOTH_ELEVATED_SHARED_AXIS'
  | 'BOTH_ELEVATED_UNRELATED'
  | 'ML_ONLY_DILUTED'
  | 'ML_ONLY_OUTSIDE_VIEW'
  | 'ML_ONLY_SAME_AXIS';

export interface DisplayAgreement {
  state: DisplayState;
  /** The enum value, always shown, so the console's finer reading never hides the API's. */
  base: AgreementState | null;
  /** Short heading. Never the word "flagged": the whole point is that the states differ. */
  label: string;
  /** The distinction the sub-state exists to draw. Empty where there is no sub-state. */
  qualifier: string;
  /** A sentence a reviewer can act on, in the API's own terms. */
  reading: string;
  statisticalElevated: boolean;
  modelElevated: boolean;
  /** Category hue token. Encodes *which* state, never *how bad*. */
  tone: 'neutral' | 'statistical' | 'model' | 'both';
}

export interface AgreementInputs {
  agreement: AgreementState | null;
  corroborated: boolean;
  modelDriversOutsideView: string[];
}

export function displayAgreement(input: AgreementInputs): DisplayAgreement {
  const { agreement, corroborated, modelDriversOutsideView } = input;

  if (agreement === null) {
    return {
      state: 'NO_MODEL',
      base: null,
      label: 'No model trained',
      qualifier: 'One layer only',
      reading:
        'There is no isolation score to compare the composite against. This is an absence of a ' +
        'second opinion, not a second opinion that found nothing.',
      statisticalElevated: false,
      modelElevated: false,
      tone: 'neutral',
    };
  }

  switch (agreement) {
    case 'BOTH_QUIET':
      return {
        state: 'BOTH_QUIET',
        base: agreement,
        label: 'Neither layer elevated',
        qualifier: '',
        reading:
          'Quiet in both layers is the weakest claim either can make, not a clean bill of health.',
        statisticalElevated: false,
        modelElevated: false,
        tone: 'neutral',
      };

    case 'STATISTICAL_ONLY':
      return {
        state: 'STATISTICAL_ONLY',
        base: agreement,
        label: 'Statistical layer only',
        qualifier: '',
        reading:
          'Unusual against this account’s own history and ordinary across the training ' +
          'population is the ordinary reading. The other reading is that the model’s features ' +
          'do not capture what the signals caught.',
        statisticalElevated: true,
        modelElevated: false,
        tone: 'statistical',
      };

    case 'BOTH_ELEVATED':
      return corroborated
        ? {
            state: 'BOTH_ELEVATED_SHARED_AXIS',
            base: agreement,
            label: 'Both layers elevated',
            qualifier: 'on a shared axis',
            reading:
              'The two layers point at the same behaviour. This is the only real corroboration ' +
              'available here, and it may still cover only part of the model’s reasoning.',
            statisticalElevated: true,
            modelElevated: true,
            tone: 'both',
          }
        : {
            state: 'BOTH_ELEVATED_UNRELATED',
            base: agreement,
            label: 'Both layers elevated',
            qualifier: 'on unrelated evidence',
            reading:
              'Agreeing that something is unusual is not the same as agreeing on what. No ' +
              'statistical signal shares an axis with anything the model isolated on, so nothing ' +
              'here corroborates anything.',
            statisticalElevated: true,
            modelElevated: true,
            tone: 'both',
          };

    case 'ML_ONLY':
      if (corroborated) {
        return {
          state: 'ML_ONLY_DILUTED',
          base: agreement,
          label: 'Model only',
          qualifier: 'but a signal did fire — diluted, not disagreeing',
          reading:
            'A statistical signal fired on an axis the model also isolated on, and the composite ' +
            'still stayed under the 0.50 convention. On that axis the layers are not disagreeing; ' +
            'the statistical score simply did not carry it over the line.',
          statisticalElevated: false,
          modelElevated: true,
          tone: 'model',
        };
      }
      if (modelDriversOutsideView.length > 0) {
        return {
          state: 'ML_ONLY_OUTSIDE_VIEW',
          base: agreement,
          label: 'Model only',
          qualifier: 'on axes the statistical layer cannot see',
          reading:
            'The statistical layer does not measure ' +
            modelDriversOutsideView.join(', ') +
            ' at all, so there is nothing in the composite to corroborate or contradict this.',
          statisticalElevated: false,
          modelElevated: true,
          tone: 'model',
        };
      }
      return {
        state: 'ML_ONLY_SAME_AXIS',
        base: agreement,
        label: 'Model only',
        qualifier: 'on axes both layers share',
        reading:
          'Every axis the model isolated on is one some statistical signal also looks at, so the ' +
          'two layers are disagreeing about the same evidence rather than seeing different evidence.',
        statisticalElevated: false,
        modelElevated: true,
        tone: 'model',
      };
  }
}
