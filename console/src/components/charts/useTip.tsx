import { useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react';

interface Tip {
  x: number;
  y: number;
  content: ReactNode;
}

/**
 * One hover readout per chart. Positions are relative to the chart's own
 * wrapper so the tooltip follows the pointer without leaving its card, and
 * flips to the left of the pointer near the right edge instead of clipping.
 * Content is always React nodes (text), never injected markup.
 */
export function useTip() {
  const wrap = useRef<HTMLDivElement>(null);
  const [tip, setTip] = useState<Tip | null>(null);

  function show(event: ReactPointerEvent | { clientX: number; clientY: number }, content: ReactNode) {
    const box = wrap.current?.getBoundingClientRect();
    if (!box) {
      return;
    }
    setTip({ x: event.clientX - box.left, y: event.clientY - box.top, content });
  }

  const node = tip ? (
    <div
      className={`chart-tip${tip.x > (wrap.current?.clientWidth ?? 0) * 0.6 ? ' chart-tip-left' : ''}`}
      style={{ left: tip.x, top: tip.y }}
      role="status"
    >
      {tip.content}
    </div>
  ) : null;

  return { wrap, show, hide: () => setTip(null), node };
}

/** Rounds an axis maximum up to a clean 1 / 2 / 5 × 10ⁿ. */
export function niceMax(value: number): number {
  if (value <= 0) {
    return 1;
  }
  const exp = Math.pow(10, Math.floor(Math.log10(value)));
  const f = value / exp;
  const nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
  return nice * exp;
}

export function compact(value: number): string {
  return Math.abs(value) >= 1000
    ? new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(value)
    : String(Math.round(value * 100) / 100);
}
