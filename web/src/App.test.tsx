import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { App } from './App.tsx';
import { fakeApi, fixtures } from './test/fakeApi.ts';

function steps(count: number): RegExp {
  return new RegExp(`^${count} steps, reaches the new schema`);
}

async function analyseExample() {
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole('button', { name: 'Load an example' }));
  await user.click(screen.getByRole('button', { name: 'Analyse changes' }));
  await screen.findByRole('region', { name: 'Overall risk' });
  return user;
}

describe('Compare two schemas', () => {
  it('runs diff, report and plan on both schemas and shows the overall risk', async () => {
    const sent = fakeApi();
    await analyseExample();

    expect(sent.map((request) => request.path).sort()).toEqual(['/api/v1/diff', '/api/v1/plan', '/api/v1/report']);
    const risk = screen.getByRole('region', { name: 'Overall risk' });
    expect(risk).toHaveTextContent(`${fixtures.report.score.value}/ 100`);
    expect(risk).toHaveTextContent('Critical risk');
    expect(risk).toHaveTextContent('Column is dropped with all its values (SG-R302, 85 points)');
  });

  it('lists risks highest score first and every change', async () => {
    fakeApi();
    const user = await analyseExample();

    const risks = screen.getByRole('tabpanel', { name: `Risks (${fixtures.report.findings.length})` });
    const titles = within(risks).getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent);
    expect(titles[0]).toBe('Column is dropped with all its values');
    expect(titles.at(-1)).toBe('Statements that need a full lock should run with a lock timeout');

    await user.click(screen.getByRole('tab', { name: `Changes (${fixtures.diff.significant})` }));
    const changes = screen.getByRole('tabpanel', { name: `Changes (${fixtures.diff.significant})` });
    expect(within(changes).getAllByRole('listitem')).toHaveLength(fixtures.diff.changes.length);
    expect(within(changes).getByText('column public.orders.id: type integer -> bigint')).toBeVisible();
    expect(within(changes).getAllByText('Added')).toHaveLength(4);
    expect(within(changes).getByText('Possible rename')).toBeVisible();
  });

  it('replans when a rename is confirmed or the route changes', async () => {
    const sent = fakeApi();
    const user = await analyseExample();
    await user.click(screen.getByRole('tab', { name: 'Plan' }));
    expect(screen.getByText(steps(fixtures.plan.steps.length))).toBeVisible();

    await user.click(screen.getByRole('checkbox'));
    await screen.findByText(steps(fixtures.planRenamed.steps.length));
    expect(sent.at(-1)?.body).toMatchObject({
      route: 'SAFE',
      renames: [{ kind: 'column', table: { schema: 'public', name: 'customers' }, from: 'name', to: 'full_name' }],
    });

    await user.click(screen.getByRole('radio', { name: /Direct/ }));
    await screen.findByText(steps(fixtures.planDirect.steps.length));
    expect(sent.at(-1)?.body).toMatchObject({ route: 'DIRECT' });
  });

  it('copies the plan as a script', async () => {
    fakeApi();
    const user = await analyseExample();
    const writeText = vi.spyOn(navigator.clipboard, 'writeText');
    await user.click(screen.getByRole('tab', { name: 'Plan' }));
    await user.click(screen.getByRole('button', { name: 'Copy script' }));
    expect(writeText).toHaveBeenCalledWith(fixtures.plan.sql);
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeVisible();
  });

  it('shows where a schema cannot be read', async () => {
    fakeApi({ invalidSchema: true });
    const user = userEvent.setup();
    render(<App />);
    await user.type(screen.getByLabelText('Current schema'), 'CREATE TABLE t (id int;');
    await user.type(screen.getByLabelText('New schema'), 'CREATE TABLE t (id int);');
    await user.click(screen.getByRole('button', { name: 'Analyse changes' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Input cannot be analysed');
    expect(alert).toHaveTextContent("Current schema, line 1, column 23: Expected ',' or ')' but found end of statement");
    expect(screen.getByLabelText('Current schema')).toHaveAttribute('aria-invalid', 'true');
  });

  it('needs a new schema before it can analyse', () => {
    fakeApi();
    render(<App />);
    expect(screen.getByRole('button', { name: 'Analyse changes' })).toBeDisabled();
  });
});

describe('Check a migration', () => {
  it('checks each statement and puts a failure under its statement', async () => {
    const sent = fakeApi();
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole('radio', { name: 'Check a migration' }));
    await user.click(screen.getByRole('button', { name: 'Load an example' }));
    await user.click(screen.getByRole('button', { name: 'Check migration' }));

    const risk = await screen.findByRole('region', { name: 'Overall risk' });
    expect(risk).toHaveTextContent(`${fixtures.lint.score.value}/ 100`);
    expect(sent[0]?.path).toBe('/api/v1/lint');

    const items = screen.getAllByRole('listitem').filter((item) => item.textContent.startsWith('line '));
    expect(items).toHaveLength(fixtures.lint.statements.length);
    const last = items.at(-1);
    expect(last).toHaveTextContent('Would fail');
    expect(last).toHaveTextContent('relation "missing" does not exist');
    expect(items[0]).not.toHaveTextContent('does not exist');
  });
});
