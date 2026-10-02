import { useId, type PointerEvent as ReactPointerEvent } from 'react';
import { useInView } from '../motion/hooks';
import { compact, niceMax, useTip } from './useTip';

export interface AreaPoint {
  t: number;
  y: number;
}

const W = 640;
const H = 230;
const PAD = { l: 40, r: 14, t: 14, b: 26 };

const shortDate = (t: number) => new Date(t).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
const clock = (t: number) => new Date(t).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
const clockSeconds = (t: number) =>
  new Date(t).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const stamp = (t: number) => `${shortDate(t)}, ${clockSeconds(t)}`;

/**
 * A single-series area/line over time, with a snapping crosshair. One series,
 * so no legend box: the card title names it. The line draws itself in on first
 * sight; the fill is a wash, never a block.
 */
export function AreaChart({
  points,
  unit,
  format,
  color = 'var(--accent)',
}: {
  points: AreaPoint[];
  unit: string;
  format?: (t: number) => string;
  color?: string;
}) {
  const gradient = useId();
  const [ref, seen] = useInView<HTMLDivElement>();
  const { wrap, show, hide, node } = useTip();

  if (points.length < 2) {
    return <p className="chart-empty">Not enough activity yet to draw a trend.</p>;
  }

  const first = points[0]!;
  const last = points[points.length - 1]!;
  const t0 = first.t;
  const t1 = last.t;
  const span = Math.max(1, t1 - t0);
  // A window inside minutes reads as clock times with seconds, inside a day and a half as clock times, longer as dates.
  // Tooltips always carry both date and time.
  const axis = format ?? (span < 20 * 60_000 ? clockSeconds : span < 36 * 3_600_000 ? clock : shortDate);
  const top = niceMax(Math.max(...points.map((p) => p.y)));
  const x = (t: number) => PAD.l + ((t - t0) / span) * (W - PAD.l - PAD.r);
  const y = (v: number) => H - PAD.b - (v / top) * (H - PAD.t - PAD.b);

  const line = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.t).toFixed(1)} ${y(p.y).toFixed(1)}`).join(' ');
  const area = `${line} L${x(t1).toFixed(1)} ${H - PAD.b} L${x(t0).toFixed(1)} ${H - PAD.b} Z`;
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => top * f);
  const xTicks = [0, 1 / 3, 2 / 3, 1].map((f) => t0 + span * f);

  // The crosshair position is written straight to CSS variables so hovering never re-renders the path.
  function setHover(p: AreaPoint | null) {
    const el = wrap.current;
    if (!el) {
      return;
    }
    if (p) {
      el.style.setProperty('--cx', `${(x(p.t) / W) * 100}%`);
      el.style.setProperty('--cy', `${(y(p.y) / H) * 100}%`);
      el.dataset.hover = 'true';
    } else {
      delete el.dataset.hover;
    }
  }

  function onMove(event: ReactPointerEvent<SVGSVGElement>) {
    const box = event.currentTarget.getBoundingClientRect();
    const px = ((event.clientX - box.left) / box.width) * W;
    const t = t0 + ((px - PAD.l) / (W - PAD.l - PAD.r)) * span;
    let best = first;
    for (const p of points) {
      if (Math.abs(p.t - t) < Math.abs(best.t - t)) {
        best = p;
      }
    }
    show(event, (
      <>
        <strong>{best.y.toLocaleString()}</strong>
        <span>{unit}</span>
        <em>{format ? format(best.t) : stamp(best.t)}</em>
      </>
    ));
    setHover(best);
  }

  return (
    <div ref={ref}>
      <div ref={wrap} className={`area-chart${seen ? ' area-in' : ''}`} style={{ color }}>
        <svg
          viewBox={`0 0 ${W} ${H}`}
          role="img"
          aria-label={`${unit} over time, from ${stamp(t0)} to ${stamp(t1)}, ending at ${last.y}`}
          onPointerMove={onMove}
          onPointerLeave={() => {
            hide();
            setHover(null);
          }}
        >
          <defs>
            <linearGradient id={gradient} x1="0" x2="0" y1="0" y2="1">
              <stop offset="0" stopColor="currentColor" stopOpacity="0.28" />
              <stop offset="1" stopColor="currentColor" stopOpacity="0" />
            </linearGradient>
          </defs>
          {ticks.map((v) => (
            <g key={v}>
              <line className="grid" x1={PAD.l} x2={W - PAD.r} y1={y(v)} y2={y(v)} />
              <text className="tick" x={PAD.l - 8} y={y(v) + 3.5} textAnchor="end">
                {compact(v)}
              </text>
            </g>
          ))}
          {xTicks.map((t, i) => (
            <text
              key={t}
              className="tick"
              x={x(t)}
              y={H - 6}
              textAnchor={i === 0 ? 'start' : i === xTicks.length - 1 ? 'end' : 'middle'}
            >
              {axis(t)}
            </text>
          ))}
          <path className="area-fill" d={area} fill={`url(#${gradient})`} />
          <path className="area-line" d={line} pathLength={1} />
          <circle className="area-end" cx={x(last.t)} cy={y(last.y)} r="4.5" />
        </svg>
        <span className="crosshair" aria-hidden="true" />
        <span className="crosshair-dot" aria-hidden="true" />
        {node}
      </div>
    </div>
  );
}
