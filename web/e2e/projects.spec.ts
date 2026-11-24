import { expect, test, type Page } from '@playwright/test';

const V1 = `CREATE TABLE customers (id integer PRIMARY KEY, email text NOT NULL);
CREATE TABLE orders (id integer PRIMARY KEY, customer_id integer NOT NULL);`;
const V2_ADDITION = `
CREATE INDEX orders_customer_id_idx ON orders (customer_id);`;

/** Types into the schema editor (Monaco once loaded, a plain text area before), at the end of what is there. */
async function typeSchema(page: Page, text: string) {
  const editor = page.getByRole('textbox', { name: 'Schema' });
  await editor.focus();
  await page.keyboard.press('ControlOrMeta+End');
  await page.keyboard.insertText(text);
}

// A project's life against a real API and database: create it, save two versions (the second
// starting from the first), read the risk of the change, open it in the analyser, find that saving
// the same schema again is refused, delete the project. The account is made and deleted by the test.
test('save schema versions in a project and follow the risk of each change', async ({ page }) => {
  const email = `e2e-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}@example.com`;
  const password = 'a long and unusual passphrase for the end-to-end test';
  const nav = page.getByRole('navigation', { name: 'Account' });

  await page.goto('/signup');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('heading', { name: 'Your account' })).toBeVisible();

  await nav.getByRole('link', { name: 'Projects', exact: true }).click();
  await expect(page.getByText('No projects yet')).toBeVisible();
  await page.getByLabel('New project').fill('shop');
  await page.getByRole('button', { name: 'Create project' }).click();
  await expect(page.getByRole('heading', { name: 'shop' })).toBeVisible();

  await typeSchema(page, V1);
  await page.getByLabel('Label (optional)').fill('Initial schema');
  await page.getByRole('button', { name: 'Save version', exact: true }).click();
  const timeline = page.getByRole('list', { name: 'Versions, newest first' });
  await expect(timeline.getByRole('link', { name: /Version 1 Initial schema/ })).toBeVisible();

  await page.getByRole('button', { name: 'Save version 2' }).click();
  await page.getByRole('button', { name: 'Start from version 1' }).click();
  await expect(page.locator('.monaco-editor .view-lines')).toContainText('CREATE TABLE customers');
  await typeSchema(page, V2_ADDITION);
  await page.getByRole('button', { name: 'Save version', exact: true }).click();
  const second = timeline.getByRole('link', { name: /^Version 2/ });
  await expect(second).toContainText('Index is built without CONCURRENTLY');

  // The chart has a bar per version; the second one opens version 2.
  const bar = page
    .getByRole('list', { name: 'Versions, oldest first' })
    .getByRole('link', { name: /^Version 2: risk \d+ of 100/ });
  await bar.click();
  await expect(page.getByRole('heading', { name: /^Version 2/ })).toBeVisible();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download Markdown' }).click();
  expect((await download).suggestedFilename()).toBe('shop-v2-report.md');
  await expect(page.getByRole('region', { name: 'Risk of this version' })).toContainText('/ 100');
  await page.getByRole('button', { name: 'Open the change from version 1 in the analyser' }).click();
  await expect(page.getByRole('region', { name: 'Overall risk' })).toContainText('Index is built without CONCURRENTLY');

  // The analyser's new schema is version 2 itself, so saving it again changes nothing.
  await page.getByRole('button', { name: 'Save the new schema to a project' }).click();
  await expect(page.getByLabel('Project', { exact: true })).toHaveValue(/.+/);
  await page.getByRole('button', { name: 'Save version', exact: true }).click();
  await expect(page.getByText('This schema is the same as version 2, so there is nothing new to save.')).toBeVisible();

  await nav.getByRole('link', { name: 'Projects', exact: true }).click();
  await page.getByRole('link', { name: /^shop/ }).click();
  await page.getByRole('button', { name: 'Delete…' }).click();
  await page.getByRole('button', { name: 'Delete project' }).click();
  await expect(page.getByText('No projects yet')).toBeVisible();

  await nav.getByRole('link', { name: email }).click();
  await page.getByRole('button', { name: 'Delete account…' }).click();
  await page.getByRole('form', { name: 'Confirm delete' }).getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Delete my account' }).click();
  await expect(page.getByRole('heading', { name: 'Account deleted' })).toBeVisible();
});
