import type { DisplayState } from '../../api/agreement';

/**
 * The filters, split by where they are applied.
 *
 * The first two go to the API. The rest are applied in the browser, because the
 * endpoint offers no parameters for them. Saying which is which matters: a
 * reviewer who narrows to `ML_ONLY` and sees eight rows should know they are
 * eight rows out of the ranking the server sent, not eight rows out of the
 * ledger.
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
  return (
    <div className="filters">
      <label>
        Minimum statistical composite
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
        <span id="minscore-note" className="th-note">
          sent to the API · 0.50 is the stated convention
        </span>
      </label>

      <label className="checkbox">
        <input
          type="checkbox"
          checked={includeMlOnly}
          onChange={(event) => onChange({ includeMlOnly: event.target.checked })}
        />
        Include accounts only the model flags
        <span className="th-note">sent to the API · these are the rows Phase 9 exists to surface</span>
      </label>

      <label>
        Account id contains
        <input
          type="search"
          value={search}
          placeholder="substring"
          onChange={(event) => onChange({ search: event.target.value })}
        />
        <span className="th-note">applied in the browser</span>
      </label>

      <label>
        How the layers relate
        <select
          multiple
          size={4}
          value={states}
          aria-label="Filter by how the layers relate"
          onChange={(event) =>
            onChange({
              states: Array.from(event.target.selectedOptions).map(
                (option) => option.value as DisplayState,
              ),
            })
          }
        >
          {STATE_FILTERS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <span className="th-note">applied in the browser · none selected means all</span>
      </label>
    </div>
  );
}
