import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';

/**
 * Make `#narrative` and friends actually work.
 *
 * The browser resolves a fragment at navigation time, and at navigation time
 * this app has rendered nothing: the section the fragment names does not exist
 * until the explanation query resolves. So a link straight to an account's
 * narrative lands at the top of the page, which is the wrong place and looks
 * like a broken link.
 *
 * `ready` is the caller's signal that the content has rendered — normally a
 * query's success flag.
 *
 * <h2>Why this waits for `load` rather than for a frame</h2>
 *
 * The obvious implementation schedules one `requestAnimationFrame` past the
 * commit and scrolls there. It does not work, and the reason is worth recording:
 * while the document is still loading, the browser performs its own scroll
 * handling — anchor resolution and scroll restoration — and that runs *after* an
 * early frame callback, resetting the position to the top. The scroll happens
 * and is then undone, which looks exactly like the hook never firing.
 *
 * So when the document has not finished loading, this waits for `load` first,
 * and only then takes a frame to let layout settle. Once the document is
 * complete — every in-app navigation — a frame is all it needs.
 */
export function useScrollToHash(ready: boolean): void {
  const { hash } = useLocation();

  useEffect(() => {
    if (!ready || !hash) {
      return;
    }

    const id = decodeURIComponent(hash.slice(1));
    let frame = 0;
    let cancelled = false;

    const scroll = () => {
      if (cancelled) {
        return;
      }
      frame = requestAnimationFrame(() => {
        document.getElementById(id)?.scrollIntoView({ block: 'start' });
      });
    };

    if (document.readyState === 'complete') {
      scroll();
      return () => {
        cancelled = true;
        cancelAnimationFrame(frame);
      };
    }

    window.addEventListener('load', scroll, { once: true });
    return () => {
      cancelled = true;
      cancelAnimationFrame(frame);
      window.removeEventListener('load', scroll);
    };
  }, [ready, hash]);
}
