import { useState } from 'react';
import { useInView } from '../motion/hooks';

export interface DonutDatum {
  key: string;
  label: string;
  value: number;
  color: string;
}

/**
 * A ring, with the legend beside it. The centre reads the total until a
 * segment (or its legend row) is hovered, then that segment. Segments are
 * separated by a real gap rather than a stroke, and draw in clockwise the
 * first time the chart is seen.
 */
export function Donut({
  data,
  centerLabel,
  emptyLabel = 'Nothing to show',
}: {
  data: DonutDatum[];
  centerLabel: string;
  emptyLabel?: string;
}) {
  const [ref, seen] = useInView<HTMLDivElement>();
  const [active, setActive] = useState<string | null>(null);
  const shown = data.filter((d) => d.value > 0);
  const total = shown.reduce((sum, d) => sum + d.value, 0);
  const focus = shown.find((d) => d.key === active);
  const gap = shown.length > 1 ? 1.2 : 0;

  let offset = 0;
  const arcs = shown.map((d) => {
    const share = (d.value / total) * 100;
    const arc = { ...d, offset, len: Math.max(0, share - gap) };
    offset += share;
    return arc;
  });

  return (
    <div ref={ref} className="donut">
      <div className="donut-ring">
        <svg viewBox="0 0 120 120" role="img" aria-label={`${centerLabel}: ${total}`}>
          <circle className="donut-track" cx="60" cy="60" r="46" pathLength={100} />
          <g transform="rotate(-90 60 60)">
            {arcs.map((arc) => (
              <circle
                key={arc.key}
                className={`donut-seg${active && active !== arc.key ? ' donut-dim' : ''}`}
                cx="60"
                cy="60"
                r="46"
                pathLength={100}
                stroke={arc.color}
                strokeDasharray={seen ? `${arc.len} ${100 - arc.len}` : '0 100'}
                strokeDashoffset={-arc.offset - gap / 2}
                onPointerEnter={() => setActive(arc.key)}
                onPointerLeave={() => setActive(null)}
              />
            ))}
          </g>
        </svg>
        <div className="donut-center">
          {total === 0 ? (
            <span className="donut-empty">{emptyLabel}</span>
          ) : (
            <>
              <span className="donut-value">{focus ? focus.value : total}</span>
              <span className="donut-label">{focus ? focus.label : centerLabel}</span>
            </>
          )}
        </div>
      </div>
      <ul className="legend">
        {data.map((d) => (
          <li
            key={d.key}
            className={`legend-row${active === d.key ? ' legend-active' : ''}${d.value === 0 ? ' legend-zero' : ''}`}
            onPointerEnter={() => d.value > 0 && setActive(d.key)}
            onPointerLeave={() => setActive(null)}
          >
            <span className="legend-swatch" style={{ background: d.color }} aria-hidden="true" />
            <span className="legend-name">{d.label}</span>
            <span className="legend-value num">{d.value}</span>
            <span className="legend-pct num">{total > 0 ? `${Math.round((d.value / total) * 100)}%` : '—'}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
