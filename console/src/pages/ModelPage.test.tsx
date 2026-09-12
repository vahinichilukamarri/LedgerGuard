import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { ModelPage } from './ModelPage';
import { renderAt } from '../test/render';
import { HttpResponse, http, server } from '../test/server';
import { firstForbidden } from '../test/dom';

function renderModel() {
  return renderAt(<ModelPage />, '/model', '/model');
}

describe('ModelPage', () => {
  it('publishes the seed and the training snapshot, which is what makes a score reproducible', async () => {
    renderModel();

    expect(await screen.findByText('20240917')).toBeInTheDocument();
    expect(screen.getByText('128')).toBeInTheDocument();
    expect(screen.getByText(/2026-09-10 07:59:00Z/)).toBeInTheDocument();
  });

  it('treats a 404 as a state rather than a failure', async () => {
    server.use(http.get('/detection/model', () => new HttpResponse(null, { status: 404 })));
    renderModel();

    expect(await screen.findByText(/no model has been trained/i)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText(/absence of a second opinion/i)).toBeInTheDocument();
  });

  it('still reports a genuine failure as a failure', async () => {
    server.use(http.get('/detection/model', () => new HttpResponse(null, { status: 500 })));
    renderModel();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/could not load the model metadata/i);
  });

  it('says the scores are unvalidated on this page too', async () => {
    renderModel();
    expect(await screen.findByText(/the scores this model produces are unvalidated/i)).toBeInTheDocument();
  });

  it('uses no copy the backend forbids its own narratives', async () => {
    renderModel();
    await screen.findByText('20240917');

    const offending = firstForbidden(document.body.textContent ?? '');
    expect(offending, `rendered copy contains "${offending}"`).toBeUndefined();
  });
});
