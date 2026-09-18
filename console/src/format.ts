/**
 * Formatting for the ledger domain (accounts, transactions, money,
 * reconciliation) — a sibling to `components/uncertainty/format.ts`, which
 * covers the detection domain's own precision rules. `instant` is genuinely
 * shared, so it is re-exported here rather than duplicated.
 */
export { instant } from './components/uncertainty/format';

/**
 * `amount` is already the correctly-rounded major-unit decimal the backend
 * computed from the integer minor units — this only adds the currency symbol,
 * grouping separators and the currency's own fraction-digit convention
 * (`Money.fractionDigits` server-side; `Intl` applies the same rule from the
 * ISO code alone).
 */
export function money(amount: number, currency: string): string {
  try {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount);
  } catch {
    // An unrecognised or malformed code should not have reached the client —
    // the backend validates every currency it stores — but render something
    // rather than throw if it somehow does.
    return `${amount} ${currency}`;
  }
}

/** A UUID, shortened for a table cell. The full id is always available on hover via `title`. */
export function shortId(id: string): string {
  return id.length > 13 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id;
}

/**
 * Minor units to a major-unit number, for the one or two DTOs (reconciliation
 * incidents, settlement records) that publish only `amountMinor` with no
 * pre-computed decimal sibling — everywhere else the backend already sends
 * the decimal rendering and this must not be used to recompute it.
 *
 * Divides by the currency's own fraction-digit convention rather than a fixed
 * 100, because that fixed divisor is exactly wrong for a zero-decimal
 * currency like JPY — the same reason `Money.toMajorUnits` on the backend
 * takes the currency, not just the amount.
 */
export function minorToMajor(minor: number, currency: string): number {
  let fractionDigits = 2;
  try {
    fractionDigits =
      new Intl.NumberFormat('en-US', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ??
      2;
  } catch {
    // Fall back to the conventional two digits for an unrecognised code.
  }
  return minor / 10 ** fractionDigits;
}
