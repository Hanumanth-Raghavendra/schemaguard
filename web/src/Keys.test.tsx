import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SESSION_HINT } from './auth/session.ts';
import { formatDate } from './dates.ts';
import { fakeApi, fixtures, renderApp, type Sent } from './test/fakeApi.tsx';

function recorded(name: string) {
  const key = fixtures.keys.find((candidate) => candidate.name === name);
  if (key === undefined) throw new Error(`No recorded key named ${name}`);
  return key;
}
const laptop = recorded('laptop');
const actions = recorded('GitHub Actions');
const keyCalls = (sent: Sent[]) =>
  sent.filter((request) => request.path.startsWith('/api/v1/account/keys')).map((r) => `${r.method} ${r.path}`);

/** The account page of a logged-in browser, once its keys have loaded. */
async function openKeys(options: Parameters<typeof fakeApi>[0] = {}) {
  localStorage.setItem(SESSION_HINT, '1');
  const sent = fakeApi({ loggedIn: true, ...options });
  const user = userEvent.setup();
  renderApp('/account');
  await screen.findByRole('heading', { name: 'API keys' });
  await screen.findAllByText(/No keys yet|sg_/);
  return { sent, user };
}

async function makeKey(user: ReturnType<typeof userEvent.setup>, name: string) {
  const form = screen.getByRole('form', { name: 'Make an API key' });
  await user.clear(within(form).getByLabelText('Key name'));
  if (name !== '') await user.type(within(form).getByLabelText('Key name'), name);
  await user.click(within(form).getByRole('button', { name: 'Make key' }));
}

beforeEach(() => {
  localStorage.clear();
});

describe('API keys', () => {
  it('lists the keys by name and first characters, with when each was last used', async () => {
    const { sent } = await openKeys();
    const list = screen.getByRole('list', { name: 'Your API keys' });
    const items = within(list).getAllByRole('listitem');
    expect(items.map((item) => item.getAttribute('aria-label'))).toEqual([laptop.name, actions.name]);
    expect(items[0]).toHaveTextContent(`${laptop.hint}…, made ${formatDate(laptop.createdAt)}, never used`);
    expect(items[1]).toHaveTextContent(`last used ${formatDate(actions.lastUsedAt ?? '')}`);
    const listed = sent.find((request) => request.path === '/api/v1/account/keys');
    expect(listed?.authorization).toBe(`Bearer ${fixtures.session.accessToken}`);
  });

  it('says when there are no keys yet', async () => {
    await openKeys({ keys: [] });
    expect(screen.getByText(/^No keys yet\./)).toBeInTheDocument();
  });

  it('shows a new key once, with a way to copy it, then never again', async () => {
    const { sent, user } = await openKeys();
    const writeText = vi.spyOn(navigator.clipboard, 'writeText');
    await makeKey(user, 'deploy bot');

    const shown = await screen.findByRole('region', { name: 'New API key' });
    const field = within(shown).getByRole('textbox', { name: 'Key for deploy bot' });
    expect(field).toHaveValue(fixtures.keyCreated.key);
    expect(field).toHaveFocus();
    expect(shown).toHaveTextContent('It is not shown again');
    expect(sent.find((request) => request.method === 'POST' && request.path === '/api/v1/account/keys')?.body).toEqual({
      name: 'deploy bot',
    });
    // The new key heads the list straight away, unused.
    const items = within(screen.getByRole('list', { name: 'Your API keys' })).getAllByRole('listitem');
    expect(items[0]).toHaveAccessibleName('deploy bot');
    expect(items[0]).toHaveTextContent('never used');
    expect(screen.getByLabelText('Key name')).toHaveValue('');

    await user.click(within(shown).getByRole('button', { name: 'Copy' }));
    expect(writeText).toHaveBeenCalledWith(fixtures.keyCreated.key);
    expect(await within(shown).findByRole('button', { name: 'Copied' })).toBeVisible();

    await user.click(within(shown).getByRole('button', { name: 'Done, I have copied it' }));
    expect(screen.queryByRole('region', { name: 'New API key' })).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue(fixtures.keyCreated.key)).not.toBeInTheDocument();
    expect(keyCalls(sent)).toEqual(['GET /api/v1/account/keys', 'POST /api/v1/account/keys']);
  });

  it('says how to copy the key by hand when the browser refuses', async () => {
    const { user } = await openKeys();
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('denied'));
    await makeKey(user, 'deploy bot');
    const shown = await screen.findByRole('region', { name: 'New API key' });
    await user.click(within(shown).getByRole('button', { name: 'Copy' }));
    expect(await within(shown).findByRole('alert')).toHaveTextContent('Select the key and copy it yourself.');
  });

  it('asks for a name without calling the API, and shows why the API refuses one', async () => {
    const { sent, user } = await openKeys({ keysFull: true });
    await makeKey(user, '');
    const form = screen.getByRole('form', { name: 'Make an API key' });
    expect(within(form).getByRole('alert')).toHaveTextContent('Name the key first.');
    expect(keyCalls(sent)).toEqual(['GET /api/v1/account/keys']);

    await makeKey(user, 'eleventh');
    expect(await within(form).findByRole('alert')).toHaveTextContent(
      'This account has 10 API keys, the most it can have. Delete one to make room.',
    );
    expect(screen.queryByRole('region', { name: 'New API key' })).not.toBeInTheDocument();
  });

  it('deletes a key only after asking', async () => {
    const { sent, user } = await openKeys();
    const row = screen.getByRole('listitem', { name: laptop.name });
    await user.click(within(row).getByRole('button', { name: `Delete key ${laptop.name}…` }));
    expect(row).toHaveTextContent('Anything still using this key stops working at once.');
    await user.click(within(row).getByRole('button', { name: 'Cancel' }));
    expect(keyCalls(sent)).toEqual(['GET /api/v1/account/keys']);

    await user.click(within(row).getByRole('button', { name: `Delete key ${laptop.name}…` }));
    await user.click(within(row).getByRole('button', { name: 'Delete key' }));
    expect(await screen.findByRole('listitem', { name: actions.name })).toBeInTheDocument();
    expect(screen.queryByRole('listitem', { name: laptop.name })).not.toBeInTheDocument();
    expect(keyCalls(sent)).toEqual(['GET /api/v1/account/keys', `DELETE /api/v1/account/keys/${laptop.id}`]);
  });

  it('keeps a key on the list when deleting it fails, and says why', async () => {
    const { user } = await openKeys({ keysGone: true });
    const row = screen.getByRole('listitem', { name: laptop.name });
    await user.click(within(row).getByRole('button', { name: `Delete key ${laptop.name}…` }));
    await user.click(within(row).getByRole('button', { name: 'Delete key' }));
    expect(await within(row).findByRole('alert')).toHaveTextContent('This account has no such API key.');
    expect(within(row).getByRole('button', { name: 'Delete key' })).toBeEnabled();
  });
});
