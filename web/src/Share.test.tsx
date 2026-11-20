import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EXAMPLE_AFTER, EXAMPLE_BEFORE } from './examples.ts';
import { fakeApi, fixtures, renderApp } from './test/fakeApi.tsx';
import { rememberToken, tokenFor } from './tokens.ts';

beforeEach(() => {
  localStorage.clear();
});

async function analyseExample() {
  const user = userEvent.setup();
  renderApp();
  await user.click(screen.getByRole('button', { name: 'Load an example' }));
  await user.click(screen.getByRole('button', { name: 'Analyse changes' }));
  await screen.findByRole('region', { name: 'Overall risk' });
  return user;
}

describe('Share', () => {
  it('saves the report, shows the link and keeps the delete key in this browser', async () => {
    const sent = fakeApi();
    const user = await analyseExample();
    await user.click(screen.getByRole('button', { name: 'Share' }));

    const link = await screen.findByLabelText('Link to this analysis');
    expect(link).toHaveValue(`${window.location.origin}/a/${fixtures.saved.id}`);
    expect(sent.at(-1)).toMatchObject({
      method: 'POST',
      path: '/api/v1/analyses/report',
      body: { before: EXAMPLE_BEFORE, after: EXAMPLE_AFTER },
    });
    expect(tokenFor(fixtures.saved.id)).toBe(fixtures.saved.deleteToken);
    expect(screen.queryByRole('button', { name: 'Share' })).not.toBeInTheDocument();
  });

  it('says so when the server does not keep links', async () => {
    fakeApi({ sharingOff: true });
    const user = await analyseExample();
    await user.click(screen.getByRole('button', { name: 'Share' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Sharing is switched off on this server.');
  });

  it('saves a checked migration as a lint', async () => {
    const sent = fakeApi();
    const user = userEvent.setup();
    renderApp();
    await user.click(screen.getByRole('radio', { name: 'Check a migration' }));
    await user.click(screen.getByRole('button', { name: 'Load an example' }));
    await user.click(screen.getByRole('button', { name: 'Check migration' }));
    await user.click(await screen.findByRole('button', { name: 'Share' }));
    await screen.findByLabelText('Link to this analysis');
    expect(sent.at(-1)).toMatchObject({ method: 'POST', path: '/api/v1/analyses/lint' });
  });
});

describe('A shared link', () => {
  it('shows the saved report and its inputs', async () => {
    fakeApi();
    renderApp(`/a/${fixtures.stored.id}`);
    expect(await screen.findByRole('heading', { name: 'Shared risk report' })).toBeVisible();
    expect(screen.getByRole('region', { name: 'Overall risk' })).toHaveTextContent(
      `${fixtures.stored.result.score.value}/ 100`,
    );
    expect(screen.getByText(/CREATE INDEX orders_status_idx/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete link' })).not.toBeInTheDocument();
  });

  it('shows a saved migration check statement by statement', async () => {
    fakeApi();
    renderApp(`/a/${fixtures.storedLint.id}`);
    expect(await screen.findByRole('heading', { name: 'Shared migration check' })).toBeVisible();
    expect(screen.getAllByText(/^line \d+$/)).toHaveLength(fixtures.storedLint.result.statements.length);
  });

  it('lets the sharer delete it after a confirmation', async () => {
    const sent = fakeApi();
    rememberToken(fixtures.stored.id, fixtures.saved.deleteToken, fixtures.stored.expiresAt);
    const user = userEvent.setup();
    renderApp(`/a/${fixtures.stored.id}`);
    await user.click(await screen.findByRole('button', { name: 'Delete link' }));
    const confirm = screen.getByRole('group', { name: 'Confirm delete' });
    await user.click(within(confirm).getByRole('button', { name: 'Delete' }));

    expect(await screen.findByText('Deleted. The link no longer works.')).toBeVisible();
    expect(sent.at(-1)).toMatchObject({
      method: 'DELETE',
      path: `/api/v1/analyses/${fixtures.stored.id}`,
      token: fixtures.saved.deleteToken,
    });
    expect(tokenFor(fixtures.stored.id)).toBeNull();
  });

  it('explains an expired or deleted link', async () => {
    fakeApi();
    renderApp('/a/AAAAAAAAAAAAAAAAAAAAAA');
    expect(await screen.findByRole('alert')).toHaveTextContent('This link has expired or was deleted.');
    expect(screen.getByRole('link', { name: 'Start a new analysis' })).toHaveAttribute('href', '/');
  });

  it('opens in the editor and runs the full analysis on the saved schemas', async () => {
    const sent = fakeApi();
    const user = userEvent.setup();
    renderApp(`/a/${fixtures.stored.id}`);
    await user.click(await screen.findByRole('button', { name: 'Open in the editor' }));

    await screen.findByRole('tab', { name: 'Plan' });
    expect(screen.getByLabelText('Current schema')).toHaveValue(EXAMPLE_BEFORE);
    const analyses = sent.filter((request) => request.method === 'POST').map((request) => request.path);
    expect(analyses.sort()).toEqual(['/api/v1/diff', '/api/v1/plan', '/api/v1/report']);
  });
});

describe('The plan as a migration', () => {
  it('checks the generated plan against the current schema', async () => {
    const sent = fakeApi();
    const user = await analyseExample();
    await user.click(screen.getByRole('tab', { name: 'Plan' }));
    await user.click(screen.getByRole('button', { name: 'Check this plan as a migration' }));

    await screen.findByRole('button', { name: 'Check migration' });
    expect(screen.getByRole('radio', { name: 'Check a migration' })).toBeChecked();
    expect(screen.getByLabelText('Migration')).toHaveValue(fixtures.plan.sql);
    expect(sent.at(-1)).toMatchObject({
      path: '/api/v1/lint',
      body: { schema: EXAMPLE_BEFORE, migration: fixtures.plan.sql },
    });
  });

  it('is offered under Risks once a rename is confirmed', async () => {
    fakeApi();
    const user = await analyseExample();
    expect(screen.queryByRole('button', { name: 'check the plan as a migration' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Plan' }));
    await user.click(screen.getByRole('checkbox'));
    await user.click(screen.getByRole('tab', { name: /^Risks/ }));
    expect(screen.getByRole('button', { name: 'check the plan as a migration' })).toBeVisible();
  });
});

describe('Export', () => {
  it('downloads the report as Markdown and JSON', async () => {
    fakeApi();
    const files: Blob[] = [];
    vi.spyOn(URL, 'createObjectURL').mockImplementation((blob) => {
      files.push(blob as Blob);
      return 'blob:test';
    });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const user = await analyseExample();

    await user.click(screen.getByRole('button', { name: 'Download Markdown' }));
    await user.click(screen.getByRole('button', { name: 'Download JSON' }));
    expect(click).toHaveBeenCalledTimes(2);
    const [markdown, json] = await Promise.all(files.map((file) => file.text()));
    expect(markdown).toContain('# SchemaGuard risk report');
    expect(markdown).toContain(`**Risk: ${fixtures.report.score.value}/100 (Critical)**`);
    expect(markdown).toContain('## Plan (safe route)');
    expect(JSON.parse(json ?? '{}')).toMatchObject({ kind: 'compare', before: EXAMPLE_BEFORE, report: fixtures.report });
  });
});
