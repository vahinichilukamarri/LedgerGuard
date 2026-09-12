import { beforeEach, describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AnomaliesPage } from './AnomaliesPage';
import { renderAt } from '../test/render';
import { ACCOUNTS, HttpResponse, http, server } from '../test/server';
import { firstForbidden } from '../test/dom';

/**
 * The ranking, against the mock backend.
 *
 * Every assertion here waits on the query rather than on a tick. `findBy` is the
 * only way content fetched over HTTP is asserted in this file — there is no
 * `await` followed by `getBy`, and no timer anywhere, so nothing in it can pass
 * or fail depending on how fast the machine is.
 */

/** Requests the page actually made, recorded so a test can assert what it did not fetch. */
let requested: string[] = [];

beforeEach(() => {
  requested = [];
  server.events.removeAllListeners();
  server.events.on('request:start', ({ request }) => {
    requested.push(new URL(request.url).pathname + new URL(request.url).search);
  });
});

function renderRanking(url = '/anomalies') {
  return renderAt(<AnomaliesPage />, '/anomalies', url);
}

describe('AnomaliesPage', () => {
  it('shows the ranking with both scores in separate columns', async () => {
    renderRanking();

    // Five fixture rows, each carrying a statistical and an isolation readout.
    expect(await screen.findAllByTestId('score-statistical')).toHaveLength(5);
    expect(screen.getAllByTestId('score-model')).toHaveLength(5);
  });

  it('offers no combined score and no risk column', async () => {
    renderRanking();
    await screen.findAllByTestId('score-statistical');

    const headers = screen.getAllByRole('columnheader').map((header) => header.textContent ?? '');
    const joined = headers.join(' | ').toLowerCase();

    // Combined-score language, not the word "combined" — the one header that
    // uses it says "never combined into one indicator", which is the claim this
    // test is checking for rather than the one it is checking against.
    expect(joined).not.toMatch(/\b(blended|combined score|overall score|total score)\b/);
    expect(joined).not.toMatch(/\brisk\b/);
    // And the header that does exist says outright that the layers stay apart.
    expect(joined).toContain('never combined into one indicator');
  });

  it('calls no explanation endpoint while rendering the list', async () => {
    renderRanking();
    await screen.findAllByTestId('score-statistical');
    await screen.findAllByText(/dispute feed|not labelled|synthetic/i);

    // Phase 11 put narrative generation on the detail endpoint alone. A list that
    // fetched an explanation per row would spend a model call per row to help
    // somebody choose which single row to open.
    expect(requested.filter((url) => url.includes('/explanation'))).toEqual([]);
    expect(requested.filter((url) => url.startsWith('/detection/anomalies'))).toHaveLength(1);
  });

  it('renders a distinguishable state for every agreement case in the ranking', async () => {
    renderRanking();
    await screen.findAllByTestId('score-statistical');

    const states = screen
      .getAllByText(/statistical: (not )?elevated/i)
      .map((pill) => pill.closest('[data-state]')?.getAttribute('data-state'));

    expect(states).toEqual([
      'BOTH_ELEVATED_SHARED_AXIS',
      'BOTH_ELEVATED_UNRELATED',
      'STATISTICAL_ONLY',
      'ML_ONLY_OUTSIDE_VIEW',
      'ML_ONLY_DILUTED',
    ]);
    // Two BOTH_ELEVATED rows and two ML_ONLY rows, none of them collapsed.
    expect(new Set(states).size).toBe(5);
  });

  it('fetches labels for the rendered page and shows their provenance', async () => {
    renderRanking();

    const synthetic = await screen.findByText(/^synthetic/i);
    expect(synthetic.textContent).toMatch(/flagged pool/i);
    expect(synthetic.textContent).toMatch(/anchored/i);

    // One label request per visible row, and no more.
    const labelRequests = requested.filter((url) => url.startsWith('/validation/labels/'));
    expect(labelRequests).toHaveLength(5);
  });

  it('reports an unlabelled account as unlabelled rather than as benign', async () => {
    renderRanking();
    const cells = await screen.findAllByText(/^not labelled$/i);

    expect(cells.length).toBeGreaterThan(0);
    for (const cell of cells) {
      expect(cell.textContent?.toLowerCase()).not.toMatch(/benign|ordinary|clear/);
    }
  });

  it('narrows to a single derived state from the URL', async () => {
    renderRanking('/anomalies?state=ML_ONLY_DILUTED');

    const rows = await screen.findAllByTestId('score-statistical');
    expect(rows).toHaveLength(1);

    const chips = document.querySelectorAll('table [data-state]');
    expect(chips).toHaveLength(1);
    expect(chips[0]?.getAttribute('data-state')).toBe('ML_ONLY_DILUTED');
  });

  it('says an empty result is about the filters, not about the ledger', async () => {
    renderRanking('/anomalies?q=nothing-matches-this');

    const empty = await screen.findByText(/no account in the ranking matches these filters/i);
    expect(empty.textContent).toMatch(/not about the ledger/i);
  });

  it('states that paging and sorting happen in the browser', async () => {
    renderRanking();
    expect(
      await screen.findByText(/the endpoint offers no parameters for them/i),
    ).toBeInTheDocument();
  });

  it('sorts on request without inventing a new ordering claim', async () => {
    renderRanking();
    await screen.findAllByTestId('score-statistical');

    await userEvent.click(screen.getByRole('button', { name: /sort by isolation score/i }));

    const header = screen.getByRole('button', { name: /sort by isolation score/i });
    expect(header.closest('th')).toHaveTextContent('0.60 convention, unfitted');
  });

  it('reports a failed ranking fetch as a failed fetch', async () => {
    server.use(http.get('/detection/anomalies', () => new HttpResponse(null, { status: 500 })));
    renderRanking();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/could not load the ranking/i);
    expect(alert).toHaveTextContent(/not a finding about the data behind it/i);
  });

  it('reports a failed label lookup without calling the account unlabelled', async () => {
    server.use(
      http.get('/validation/labels/:accountId', () => new HttpResponse(null, { status: 503 })),
    );
    renderRanking();

    const cells = await screen.findAllByText(/label lookup failed/i);
    expect(cells.length).toBe(5);
    expect(screen.queryByText(/^not labelled$/i)).not.toBeInTheDocument();
  });

  it('uses no copy the backend forbids its own narratives', async () => {
    renderRanking();
    await screen.findAllByTestId('score-statistical');

    const offending = firstForbidden(document.body.textContent ?? '');
    expect(offending, `rendered copy contains "${offending}"`).toBeUndefined();
  });

  it('keeps the standing read-only note on the page', async () => {
    renderRanking();
    const note = await screen.findByText(/this console is read-only/i);
    expect(note).toHaveTextContent(/labelling happens through the validation api/i);
  });

  it('links each row to its own detail view', async () => {
    renderRanking();
    const links = await screen.findAllByRole('link');
    const hrefs = links.map((link) => link.getAttribute('href'));

    expect(hrefs).toContain(`/accounts/${ACCOUNTS.bothElevated}`);
  });

  it('shows evidence thinness as thinness rather than as a low score', async () => {
    server.use(
      http.get('/detection/anomalies', () =>
        HttpResponse.json([
          {
            accountId: ACCOUNTS.bothQuiet,
            asOf: '2026-09-12T10:00:00Z',
            statisticalScore: 0.55,
            applicableSignals: 1,
            wellEvidenced: false,
            ml: {
              available: false,
              score: null,
              agreement: null,
              unavailableReason: 'no model has been trained',
              model: null,
            },
            signals: [],
            explanation: {
              summary: 'One signal could judge.',
              agreement: null,
              corroborated: false,
              statisticalDrivers: [],
              modelDrivers: [],
              modelDriversOutsideStatisticalView: [],
              detail: '/x',
            },
          },
        ]),
      ),
    );

    renderRanking();
    const row = (await screen.findAllByRole('row'))[1] as HTMLElement;

    expect(row.textContent).toMatch(/1 of 5/);
    expect(row.textContent).toMatch(/thinly evidenced/i);
    expect(row.textContent).toMatch(/never available/i);
  });
});
