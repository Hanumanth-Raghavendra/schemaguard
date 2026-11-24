import { expect, test } from '@playwright/test';

// An API key's life against a real API and database: make one on the account page and copy it once,
// use it as a script in CI would (list projects, save a version) through the same /api the site
// uses, find that it cannot touch the account, see its last use, delete it and find it refused.
// The account is made and deleted by the test.
test('make an API key, save a version with it, and delete it', async ({ page }) => {
  const email = `e2e-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}@example.com`;
  const password = 'a long and unusual passphrase for the end-to-end test';
  const nav = page.getByRole('navigation', { name: 'Account' });

  await page.goto('/signup');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('heading', { name: 'Your account' })).toBeVisible();

  // A project to save into, made with the login.
  await nav.getByRole('link', { name: 'Projects', exact: true }).click();
  await page.getByLabel('New project').fill('ci');
  await page.getByRole('button', { name: 'Create project' }).click();
  await expect(page.getByRole('heading', { name: 'ci', exact: true })).toBeVisible();
  const project = new URL(page.url()).pathname.split('/').pop() ?? '';

  await nav.getByRole('link', { name: email }).click();
  await page.getByLabel('Key name').fill('GitHub Actions');
  await page.getByRole('button', { name: 'Make key' }).click();
  const field = page.getByRole('textbox', { name: 'Key for GitHub Actions' });
  await expect(field).toHaveValue(/^sg_[A-Za-z0-9_-]{43}$/);
  const key = await field.inputValue();
  await page.getByRole('button', { name: 'Done, I have copied it' }).click();
  await expect(field).toHaveCount(0);

  const auth = { Authorization: `Bearer ${key}` };
  const listed = await page.request.get('/api/v1/projects', { headers: auth });
  expect(listed.status()).toBe(200);
  expect(await listed.json()).toMatchObject([{ id: project, name: 'ci' }]);
  const saved = await page.request.post(`/api/v1/projects/${project}/versions`, {
    headers: auth,
    data: { sql: 'CREATE TABLE builds (id integer PRIMARY KEY);', label: 'from CI' },
  });
  expect(saved.status()).toBe(201);
  const account = await page.request.get('/api/v1/account', { headers: auth });
  expect(account.status()).toBe(403);
  expect(await account.json()).toMatchObject({
    detail: 'An API key may only read projects and save versions; this needs a login.',
  });

  // The version the key saved is in the project, and the key shows that it was used.
  await nav.getByRole('link', { name: 'Projects', exact: true }).click();
  await page.getByRole('link', { name: /^ci/ }).click();
  const timeline = page.getByRole('list', { name: 'Versions, newest first' });
  await expect(timeline.getByRole('link', { name: /Version 1 from CI/ })).toBeVisible();
  await nav.getByRole('link', { name: email }).click();
  const row = page.getByRole('listitem', { name: 'GitHub Actions' });
  await expect(row).toContainText('last used');

  await row.getByRole('button', { name: 'Delete key GitHub Actions…' }).click();
  await row.getByRole('button', { name: 'Delete key', exact: true }).click();
  await expect(page.getByText('No keys yet')).toBeVisible();
  expect((await page.request.get('/api/v1/projects', { headers: auth })).status()).toBe(401);

  await page.getByRole('button', { name: 'Delete account…' }).click();
  await page.getByRole('form', { name: 'Confirm delete' }).getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Delete my account' }).click();
  await expect(page.getByRole('heading', { name: 'Account deleted' })).toBeVisible();
});
