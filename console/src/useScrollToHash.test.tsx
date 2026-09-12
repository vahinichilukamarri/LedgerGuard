import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { useScrollToHash } from './useScrollToHash';

/**
 * A regression test for a bug that shipped and was caught by looking.
 *
 * The first version of this hook scrolled on the next animation frame. It did
 * nothing: while the document is still loading, the browser's own scroll
 * handling runs after an early frame callback and resets the position, so the
 * scroll happened and was immediately undone. It looked exactly like the effect
 * never firing, which is why it took a browser to find.
 *
 * These tests pin the fix rather than the mechanism — that a scroll happens once
 * the document is complete, and that on a still-loading document it waits for
 * `load` instead of scrolling into a layout that is about to be overridden.
 */

function Probe({ ready }: { ready: boolean }) {
  useScrollToHash(ready);
  return <section id="target">target</section>;
}

function renderProbe(ready: boolean, hash = '#target') {
  return render(
    <MemoryRouter initialEntries={[`/accounts/abc${hash}`]}>
      <Probe ready={ready} />
    </MemoryRouter>,
  );
}

let scrollIntoView: ReturnType<typeof vi.fn>;
let readyState: DocumentReadyState;

beforeEach(() => {
  // jsdom does not implement scrollIntoView at all, so it has to be supplied
  // before anything can assert on it.
  scrollIntoView = vi.fn();
  Element.prototype.scrollIntoView = scrollIntoView as unknown as typeof Element.prototype.scrollIntoView;

  readyState = 'complete';
  vi.spyOn(document, 'readyState', 'get').mockImplementation(() => readyState);

  // Frames are run synchronously so the test never waits on a real one.
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    callback(0);
    return 1;
  });
  vi.stubGlobal('cancelAnimationFrame', () => {});
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('useScrollToHash', () => {
  it('scrolls to the fragment once the content is ready', () => {
    renderProbe(true);
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
  });

  it('does nothing until the content is ready', () => {
    const view = renderProbe(false);
    expect(scrollIntoView).not.toHaveBeenCalled();

    // The section exists the whole time; readiness is about the data, not the DOM.
    expect(view.container.querySelector('#target')).not.toBeNull();
  });

  it('does nothing when there is no fragment', () => {
    renderProbe(true, '');
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('waits for load rather than scrolling into a document still loading', () => {
    readyState = 'loading';
    renderProbe(true);

    // This is the bug: scrolling here would be undone by the browser's own
    // scroll handling a moment later.
    expect(scrollIntoView).not.toHaveBeenCalled();

    window.dispatchEvent(new Event('load'));
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
  });

  it('does not scroll after unmount, even if load arrives later', () => {
    readyState = 'loading';
    const view = renderProbe(true);
    view.unmount();

    window.dispatchEvent(new Event('load'));
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('ignores a fragment naming nothing on the page', () => {
    expect(() => renderProbe(true, '#does-not-exist')).not.toThrow();
    expect(scrollIntoView).not.toHaveBeenCalled();
  });
});
