import type { CSSProperties, ReactNode } from 'react';
import { useInView } from '../motion/hooks';
import { compact, niceMax, useTip } from './useTip';

export interface Segment {
  key: string;
  label: string;
  value: number;
  color: string;
}
export interface Column {
  label: string;
  title: string;
  segments: Segment[];
}

const W = 640;
const H = 230;
const PAD = { l: 40, r: 10, t: 14, b: 30 };
const MAX_BAR = 26;

/**
 * Stacked (or single-segment) columns from one baseline. Bars are capped in
 * width so a sparse chart doesn't turn into slabs, rounded only at the free
 * end, separated by a 2px gap, and grow up from the baseline on first sight.
 * Each column is its own hover target and keyboard-focusable.
 */
export function ColumnChart({
  columns,
  unit,
  legend = true,
}: {
  columns: Column[];
  unit: string;
  legend?: boolean;
}) {
  const [ref, seen] = useInView<HTMLDivElement>();
  const { wrap, show, hide, node } = useTip();

  if (columns.length === 0) {
    return <p className="chart-empty">Nothing to plot yet.</p>;
  }

  const totals = columns.map((c) => c.segments.reduce((s, x) => s + x.value, 0));
  const top = niceMax(Math.max(...totals));
  const slot = (W - PAD.l - PAD.r) / columns.length;
  const bar = Math.min(MAX_BAR, slot * 0.6);
  const y = (v: number) => H - PAD.b - (v / top) * (H - PAD.t - PAD.b);
  const ticks = [0, 0.5, 1].map((f) => top * f);
  const keys = Array.from(new Map(columns.flatMap((c) => c.segments).map((s) => [s.key, s])).values());

  const readout = (column: Column): ReactNode => (
    <>
      <em>{column.title}</em>
      {column.segments.map((s) => (
        <span key={s.key} className="tip-row">
          <i style={{ background: s.color }} />
          {s.label} <strong>{s.value.toLocaleString()}</strong>
        </span>
      ))}
    </>
  );

  return (
    <div ref={ref}>
      <div ref={wrap} className={`column-chart${seen ? ' columns-in' : ''}`}>
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`${unit}, ${columns.length} columns`} onPointerLeave={hide}>
          {ticks.map((v) => (
            <g key={v}>
              <line className="grid" x1={PAD.l} x2={W - PAD.r} y1={y(v)} y2={y(v)} />
              <text className="tick" x={PAD.l - 8} y={y(v) + 3.5} textAnchor="end">
                {compact(v)}
              </text>
            </g>
          ))}
          {columns.map((column, i) => {
            const cx = PAD.l + slot * i + slot / 2;
            const lastIndex = column.segments.reduce((acc, s, si) => (s.value > 0 ? si : acc), -1);
            let acc = 0;
            return (
              <g
                key={column.title}
                className="column"
                tabIndex={0}
                style={{ '--i': i } as CSSProperties}
                aria-label={`${column.title}: ${column.segments.map((s) => `${s.label} ${s.value}`).join(', ')}`}
                onPointerMove={(e) => show(e, readout(column))}
                onFocus={(e) => {
                  const b = e.currentTarget.getBoundingClientRect();
                  show({ clientX: b.left + b.width / 2, clientY: b.top }, readout(column));
                }}
                onBlur={hide}
              >
                <rect className="column-hit" x={cx - slot / 2} y={PAD.t} width={slot} height={H - PAD.t - PAD.b} />
                {column.segments.map((s, si) => {
                  const y0 = y(acc);
                  acc += s.value;
                  const y1 = y(acc);
                  const h = Math.max(0, y0 - y1 - (si > 0 ? 2 : 0));
                  return s.value > 0 ? (
                    <rect
                      key={s.key}
                      className="column-bar"
                      x={cx - bar / 2}
                      y={y1}
                      width={bar}
                      height={h}
                      rx={si === lastIndex ? 4 : 0}
                      fill={s.color}
                    />
                  ) : null;
                })}
                <text className="tick" x={cx} y={H - 10} textAnchor="middle">
                  {column.label}
                </text>
              </g>
            );
          })}
        </svg>
        {node}
      </div>
      {legend && keys.length > 1 && (
        <ul className="legend legend-inline">
          {keys.map((k) => (
            <li key={k.key} className="legend-row">
              <span className="legend-swatch" style={{ background: k.color }} aria-hidden="true" />
              <span className="legend-name">{k.label}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
