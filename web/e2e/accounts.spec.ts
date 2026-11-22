import { expect, test } from '@playwright/test';

// An account's whole life against a real API and database, on one origin as in production: sign up,
// stay logged in across a reload (the httpOnly refresh cookie), log out, a refused login, log in,
// delete the account. Each run makes its own account and deletes it.
test('sign up, stay logged in, log out, log in and delete the account', async ({ page, context }) => {
  const email = `e2e-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}@example.com`;
  const password = 'a long and unusual passphrase for the end-to-end test';
  const nav = page.getByRole('navigation', { name: 'Account' });

  await page.goto('/');
  await nav.getByRole('link', { name: 'Sign up' }).click();
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('heading', { name: 'Your account' })).toBeVisible();
  await expect(page.locator('dd', { hasText: email })).toBeVisible();

  // The refresh token is a cookie scripts cannot read, sent to /api/v1/auth only.
  const refresh = (await context.cookies()).find((cookie) => cookie.name === '__Secure-sg-refresh');
  expect(refresh).toMatchObject({ httpOnly: true, secure: true, sameSite: 'Strict', path: '/api/v1/auth' });
  expect(await page.evaluate<string>('document.cookie')).not.toContain('sg-refresh');

  // The access token lived in memory only; after a reload the cookie brings the session back.
  await page.reload();
  await expect(nav.getByRole('link', { name: email })).toBeVisible();

  await nav.getByRole('button', { name: 'Log out' }).click();
  await expect(nav.getByRole('link', { name: 'Log in' })).toBeVisible();
  await page.reload();
  await expect(nav.getByRole('link', { name: 'Log in' })).toBeVisible();

  await nav.getByRole('link', { name: 'Log in' }).click();
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('not the password');
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page.getByRole('alert')).toHaveText('The email or password is wrong.');

  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page.getByRole('heading', { name: 'Your account' })).toBeVisible();

  await page.getByRole('button', { name: 'Delete account…' }).click();
  const confirm = page.getByRole('form', { name: 'Confirm delete' });
  await confirm.getByLabel('Password', { exact: true }).fill(password);
  await confirm.getByRole('button', { name: 'Delete my account' }).click();
  await expect(page.getByRole('heading', { name: 'Account deleted' })).toBeVisible();
  await expect(nav.getByRole('link', { name: 'Log in' })).toBeVisible();
});
