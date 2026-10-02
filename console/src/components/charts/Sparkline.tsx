import { useId } from 'react';

/** A tiny trend line for a stat card. Decorative: the card's number is the information. */
export function Sparkline({ values, color = 'var(--accent)' }: { values: number[]; color?: string }) {
  const id = useId();
  if (values.length < 2) {
    return <div className="spark spark-empty" aria-hidden="true" />;
  }
  const W = 120;
  const H = 34;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const pts = values.map((v, i): [number, number] => [(i / (values.length - 1)) * (W - 6) + 3, H - 4 - ((v - min) / span) * (H - 9)]);
  const line = pts.map(([px, py], i) => `${i === 0 ? 'M' : 'L'}${px.toFixed(1)} ${py.toFixed(1)}`).join(' ');
  const [lx, ly] = pts[pts.length - 1]!;
  return (
    <svg className="spark" viewBox={`0 0 ${W} ${H}`} aria-hidden="true" style={{ color }}>
      <defs>
        <linearGradient id={id} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor="currentColor" stopOpacity="0.25" />
          <stop offset="1" stopColor="currentColor" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`${line} L${W - 3} ${H} L3 ${H} Z`} fill={`url(#${id})`} />
      <path className="spark-line" d={line} pathLength={1} />
      <circle cx={lx} cy={ly} r="2.5" fill="currentColor" />
    </svg>
  );
}
