import type { Severity } from '../../api/types';

/**
 * The one place in this console a real, alarm-style palette is allowed to
 * reach the screen. `Severity` is a backend-computed enum on reconciliation
 * incidents (`ReconciliationIncident.severity`), not a UI-invented risk
 * scale — see `Severity.java`'s own escalation rules. The prop type is
 * `Severity` and nothing wider, so this cannot be called with a detection
 * score or an agreement state by accident.
 */
const LABELS: Record<Severity, string> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
  CRITICAL: 'Critical',
};

export function SeverityBadge({ severity }: { severity: Severity }) {
  return <span className={`severity-badge severity-${severity.toLowerCase()}`}>{LABELS[severity]}</span>;
}
