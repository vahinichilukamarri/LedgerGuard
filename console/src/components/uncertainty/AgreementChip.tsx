import { displayAgreement, type AgreementInputs } from '../../api/agreement';

/**
 * The four-way agreement state, never collapsed into one indicator.
 *
 * <h2>What this component refuses to be</h2>
 *
 * The obvious component here is a badge that says "flagged". It would fit the
 * table, it would sort, and it would undo Phase 9's central decision in a single
 * span: the two scores were kept apart because disagreement is the most
 * informative thing the pair produces, and a chip that reads "flagged" for
 * BOTH_ELEVATED and for ML_ONLY alike has averaged the prose after the backend
 * refused to average the numbers.
 *
 * So there is no combined indicator anywhere in the console, and this chip
 * always renders three things at once: which layers are elevated, the enum value
 * the API returned, and the sub-state distinction where there is one.
 *
 * <h2>Readable without colour</h2>
 *
 * The category hue is the least of the encodings here. The layer pills state
 * elevation in words, the qualifier states the sub-state in words, and the base
 * enum is printed literally. Turn the stylesheet off and the chip still says
 * everything it says.
 */
export function AgreementChip({
  input,
  compact = false,
}: {
  input: AgreementInputs;
  compact?: boolean;
}) {
  const view = displayAgreement(input);

  return (
    <div className={`agreement agreement-${view.tone}`} data-state={view.state}>
      <div className="agreement-head">
        <span className="agreement-label">{view.label}</span>
        {view.qualifier && <span className="agreement-qualifier">{view.qualifier}</span>}
        <span className="agreement-base">{view.base ?? 'no model'}</span>
      </div>

      <div className="layer-pills">
        <span className={`layer-pill${view.statisticalElevated ? ' elevated' : ''}`}>
          statistical: {view.statisticalElevated ? 'elevated' : 'not elevated'}
        </span>
        <span className={`layer-pill${view.modelElevated ? ' elevated' : ''}`}>
          model: {view.base === null ? 'none trained' : view.modelElevated ? 'elevated' : 'not elevated'}
        </span>
      </div>

      {!compact && <p className="agreement-reading">{view.reading}</p>}
    </div>
  );
}
