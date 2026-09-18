/**
 * A category pill for an enum-like field that carries no severity meaning —
 * `PaymentStatus`, `IncidentStatus`, `SettlementStatus`, `PostingType`. Same
 * tone system as the detection console's agreement chips: hue says *which*
 * value, never how alarming it is.
 *
 * Never accepts a `Severity`. That badge is `SeverityBadge`, deliberately a
 * separate component, because reconciliation `Severity` is the one field in
 * this system allowed a real severity palette.
 */
export type StatusTone = 'neutral' | 'positive' | 'caution' | 'info';

export function StatusPill({ label, tone = 'neutral' }: { label: string; tone?: StatusTone }) {
  return <span className={`status-pill status-pill-${tone}`}>{label}</span>;
}
