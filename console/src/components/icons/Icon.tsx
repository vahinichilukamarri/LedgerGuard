/**
 * One small, hand-authored line-icon set — not a library. Every icon shares
 * the same 24x24 viewBox, stroke width and `currentColor`, so nothing drifts
 * visually as new names get added, and adding an icon never adds a
 * dependency. Colour is always inherited from the caller (`currentColor`),
 * so an icon in the admin/simulation zone reads amber, an active sidebar
 * link reads accent, and neither can accidentally carry a severity or
 * detection-score meaning of its own.
 */
export type IconName =
  | 'home'
  | 'overview'
  | 'ledger'
  | 'accounts'
  | 'create'
  | 'payments'
  | 'transactions'
  | 'reconciliation'
  | 'anomaly'
  | 'model'
  | 'validation'
  | 'simulation'
  | 'plus'
  | 'arrow-right'
  | 'copy'
  | 'check'
  | 'empty'
  | 'sun'
  | 'moon'
  | 'shield'
  | 'layers'
  | 'activity'
  | 'bolt'
  | 'lock'
  | 'refresh'
  | 'eye'
  | 'github';

const PATHS: Record<IconName, string> = {
  home: 'M4 11.5 12 4l8 7.5M6 10v9h5v-5h2v5h5v-9',
  overview: 'M4 4h7v7H4zM13 4h7v4h-7zM13 11h7v9h-7zM4 14h7v6H4z',
  ledger: 'M6 4h9l3 3v13H6zM9 9h6M9 13h6M9 17h4',
  accounts: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM4.5 20a7.5 7.5 0 0 1 15 0',
  create: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM4.5 20a7.5 7.5 0 0 1 12.6-5.5M18 15v6M15 18h6',
  payments: 'M3 8h18v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8ZM3 8l2-4h14l2 4M7 15h4',
  transactions: 'M7 7h12l-3-3M17 17H5l3 3M4 7h1M19 17h1',
  reconciliation: 'M9 11l2 2 4-5M4 6h16v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6Z',
  anomaly: 'M12 3 2 20h20L12 3ZM12 10v4M12 17h.01',
  model: 'M8 3h8v4H8zM5 9h14v10H5zM9 13h2v2H9zM13 13h2v2h-2z',
  validation: 'M5 4h14v17l-7-3-7 3ZM9 11l2 2 4-4',
  simulation: 'M9 3h6M10 3v5l-5 9a2 2 0 0 0 1.8 3h10.4a2 2 0 0 0 1.8-3l-5-9V3M8 15h8',
  plus: 'M12 5v14M5 12h14',
  'arrow-right': 'M5 12h13M13 6l6 6-6 6',
  copy: 'M9 9h10v10H9zM5 15V5h10v2',
  check: 'M4 12l5 5L20 6',
  empty: 'M4 8l8-4 8 4-8 4-8-4ZM4 8v9l8 4M20 8v9l-8 4M4 8l8 4',
  sun: 'M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4',
  moon: 'M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5Z',
  shield: 'M12 3l8 3v6c0 4.5-3.2 8-8 9-4.8-1-8-4.5-8-9V6l8-3ZM9 12l2 2 4-4',
  layers: 'M12 3 3 8l9 5 9-5-9-5ZM3 13l9 5 9-5M3 17.5l9 5 9-5',
  activity: 'M3 12h4l3-8 4 16 3-8h4',
  bolt: 'M13 2 4 14h7l-1 8 9-12h-7l1-8Z',
  lock: 'M6 11h12v9H6zM8 11V8a4 4 0 0 1 8 0v3',
  refresh: 'M20 11a8 8 0 0 0-14.5-4M4 4v4h4M4 13a8 8 0 0 0 14.5 4M20 20v-4h-4',
  eye: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12ZM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z',
  github: 'M9 19c-4.3 1.4-4.3-2.5-6-3m12 5v-3.5c0-1 .1-1.4-.5-2 2.8-.3 5.5-1.4 5.5-6a4.6 4.6 0 0 0-1.3-3.2 4.2 4.2 0 0 0-.1-3.2s-1.1-.3-3.5 1.3a12.3 12.3 0 0 0-6.2 0C6.5 2.8 5.4 3.1 5.4 3.1a4.2 4.2 0 0 0-.1 3.2A4.6 4.6 0 0 0 4 9.5c0 4.6 2.7 5.7 5.5 6-.6.6-.6 1.2-.5 2V21',
};

export function Icon({ name, className }: { name: IconName; className?: string }) {
  return (
    <svg
      className={`icon${className ? ` ${className}` : ''}`}
      viewBox="0 0 24 24"
      width="1em"
      height="1em"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
