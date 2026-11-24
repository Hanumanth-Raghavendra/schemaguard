import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SESSION_HINT } from './auth/session.ts';
import { fakeApi, fixtures, renderApp, type Sent } from './test/fakeApi.tsx';
import created from './test/fixtures/projectCreated.json';

const { project, version1, version2 } = fixtures;
const calls = (sent: Sent[]) => sent.map((request) => `${request.method} ${request.path}`);

/** A browser that logged in before: the session renews itself on load. */
function signedIn(options: Parameters<typeof fakeApi>[0] = {}) {
  localStorage.setItem(SESSION_HINT, '1');
  return fakeApi({ loggedIn: true, ...options });
}

beforeEach(() => {
  localStorage.clear();
});

describe('projects', () => {
  it('lists the projects with their latest risk', async () => {
    signedIn();
    renderApp('/projects');
    const link = await screen.findByRole('link', { name: /^shop/ });
    expect(link).toHaveAttribute('href', `/projects/${project.id}`);
    expect(link).toHaveTextContent('2 versions, last saved');
    expect(within(link).getByText('Critical')).toBeInTheDocument();
    expect(within(link).getByText('100')).toBeInTheDocument();
    expect(link).toHaveTextContent('8 changes, 6 risks');
  });

  it('invites a first project when there is none', async () => {
    signedIn({ projects: [] });
    renderApp('/projects');
    expect(await screen.findByText(/No projects yet/)).toBeInTheDocument();
  });

  it('creates a project and opens it on the form for its first version', async () => {
    const sent = signedIn();
    renderApp('/projects');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('New project'), 'billing');
    await user.click(screen.getByRole('button', { name: 'Create project' }));

    expect(await screen.findByRole('heading', { name: 'billing' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Save version 1' })).toBeInTheDocument();
    expect(screen.getByText(/No versions yet/)).toBeInTheDocument();
    expect(sent.find((request) => request.path === '/api/v1/projects' && request.method === 'POST')?.body).toEqual({
      name: 'billing',
    });
  });

  it('says when the account has a project of that name', async () => {
    signedIn();
    renderApp('/projects');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('New project'), 'Shop');
    await user.click(screen.getByRole('button', { name: 'Create project' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('This account already has a project named "Shop".');
  });

  it('shows the timeline newest first, each version with the risk of its change', async () => {
    signedIn();
    renderApp(`/projects/${project.id}`);
    const timeline = await screen.findByRole('list');
    const items = within(timeline).getAllByRole('link');
    expect(items.map((item) => item.getAttribute('href'))).toEqual([
      `/projects/${project.id}/versions/2`,
      `/projects/${project.id}/versions/1`,
    ]);
    expect(items[0]).toHaveTextContent('Version 2 Release 2.4');
    expect(items[0]).toHaveTextContent('Column is dropped with all its values');
    expect(items[1]).toHaveTextContent('Version 1 Initial schema');
    expect(items[1]).toHaveTextContent('First version');
    // With versions saved, the page opens on the timeline; the form waits behind a button.
    expect(screen.queryByLabelText('Schema')).not.toBeInTheDocument();
  });

  it('saves a new version from the latest one, with a label', async () => {
    const sent = signedIn();
    renderApp(`/projects/${project.id}`);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Save version 3' }));
    await user.click(screen.getByRole('button', { name: 'Start from version 2' }));
    expect(await screen.findByLabelText('Schema')).toHaveValue(version2.sql);

    await user.type(screen.getByLabelText('Schema'), '\nCREATE TABLE notes (id int PRIMARY KEY);');
    await user.type(screen.getByLabelText('Label (optional)'), 'Notes');
    await user.click(screen.getByRole('button', { name: 'Save version' }));

    await vi.waitFor(() => {
      expect(screen.queryByLabelText('Schema')).not.toBeInTheDocument();
    });
    const save = sent.find((request) => request.method === 'POST' && request.path.endsWith('/versions'));
    expect(save?.body).toEqual({ sql: `${version2.sql}\nCREATE TABLE notes (id int PRIMARY KEY);`, label: 'Notes' });
    expect(calls(sent).filter((call) => call === `GET /api/v1/projects/${project.id}`)).toHaveLength(2);
  });

  it('shows where a schema that cannot be analysed goes wrong, and refuses one that changes nothing', async () => {
    signedIn();
    renderApp(`/projects/${project.id}`);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Save version 3' }));
    await user.type(screen.getByLabelText('Schema'), 'CREATE TABLE orders (id integer PRIMARY KEY,');
    await user.click(screen.getByRole('button', { name: 'Save version' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The new schema has 1 error and cannot be analysed.');
    expect(alert).toHaveTextContent('New version, line 1');

    await user.click(screen.getByRole('button', { name: 'Start from version 2' }));
    await screen.findByDisplayValue(/CREATE INDEX orders_status_idx/);
    await user.click(screen.getByRole('button', { name: 'Save version' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('This schema is the same as version 2');
  });

  it('renames and deletes a project', async () => {
    const sent = signedIn();
    renderApp(`/projects/${project.id}`);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Rename' }));
    await user.clear(screen.getByLabelText('Project name'));
    await user.type(screen.getByLabelText('Project name'), 'shop backend');
    await user.click(screen.getByRole('button', { name: 'Save name' }));
    expect(await screen.findByRole('heading', { name: 'shop backend' })).toBeInTheDocument();
    expect(sent.find((request) => request.method === 'PATCH')?.body).toEqual({ name: 'shop backend' });

    await user.click(screen.getByRole('button', { name: 'Delete…' }));
    const confirm = screen.getByRole('group', { name: 'Confirm delete' });
    expect(confirm).toHaveTextContent('Delete shop backend and its 2 versions?');
    await user.click(within(confirm).getByRole('button', { name: 'Delete project' }));
    expect(await screen.findByRole('heading', { name: 'Projects' })).toBeInTheDocument();
    expect(calls(sent)).toContain(`DELETE /api/v1/projects/${project.id}`);
  });

  it('opens a version with its saved risk and its SQL, and its change in the analyser', async () => {
    signedIn();
    renderApp(`/projects/${project.id}/versions/2`);
    expect(await screen.findByRole('heading', { name: /^Version 2/ })).toHaveTextContent('Version 2 Release 2.4');
    expect(screen.getByText('Critical risk')).toBeInTheDocument();
    expect(screen.getByText(/8 changes and 6 risks, scored when the version was saved/)).toBeInTheDocument();
    expect(screen.getByText(/CREATE INDEX orders_status_idx/)).toBeInTheDocument();

    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: 'Open the change from version 1 in the analyser' }));
    expect(await screen.findByLabelText('Current schema')).toHaveValue(version1.sql);
    expect(screen.getByLabelText('New schema')).toHaveValue(version2.sql);
    expect(await screen.findByRole('region', { name: 'Overall risk' })).toBeInTheDocument();
  });

  it('calls the first version the first, with nothing to compare', async () => {
    signedIn();
    renderApp(`/projects/${project.id}/versions/1`);
    expect(await screen.findByText(/The first version: there is nothing before it/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Open the change/ })).not.toBeInTheDocument();
    // Version 1 is not the latest, so it cannot be deleted.
    expect(screen.queryByRole('heading', { name: 'Delete this version' })).not.toBeInTheDocument();
  });

  it('deletes the latest version after a confirmation', async () => {
    const sent = signedIn();
    renderApp(`/projects/${project.id}/versions/2`);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Delete…' }));
    await user.click(screen.getByRole('button', { name: 'Delete version 2' }));
    expect(await screen.findByRole('heading', { name: 'Versions' })).toBeInTheDocument();
    expect(calls(sent)).toContain(`DELETE /api/v1/projects/${project.id}/versions/2`);
  });

  it('answers a project of someone else, or none, as missing', async () => {
    signedIn();
    renderApp('/projects/00000000-0000-4000-8000-000000000000');
    expect(await screen.findByRole('alert')).toHaveTextContent('There is no such project in your account.');
  });

  it('sends a visitor who is not logged in to the login page', async () => {
    fakeApi();
    renderApp('/projects');
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
  });
});

describe('saving an analysis to a project', () => {
  async function analyseExample(title = 'Add a required column') {
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: new RegExp(`^${title}`) }));
    await screen.findByRole('region', { name: 'Overall risk' });
    return user;
  }

  it('saves the new schema as the next version of a chosen project', async () => {
    const sent = signedIn();
    renderApp('/');
    await screen.findByRole('link', { name: 'Projects' });
    const user = await analyseExample();
    await user.click(screen.getByRole('button', { name: 'Save the new schema to a project' }));
    expect(await screen.findByLabelText('Project')).toHaveValue(project.id);
    await user.type(screen.getByLabelText('Label (optional)'), 'From the analyser');
    await user.click(screen.getByRole('button', { name: 'Save version' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Saved as version 2 of shop.');
    const save = sent.find((request) => request.path === `/api/v1/projects/${project.id}/versions`);
    expect(save?.body).toMatchObject({ label: 'From the analyser' });
  });

  it("says when the new schema is the project's latest version already", async () => {
    signedIn();
    renderApp('/');
    await screen.findByRole('link', { name: 'Projects' });
    // The typical release ends at exactly the schema saved as version 2.
    const user = await analyseExample('A typical release');
    await user.click(screen.getByRole('button', { name: 'Save the new schema to a project' }));
    await screen.findByLabelText('Project');
    await user.click(screen.getByRole('button', { name: 'Save version' }));
    expect(await screen.findByText(/This schema is the same as version 2/)).toBeInTheDocument();
  });

  it('can start a new project for it', async () => {
    const sent = signedIn();
    renderApp('/');
    await screen.findByRole('link', { name: 'Projects' });
    const user = await analyseExample();
    await user.click(screen.getByRole('button', { name: 'Save the new schema to a project' }));
    await user.selectOptions(await screen.findByLabelText('Project'), 'A new project…');
    await user.type(screen.getByLabelText('New project name'), 'billing');
    await user.click(screen.getByRole('button', { name: 'Save version' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Saved as version 1 of billing.');
    const posts = sent.filter((request) => request.method === 'POST' && request.path.startsWith('/api/v1/projects'));
    expect(posts.map((request) => request.path)).toEqual([
      '/api/v1/projects',
      `/api/v1/projects/${created.id}/versions`,
    ]);
    expect(posts[0]?.body).toEqual({ name: 'billing' });
  });

  it('is not offered without an account', async () => {
    fakeApi();
    renderApp('/');
    await analyseExample();
    expect(screen.queryByRole('button', { name: 'Save the new schema to a project' })).not.toBeInTheDocument();
  });
});
