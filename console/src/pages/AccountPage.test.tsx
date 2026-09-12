import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AccountPage } from './AccountPage';
import { renderAt } from '../test/render';
import { ACCOUNTS, EXPLANATIONS, HttpResponse, http, server } from '../test/server';
import { firstForbidden, textOutsidePopulationContext } from '../test/dom';
import { explanation } from '../test/fixtures';

/**
 * The detail view, against the mock backend.
 *
 * The slow-narrative test uses a gate it opens itself rather than a delay, so
 * "the pending state is visible before the answer arrives" is a fact about
 * ordering rather than a race the test hopes to win. There is no timer, no
 * `waitFor` with a timeout, and no fake clock in this file.
 */

function renderAccount(accountId: string, query = '') {
  return renderAt(<AccountPage />, '/accounts/:accountId', `/accounts/${accountId}${query}`);
}

describe('AccountPage', () => {
  it('puts the caveats above the first number on the page', async () => {
    renderAccount(ACCOUNTS.bothElevated);

    const caveats = await screen.findByRole('heading', { name: /what this page does not know/i });
    const composite = screen.getByRole('heading', { name: /^account/i });

    // AccountExplanation keeps caveats outside the summary so they get read.
    // Below the evidence they would not be.
    expect(caveats.compareDocumentPosition(composite) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('shows both scores with their own conventions and no third number', async () => {
    renderAccount(ACCOUNTS.bothElevated);

    const statistical = await screen.findByTestId('score-statistical');
    const model = screen.getByTestId('score-model');

    expect(statistical).toHaveTextContent(/0.50 convention \(not fitted\)/);
    expect(model).toHaveTextContent(/0.60 convention \(not fitted\)/);
    expect(screen.getAllByTestId('score-statistical')).toHaveLength(1);
    expect(screen.getAllByTestId('score-model')).toHaveLength(1);
  });

  it.each([
    [ACCOUNTS.bothElevated, 'BOTH_ELEVATED_SHARED_AXIS'],
    [ACCOUNTS.bothElevatedUnrelated, 'BOTH_ELEVATED_UNRELATED'],
    [ACCOUNTS.statisticalOnly, 'STATISTICAL_ONLY'],
    [ACCOUNTS.mlOnly, 'ML_ONLY_OUTSIDE_VIEW'],
    [ACCOUNTS.mlOnlyDiluted, 'ML_ONLY_DILUTED'],
    [ACCOUNTS.bothQuiet, 'BOTH_QUIET'],
    [ACCOUNTS.noModel, 'NO_MODEL'],
  ])('renders %s as the %s state', async (accountId, state) => {
    renderAccount(accountId);
    await screen.findByRole('heading', { name: /how the two layers relate/i });

    const chip = document.querySelector('[data-state]');
    expect(chip?.getAttribute('data-state')).toBe(state);
  });

  it('describes a quiet account without calling it clear', async () => {
    renderAccount(ACCOUNTS.bothQuiet);
    const chip = await screen.findByText(/neither layer elevated/i);
    const text = chip.closest('[data-state]')?.textContent ?? '';

    expect(text).toMatch(/not a clean bill of health/i);
    expect(firstForbidden(text)).toBeUndefined();
  });

  it('treats a missing model as a missing second opinion', async () => {
    renderAccount(ACCOUNTS.noModel);

    const absent = await screen.findByTestId('score-absent-model');
    expect(absent).toHaveTextContent(/no model has been trained/i);
    expect(
      screen.getByText(/no model has been trained, so there is no attribution/i),
    ).toBeInTheDocument();
  });

  it('keeps every percentile inside a population-context wrapper', async () => {
    renderAccount(ACCOUNTS.bothElevated);
    await screen.findByRole('heading', { name: /what the model isolated on/i });

    const wrappers = document.querySelectorAll('[data-population-context="true"]');
    expect(wrappers.length).toBe(4);
    expect(textOutsidePopulationContext(document.body)).not.toMatch(/percentile/i);
  });

  it('renders every feature, including the ones that did not isolate', async () => {
    renderAccount(ACCOUNTS.bothElevated);
    await screen.findByRole('heading', { name: /what the model isolated on/i });

    // Truncating to the isolating features would let a reviewer read the rest as
    // merely absent. A feature that pushed the account toward the crowd is a
    // finding.
    expect(document.querySelectorAll('[data-feature]')).toHaveLength(4);
    expect(document.querySelectorAll('[data-isolating="false"]')).toHaveLength(2);
    expect(screen.getAllByText(/pushed the account toward the crowd/i)).toHaveLength(2);
  });

  it('names attribution share as a share of the isolation, not of the score', async () => {
    renderAccount(ACCOUNTS.bothElevated);
    const header = await screen.findByText(/share of the isolation/i);
    expect(header).toHaveTextContent(/not a share of the score/i);
  });

  it('draws unmeasurable signals rather than dropping them', async () => {
    renderAccount(ACCOUNTS.bothElevated);
    await screen.findByRole('heading', { name: /what the statistical layer measured/i });

    const rows = Array.from(document.querySelectorAll('[data-signal]'));
    expect(rows).toHaveLength(5);

    const silent = rows.filter((row) => /could not judge/i.test(row.textContent ?? ''));
    expect(silent).toHaveLength(1);
    expect(silent[0]?.getAttribute('data-signal')).toBe('refund_reversal_rate');
  });

  it('badges a model-written narrative as model-written', async () => {
    server.use(
      http.get('/detection/accounts/:accountId/explanation', ({ request }) => {
        const template = new URL(request.url).searchParams.get('narrative') === 'template';
        return HttpResponse.json(
          explanation(ACCOUNTS.bothElevated, 'BOTH_ELEVATED', template ? 'TEMPLATE' : 'LLM'),
        );
      }),
    );

    renderAccount(ACCOUNTS.bothElevated);

    expect(await screen.findByText(/written by a hosted model/i)).toBeInTheDocument();
    expect(screen.getByText(/it cannot add to it/i)).toBeInTheDocument();
  });

  it('badges a template narrative as a template without inventing a fallback', async () => {
    renderAccount(ACCOUNTS.bothElevated);

    expect(await screen.findByText(/does not distinguish a template served/i)).toBeInTheDocument();
    expect(document.querySelector('[data-narrative-source="TEMPLATE"]')).not.toBeNull();
    expect(screen.getByText('DETERMINISTIC TEMPLATE')).toBeInTheDocument();
    expect(document.body.textContent?.toLowerCase()).not.toMatch(/fell back|fallback/);
  });

  it('shows the numbers before the narrative has returned, then the narrative', async () => {
    let open = () => {};
    const gate = new Promise<void>((resolve) => {
      open = resolve;
    });

    server.use(
      http.get('/detection/accounts/:accountId/explanation', async ({ request }) => {
        const template = new URL(request.url).searchParams.get('narrative') === 'template';
        if (template) {
          return HttpResponse.json(explanation(ACCOUNTS.bothElevated, 'BOTH_ELEVATED', 'TEMPLATE'));
        }
        await gate;
        return HttpResponse.json(explanation(ACCOUNTS.bothElevated, 'BOTH_ELEVATED', 'LLM'));
      }),
    );

    renderAccount(ACCOUNTS.bothElevated);

    // The arithmetic is final and on screen while the slowest call in the system
    // is still in flight.
    expect(await screen.findByTestId('score-statistical')).toHaveTextContent('0.71');
    expect(await screen.findByText(/narrative pending/i)).toBeInTheDocument();
    expect(screen.getByText(/every number on this page is already final/i)).toBeInTheDocument();

    open();

    expect(await screen.findByText(/written by a hosted model/i)).toBeInTheDocument();
    expect(screen.queryByText(/narrative pending/i)).not.toBeInTheDocument();
  });

  it('treats a failed narrative as a missing narrative, not a failed account', async () => {
    server.use(
      http.get('/detection/accounts/:accountId/explanation', ({ request }) => {
        const template = new URL(request.url).searchParams.get('narrative') === 'template';
        return template
          ? HttpResponse.json(EXPLANATIONS[ACCOUNTS.bothElevated])
          : new HttpResponse(null, { status: 504 });
      }),
    );

    renderAccount(ACCOUNTS.bothElevated);

    const notice = await screen.findByText(/narrative not retrieved/i);
    expect(notice).toBeInTheDocument();
    expect(screen.getByText(/this says nothing about the account/i)).toBeInTheDocument();
    // The evidence is all still there.
    expect(screen.getByTestId('score-statistical')).toHaveTextContent('0.71');
  });

  it('shows the template alongside the default when asked to compare', async () => {
    renderAccount(ACCOUNTS.bothElevated);
    // Wait for the default narrative itself, not merely for the page: clicking
    // while it is still in flight would count one block where two are expected.
    await screen.findByText(/does not distinguish a template served/i);

    await userEvent.click(
      screen.getByRole('checkbox', { name: /compare against the deterministic template/i }),
    );

    const blocks = document.querySelectorAll('[data-narrative-source]');
    expect(blocks).toHaveLength(2);
    // And it says so when both sides are the same generator, rather than
    // presenting one text twice as if the pair corroborated something.
    expect(await screen.findByText(/the default narrative was itself the template/i)).toBeInTheDocument();
  });

  it('shows a label with its source, stratum and blindness', async () => {
    renderAccount(ACCOUNTS.bothElevated);

    const verdict = await screen.findByText(/judged anomalous/i);
    const chip = verdict.closest('.label-chip');
    expect(chip?.textContent).toMatch(/source: dispute feed/i);
    expect(chip?.textContent).toMatch(/stratum: flagged pool/i);
    expect(chip?.textContent).toMatch(/judged blind/i);
  });

  it('warns on a synthetic label rather than presenting it as evidence', async () => {
    renderAccount(ACCOUNTS.statisticalOnly);
    expect(
      await screen.findByText(/circular as evidence about detection quality/i),
    ).toBeInTheDocument();
  });

  it('never renders a verdict without provenance beside it', async () => {
    for (const accountId of [ACCOUNTS.bothElevated, ACCOUNTS.statisticalOnly, ACCOUNTS.mlOnly]) {
      const view = renderAccount(accountId);
      await screen.findByText(/judged (anomalous|benign|unclear)/i);

      // Every element carrying a verdict must also carry a source and a stratum.
      for (const node of Array.from(document.querySelectorAll('[data-verdict]'))) {
        expect(node.getAttribute('data-source')).toBeTruthy();
        expect(node.getAttribute('data-stratum')).toBeTruthy();
      }
      view.unmount();
    }
  });

  it('says an unlabelled account is unlabelled, not ordinary', async () => {
    renderAccount(ACCOUNTS.bothQuiet);
    const none = await screen.findByText(/no label recorded/i);
    expect(none).toHaveTextContent(/absence of evidence, not a verdict of ordinary/i);
  });

  it('offers no control that would write a label', async () => {
    renderAccount(ACCOUNTS.bothElevated);
    await screen.findByTestId('score-statistical');

    const buttons = screen.queryAllByRole('button').map((button) => button.textContent ?? '');
    for (const label of buttons) {
      expect(label.toLowerCase()).not.toMatch(/mark|submit|save|review|label|confirm|reject/);
    }
    expect(screen.queryByRole('form')).not.toBeInTheDocument();
    expect(screen.getByText(/verdicts are recorded through/i)).toBeInTheDocument();
  });

  it('reports a failed explanation fetch as a failed fetch', async () => {
    server.use(
      http.get(
        '/detection/accounts/:accountId/explanation',
        () => new HttpResponse(null, { status: 500 }),
      ),
    );

    renderAccount(ACCOUNTS.bothElevated);
    const alert = await screen.findByRole('alert');

    expect(alert).toHaveTextContent(/could not load the explanation/i);
    expect(alert).toHaveTextContent(/not a finding about the data behind it/i);
  });

  it('uses no copy the backend forbids its own narratives', async () => {
    renderAccount(ACCOUNTS.bothElevated);
    await screen.findByText(/judged anomalous/i);

    // "judged anomalous" is the reviewer's own verdict wording, which the
    // backend's vocabulary list permits; what must not appear is the console
    // asserting one.
    const offending = firstForbidden(document.body.textContent ?? '');
    expect(offending, `rendered copy contains "${offending}"`).toBeUndefined();
  });
});
