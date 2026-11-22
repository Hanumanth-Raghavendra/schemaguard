import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SESSION_HINT } from './auth/session.ts';
import { ACCOUNT, fakeApi, fixtures, renderApp, type Sent } from './test/fakeApi.tsx';

const header = () => screen.getByRole('navigation', { name: 'Account' });
// While a session is being renewed the header shows no account links at all.
const settledHeader = () => screen.findByRole('navigation', { name: 'Account' });
const calls = (sent: Sent[]) => sent.map((request) => `${request.method} ${request.path}`);

async function fillIn(email: string, password: string) {
  const user = userEvent.setup();
  await user.clear(screen.getByLabelText('Email'));
  if (email !== '') await user.type(screen.getByLabelText('Email'), email);
  await user.clear(screen.getByLabelText('Password'));
  if (password !== '') await user.type(screen.getByLabelText('Password'), password);
  return user;
}

beforeEach(() => {
  localStorage.clear();
});

describe('accounts', () => {
  it('offers a visitor who never logged in to log in or sign up, without asking the API anything', () => {
    const sent = fakeApi();
    renderApp('/');
    expect(within(header()).getByRole('link', { name: 'Log in' })).toHaveAttribute('href', '/login');
    expect(within(header()).getByRole('link', { name: 'Sign up' })).toHaveAttribute('href', '/signup');
    expect(sent).toEqual([]);
  });

  it('renews the session of a browser that logged in before', async () => {
    localStorage.setItem(SESSION_HINT, '1');
    const sent = fakeApi({ loggedIn: true });
    renderApp('/');
    expect(within(await settledHeader()).getByRole('link', { name: ACCOUNT.email })).toHaveAttribute(
      'href',
      '/account',
    );
    expect(calls(sent)).toEqual(['POST /api/v1/auth/refresh']);
  });

  it('forgets a session the server no longer knows', async () => {
    localStorage.setItem(SESSION_HINT, '1');
    fakeApi({ loggedIn: false });
    renderApp('/');
    expect(within(await settledHeader()).getByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(localStorage.getItem(SESSION_HINT)).toBeNull();
  });

  it('logs in and lands on the account page, which asks the API with the access token', async () => {
    const sent = fakeApi();
    renderApp('/login');
    const user = await fillIn(ACCOUNT.email, ACCOUNT.password);
    await user.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
    expect(within(header()).getByRole('link', { name: ACCOUNT.email })).toBeInTheDocument();
    const account = sent.find((request) => request.path === '/api/v1/account');
    expect(account?.authorization).toBe(`Bearer ${fixtures.session.accessToken}`);
    expect(sent[0]?.body).toEqual({ email: ACCOUNT.email, password: ACCOUNT.password });
    expect(localStorage.getItem(SESSION_HINT)).toBe('1');
  });

  it('says why a login failed, in the API’s words', async () => {
    fakeApi();
    renderApp('/login');
    let user = await fillIn(ACCOUNT.email, 'not the password');
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The email or password is wrong.');

    user = await fillIn('locked@example.com', ACCOUNT.password);
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many failed logins for this account. Try again in 1 minute.',
    );
  });

  it('asks for both fields before it calls the API', async () => {
    const sent = fakeApi();
    renderApp('/login');
    const user = await fillIn(ACCOUNT.email, '');
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Enter your email and your password.');
    expect(sent).toEqual([]);
  });

  it('signs up, saying what to change when the email or password cannot be used', async () => {
    fakeApi();
    renderApp('/signup');
    expect(screen.getByText(/You do not need one to analyse schemas/)).toBeInTheDocument();

    let user = await fillIn('new@example.com', 'iloveyou');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('one of the 10,000 most common');

    user = await fillIn(ACCOUNT.email, 'a long and unusual passphrase');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'An account with this email already exists. Log in instead.',
    );

    user = await fillIn('new@example.com', 'a long and unusual passphrase');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
  });

  it('shows the password on request, so a long passphrase can be checked', async () => {
    fakeApi();
    renderApp('/signup');
    const user = await fillIn('new@example.com', 'a long passphrase');
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password');
    await user.click(screen.getByRole('button', { name: 'Show password' }));
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'text');
    expect(screen.getByRole('button', { name: 'Hide password' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('sends a visitor from the account page to the login page and back after logging in', async () => {
    fakeApi();
    renderApp('/account');
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    const user = await fillIn(ACCOUNT.email, ACCOUNT.password);
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
  });

  it('logs out from the header and forgets the session', async () => {
    localStorage.setItem(SESSION_HINT, '1');
    const sent = fakeApi({ loggedIn: true });
    renderApp('/account');
    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();

    await userEvent.setup().click(within(header()).getByRole('button', { name: 'Log out' }));
    expect(await within(header()).findByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Your account' })).not.toBeInTheDocument();
    expect(calls(sent)).toContain('POST /api/v1/auth/logout');
    expect(localStorage.getItem(SESSION_HINT)).toBeNull();
  });

  it('renews an expired access token once and tries the call again', async () => {
    localStorage.setItem(SESSION_HINT, '1');
    const sent = fakeApi({ loggedIn: true, expireFirstToken: true });
    renderApp('/account');
    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
    await screen.findByText(ACCOUNT.email, { selector: 'dd' });
    await vi.waitFor(() => {
      expect(calls(sent)).toEqual([
        'POST /api/v1/auth/refresh',
        'GET /api/v1/account',
        'POST /api/v1/auth/refresh',
        'GET /api/v1/account',
      ]);
    });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('deletes the account only with its password', async () => {
    localStorage.setItem(SESSION_HINT, '1');
    fakeApi({ loggedIn: true });
    renderApp('/account');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Delete account…' }));

    const confirm = screen.getByRole('form', { name: 'Confirm delete' });
    await user.type(within(confirm).getByLabelText('Password'), 'not it');
    await user.click(within(confirm).getByRole('button', { name: 'Delete my account' }));
    expect(await within(confirm).findByRole('alert')).toHaveTextContent('The password is wrong.');

    await user.clear(within(confirm).getByLabelText('Password'));
    await user.type(within(confirm).getByLabelText('Password'), ACCOUNT.password);
    await user.click(within(confirm).getByRole('button', { name: 'Delete my account' }));
    expect(await screen.findByRole('heading', { name: 'Account deleted' })).toBeInTheDocument();
    expect(within(header()).getByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(localStorage.getItem(SESSION_HINT)).toBeNull();
  });

  it('follows a logout in another tab', async () => {
    localStorage.setItem(SESSION_HINT, '1');
    fakeApi({ loggedIn: true });
    renderApp('/');
    expect(within(await settledHeader()).getByRole('link', { name: ACCOUNT.email })).toBeInTheDocument();

    act(() => {
      localStorage.removeItem(SESSION_HINT);
      window.dispatchEvent(new StorageEvent('storage', { key: SESSION_HINT, newValue: null }));
    });
    expect(within(header()).getByRole('link', { name: 'Log in' })).toBeInTheDocument();
  });
});
