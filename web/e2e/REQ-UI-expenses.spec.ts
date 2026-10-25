import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { mockApi } from './expenses.helpers';

const combos = [
  { dir: 'ltr', locale: 'en-US', colorScheme: 'light' },
  { dir: 'ltr', locale: 'en-US', colorScheme: 'dark' },
  { dir: 'rtl', locale: 'ar-EG', colorScheme: 'light' },
  { dir: 'rtl', locale: 'ar-EG', colorScheme: 'dark' },
] as const;

async function expectNoSeriousAxe(page: Page) {
  const results = await new AxeBuilder({ page }).analyze();
  const bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  expect(bad.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([]);
}

test.describe('REQ-UI-017 REQ-UI-026 REQ-UI-027 REQ-UI-028 screens 375x812 matrix (axe + screenshot)', () => {
  for (const c of combos) {
    test(`${c.dir} ${c.colorScheme}: Home, Transactions and Add are axe clean and match baselines @REQ-UI-026`, async ({
      browser,
    }) => {
      const ctx = await browser.newContext({
        viewport: { width: 375, height: 812 },
        locale: c.locale,
        colorScheme: c.colorScheme,
      });
      const page = await ctx.newPage();
      await mockApi(page);

      await page.goto('/');
      await expect(page.locator('html')).toHaveAttribute('dir', c.dir);
      await expect(page.locator('.row')).toHaveCount(3);
      await expectNoSeriousAxe(page);
      await expect(page).toHaveScreenshot(`home-${c.dir}-${c.colorScheme}.png`);

      await page.getByRole('link', { name: c.dir === 'rtl' ? 'المعاملات' : 'Transactions' }).click();
      await expect(page.locator('.row')).toHaveCount(3);
      await expectNoSeriousAxe(page);
      await expect(page).toHaveScreenshot(`transactions-${c.dir}-${c.colorScheme}.png`);

      await page.getByRole('link', { name: c.dir === 'rtl' ? 'إضافة' : 'Add' }).click();
      await expect(page.locator('.key').first()).toBeVisible();
      await expectNoSeriousAxe(page);
      await expect(page).toHaveScreenshot(`add-${c.dir}-${c.colorScheme}.png`);
      await ctx.close();
    });
  }
});

test.describe('REQ-UI-028 add flow', () => {
  test.use({ locale: 'en-US', viewport: { width: 375, height: 812 } });

  test('REQ-UI-028 keypad entry posts integer minor units and lands on Transactions @REQ-UI-028', async ({ page }) => {
    const api = await mockApi(page);
    await page.goto('/add');
    for (const k of ['1', '2', 'Decimal point', '5']) await page.getByRole('button', { name: k, exact: true }).click();
    await page.getByRole('button', { name: 'Groceries' }).click();
    await page.getByLabel('Note').fill('milk');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page).toHaveURL(/\/transactions$/);
    expect(api.posted).toHaveLength(1);
    expect(api.posted[0]).toMatchObject({
      type: 'EXPENSE',
      amount: { amountMinor: 1250, currency: 'EGP' },
      notes: 'milk',
      paymentMethod: 'CASH',
    });
  });

  test('REQ-UI-028 zero amount shows a validation error and sends nothing @REQ-UI-028', async ({ page }) => {
    const api = await mockApi(page);
    await page.goto('/add');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('alert')).toHaveText('Enter an amount greater than zero.');
    expect(api.posted).toHaveLength(0);
  });
});

test.describe('REQ-UI-026 REQ-UI-027 list states', () => {
  test.use({ locale: 'en-US', viewport: { width: 375, height: 812 } });

  test('REQ-UI-026 Home shows the Cairo-month total (October: 125.50 + 40 + 980.75) @REQ-UI-026', async ({ page }) => {
    await mockApi(page);
    await page.goto('/');
    await expect(page.locator('.hero-amount')).toHaveText('1,146.25 EGP');
  });

  test('REQ-UI-027 empty, error with retry, and offline states @REQ-UI-027', async ({ page, context }) => {
    await mockApi(page, { items: [] });
    await page.goto('/transactions');
    await expect(page.getByText('No expenses match.')).toBeVisible();

    const failing = await context.newPage();
    await mockApi(failing, { fail: true });
    await failing.goto('/transactions');
    await expect(failing.getByRole('alert')).toHaveText("Couldn't load this list.");
    await expect(failing.getByRole('button', { name: 'Retry' })).toBeVisible();
    await context.setOffline(true);
    await expect(failing.getByRole('status').filter({ hasText: "You're offline" })).toBeVisible();
    await context.setOffline(false);
  });

  test('REQ-UI-027 search sends q and category filter sends categoryId @REQ-UI-027', async ({ page }) => {
    const api = await mockApi(page);
    await page.goto('/transactions');
    await page.getByRole('searchbox', { name: 'Search expenses' }).fill('uber');
    await expect.poll(() => api.expenseCalls.some((u) => u.searchParams.get('q') === 'uber')).toBe(true);
    await page.getByRole('combobox', { name: 'Category' }).selectOption({ label: 'Transport' });
    await expect.poll(() => api.expenseCalls.some((u) => u.searchParams.has('categoryId'))).toBe(true);
  });
});

// Needs `make up` (backend with expenses, no auth) and E2E_BASE_URL pointing at it; skipped otherwise.
test.describe('REQ-UI-028 real backend', () => {
  test.skip(process.env.E2E_REAL_BACKEND !== '1', 'set E2E_REAL_BACKEND=1 with the backend up');

  test('REQ-UI-028 a saved expense appears in Transactions @REQ-UI-028 @real-backend', async ({ page }) => {
    await page.goto('/add');
    await page.getByRole('button', { name: '7', exact: true }).click();
    await page.getByLabel('Note').fill('e2e real backend');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page).toHaveURL(/\/transactions$/);
    await expect(page.getByText('e2e real backend').or(page.getByText('7 EGP'))).toBeVisible();
  });
});
