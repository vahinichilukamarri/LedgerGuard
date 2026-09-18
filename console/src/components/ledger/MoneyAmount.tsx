import { money } from '../../format';

/**
 * One amount, rendered one way, everywhere it appears.
 *
 * Always shown against its currency — `$10.25` alone is not a complete
 * statement once a second currency exists anywhere in the system.
 */
export function MoneyAmount({ amount, currency }: { amount: number; currency: string }) {
  return <span className="num money">{money(amount, currency)}</span>;
}
