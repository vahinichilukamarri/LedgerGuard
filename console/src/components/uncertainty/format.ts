/**
 * Number and date formatting, in one place.
 *
 * Two rules the whole console follows, both of which are about not implying
 * precision that is not there:
 *
 *  - Scores print to two decimals. The composite is a power mean of five
 *    unfitted weights; a third decimal would be arithmetic noise dressed as
 *    resolution.
 *  - A share prints as a whole percentage, and never as "0%" for something that
 *    contributed. A signal that supplied 0.4% of the score supplied something,
 *    and rounding that to zero says it was silent when it was not.
 */

export function score(value: number): string {
  return value.toFixed(2);
}

export function share(fraction: number): string {
  if (fraction <= 0) {
    return '0%';
  }
  const percent = fraction * 100;
  return percent < 1 ? '<1%' : `${Math.round(percent)}%`;
}

export function statistic(value: number | null): string {
  return value === null ? 'not measurable' : value.toFixed(2);
}

/** A percentile as an ordinal, matching the wording the backend narratives use. */
export function ordinalPercentile(percentile: number): string {
  const rank = Math.round(percentile * 100);
  const suffix =
    rank % 100 >= 11 && rank % 100 <= 13
      ? 'th'
      : rank % 10 === 1
        ? 'st'
        : rank % 10 === 2
          ? 'nd'
          : rank % 10 === 3
            ? 'rd'
            : 'th';
  return `${rank}${suffix}`;
}

/** A signal or feature name as a reviewer should read it, not as the wire spells it. */
export function humanise(wireName: string): string {
  const spaced = wireName
    .replace(/_/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

export function instant(iso: string): string {
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? iso : parsed.toISOString().replace('T', ' ').slice(0, 19) + 'Z';
}

/**
 * Label latency in the largest unit that is still honest.
 *
 * Published by the API in seconds and rendered in days wherever it runs to days,
 * because "5184000 seconds" is a number nobody converts and the gap between the
 * behaviour and the truth about it is the property that makes these labels hard
 * to use.
 */
export function latency(seconds: number): string {
  const abs = Math.abs(seconds);
  if (abs < 90) {
    return `${seconds}s`;
  }
  if (abs < 5400) {
    return `${Math.round(seconds / 60)} min`;
  }
  if (abs < 172800) {
    return `${Math.round(seconds / 3600)} h`;
  }
  return `${Math.round(seconds / 86400)} days`;
}

export function shortAccount(accountId: string): string {
  return accountId.length > 13 ? `${accountId.slice(0, 8)}…${accountId.slice(-4)}` : accountId;
}
