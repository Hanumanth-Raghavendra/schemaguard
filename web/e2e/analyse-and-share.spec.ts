import { expect, test } from '@playwright/test';

// The whole promise of the site against a real API and database: paste (here, one click on an
// example), analyse, look at every view, share, open the link elsewhere, delete it.
test('analyse an example, share it, open the link and delete it', async ({ page, context }) => {
  await page.goto('/');
  await page.getByRole('button', { name: /^A typical release/ }).click();

  const risk = page.getByRole('region', { name: 'Overall risk' });
  await expect(risk).toContainText('/ 100');
  await expect(risk).toContainText('Critical risk');
  await expect(page.getByRole('heading', { name: 'Column is dropped with all its values' })).toBeVisible();

  await page.getByRole('tab', { name: /^Changes/ }).click();
  const changes = page.getByRole('tabpanel', { name: /^Changes/ });
  await expect(changes.getByText('column public.orders.id: type integer -> bigint', { exact: true })).toBeVisible();

  await page.getByRole('tab', { name: 'Diagram' }).click();
  await expect(page.getByRole('img', { name: /^Diagram of 2 tables/ })).toBeVisible();

  await page.getByRole('tab', { name: 'Plan' }).click();
  await page.getByRole('checkbox').check();
  await expect(page.getByText(/^\d+ steps, reaches the new schema/)).toBeVisible();
  await expect(page.locator('pre', { hasText: 'RENAME COLUMN name TO full_name' })).toBeVisible();

  await page.getByRole('button', { name: 'Share' }).click();
  const link = await page.getByLabel('Link to this analysis').inputValue();
  expect(link).toMatch(/\/a\/[A-Za-z0-9]{22}$/);

  const shared = await context.newPage();
  await shared.goto(link);
  await expect(shared.getByRole('heading', { name: 'Shared risk report' })).toBeVisible();
  await shared.getByRole('button', { name: 'Delete link' }).click();
  await shared.getByRole('group', { name: 'Confirm delete' }).getByRole('button', { name: 'Delete' }).click();
  await expect(shared.getByText('Deleted. The link no longer works.')).toBeVisible();

  await shared.reload();
  await expect(shared.getByRole('alert')).toHaveText('This link has expired or was deleted.');
});
