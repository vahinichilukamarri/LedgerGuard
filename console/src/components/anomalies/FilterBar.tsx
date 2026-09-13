import type { DisplayState } from '../../api/agreement';

/**
 * The filters, split by where they are applied.
 *
 * The first two go to the API. The rest are applied in the browser, because the
 * endpoint offers no parameters for them. Saying which is which matters: a
 * reviewer who narrows to `ML_ONLY` and sees eight rows should know they are
 * eight rows out of the ranking the server sent, not eight rows out of the
 * ledger.
 *
 * <h2>Toggle chips, not a native multiple-select</h2>
 *
 * A `<select multiple>` needs a modifier key most people don't know to hold, is
 * rendered by the OS rather than the page, and looks broken next to everything
 * around it. A set of checkboxes styled as toggle chips does the identical job —
 * `states` is still an array of the values checked — legibly, and it is
 * discoverable without documentation.
 */

export const STATE_FILTERS: Array<{ value: DisplayState; label: string }> = [
  { value: 'BOTH_ELEVATED_SHARED_AXIS', label: 'Both elevated — shared axis' },
  { value: 'BOTH_ELEVATED_UNRELATED', label: 'Both elevated — unrelated evidence' },
  { value: 'STATISTICAL_ONLY', label: 'Statistical only' },
  { value: 'ML_ONLY_DILUTED', label: 'Model only — diluted' },
  { value: 'ML_ONLY_OUTSIDE_VIEW', label: 'Model only — outside statistical view' },
  { value: 'ML_ONLY_SAME_AXIS', label: 'Model only — shared axes' },
  { value: 'BOTH_QUIET', label: 'Neither elevated' },
  { value: 'NO_MODEL', label: 'No model trained' },
];

export function FilterBar({
  minScore,
  includeMlOnly,
  search,
  states,
  onChange,
}: {
  minScore: number;
  includeMlOnly: boolean;
  search: string;
  states: DisplayState[];
  onChange: (patch: {
    minScore?: number;
    includeMlOnly?: boolean;
    search?: string;
    states?: DisplayState[];
  }) => void;
}) {
  function toggleState(value: DisplayState) {
    const next = states.includes(value)
      ? states.filter((candidate) => candidate !== value)
      : [...states, value];
    onChange({ states: next });
  }

  return (
    <div className="toolbar">
      <label className="field">
        <span className="field-label">Minimum composite</span>
        <input
          type="number"
          min={0}
          max={1}
          step={0.05}
          value={minScore}
          aria-describedby="minscore-note"
          onChange={(event) => {
            const parsed = Number(event.target.value);
            if (!Number.isNaN(parsed)) {
              onChange({ minScore: parsed });
            }
          }}
        />
        <span id="minscore-note" className="field-hint">
          sent to the API · 0.50 is the stated convention
        </span>
      </label>

      <label className="field">
        <span className="field-label">Account search</span>
        <input
          type="search"
          value={search}
          placeholder="Account id contains…"
          onChange={(event) => onChange({ search: event.target.value })}
        />
        <span className="field-hint">applied in the browser</span>
      </label>

      <label className="field toolbar-checkbox">
        <input
          type="checkbox"
          checked={includeMlOnly}
          onChange={(event) => onChange({ includeMlOnly: event.target.checked })}
        />
        Include accounts only the model flags
      </label>

      <fieldset className="chip-filter field">
        <legend>How the layers relate</legend>
        <div className="chip-group" role="group" aria-label="Filter by how the layers relate">
          {STATE_FILTERS.map((option) => {
            const active = states.includes(option.value);
            return (
              <label key={option.value} className={`chip${active ? ' chip-active' : ''}`}>
                <input
                  type="checkbox"
                  checked={active}
                  onChange={() => toggleState(option.value)}
                />
                {option.label}
              </label>
            );
          })}
        </div>
        <span className="field-hint">applied in the browser · none selected means all</span>
      </fieldset>
    </div>
  );
}
