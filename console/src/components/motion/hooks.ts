import { useEffect, useState } from 'react';

/**
 * Motion is decoration, never information: every animated figure lands on the
 * exact number the data holds, and every hook here collapses to "already
 * there" when the visitor asks for reduced motion, when the browser has no
 * IntersectionObserver, and under the test runner — so a test never has to
 * wait for a tween to finish before it can read a value.
 */
export function motionAllowed(): boolean {
  if (import.meta.env.MODE === 'test') {
    return false;
  }
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return false;
  }
  return !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * True once the element has scrolled into view (and stays true).
 *
 * Returns a callback ref rather than an object ref on purpose: charts render a
 * placeholder while their data is loading and the real element afterwards, so
 * the node this observes changes over the component's life. A callback ref
 * re-attaches the observer whenever it does; an object ref would have stayed
 * pointed at the first (absent) node.
 */
export function useInView<T extends Element>(rootMargin = '0px 0px -8% 0px'): [(node: T | null) => void, boolean] {
  const [node, setNode] = useState<T | null>(null);
  const [seen, setSeen] = useState(() => !motionAllowed() || typeof IntersectionObserver === 'undefined');

  useEffect(() => {
    if (seen || !node) {
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setSeen(true);
          observer.disconnect();
        }
      },
      { rootMargin },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, [seen, node, rootMargin]);

  return [setNode, seen];
}

/** Eases a number from 0 to `target` once `active`; returns `target` outright when motion is off. */
export function useCountUp(target: number, active = true, duration = 900): number {
  const [value, setValue] = useState(() => (motionAllowed() ? 0 : target));

  useEffect(() => {
    if (!motionAllowed()) {
      setValue(target);
      return;
    }
    if (!active) {
      return;
    }
    let frame = 0;
    const start = performance.now();
    const tick = (now: number) => {
      // A frame timestamp can precede `start` by a hair; clamp both ends so the value is never negative (or "-0").
      const t = Math.max(0, Math.min(1, (now - start) / duration));
      const eased = 1 - Math.pow(1 - t, 3);
      setValue(Math.max(0, target * eased));
      if (t < 1) {
        frame = requestAnimationFrame(tick);
      }
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [target, active, duration]);

  return value;
}

export type Theme = 'light' | 'dark';
const THEME_KEY = 'ledgerguard.theme';

function systemTheme(): Theme {
  return typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
    ? 'dark'
    : 'light';
}

/** The viewer's theme: their stored choice if any, otherwise the OS setting. Stamped on <html data-theme>. */
export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(() => {
    try {
      const stored = localStorage.getItem(THEME_KEY);
      if (stored === 'light' || stored === 'dark') {
        return stored;
      }
    } catch {
      /* storage can be blocked; fall through to the OS setting */
    }
    return systemTheme();
  });

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  function toggle() {
    setTheme((current) => {
      const next: Theme = current === 'dark' ? 'light' : 'dark';
      try {
        localStorage.setItem(THEME_KEY, next);
      } catch {
        /* non-fatal */
      }
      return next;
    });
  }

  return [theme, toggle];
}
