import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { GOOD_PASSWORD } from './auth.helpers';
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

async function signIn(page: Page, password = GOOD_PASSWORD) {
  await page.getByLabel('Email').fill('mohamed@example.com');
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

test.describe('REQ-UI-010 login screen matrix (axe + screenshot)', () => {
  for (const c of combos) {
    test(`${c.dir} ${c.colorScheme}: login is axe clean and matches the baseline @REQ-UI-010`, async ({ browser }) => {
      const ctx = await browser.newContext({
        viewport: { width: 375, height: 812 },
        locale: c.locale,
        colorScheme: c.colorScheme,
      });
      const page = await ctx.newPage();
      await mockApi(page, { signedIn: false });
      await page.goto('/');
      await expect(page.locator('html')).toHaveAttribute('dir', c.dir);
      await expect(page.locator('input[type="password"]')).toBeVisible();
      await expectNoSeriousAxe(page);
      await expect(page).toHaveScreenshot(`login-${c.dir}-${c.colorScheme}.png`);
      await ctx.close();
    });
  }
});

test.describe('REQ-UI-010 REQ-UI-011 session flows', () => {
  test.use({ locale: 'en-US', viewport: { width: 375, height: 812 } });

  test('login success lands on Home with data, keeps tokens out of localStorage @REQ-UI-010', async ({ page }) => {
    const api = await mockApi(page, { signedIn: false });
    await page.goto('/');
    await signIn(page);
    await expect(page.locator('.row')).toHaveCount(3);
    expect(api.auth.loginCalls).toBe(1);
    expect(await page.evaluate(() => window.localStorage.length)).toBe(0);
    await page.getByRole('link', { name: 'More' }).click();
    await expect(page.getByText('Signed in as Mohamed')).toBeVisible();
  });

  test('bad password shows a generic error and clears the password @REQ-UI-010', async ({ page }) => {
    await mockApi(page, { signedIn: false });
    await page.goto('/');
    await signIn(page, 'wrong-password');
    await expect(page.getByRole('alert')).toHaveText('Wrong email or password.');
    await expect(page.getByLabel('Password')).toHaveValue('');
    await expect(page.getByLabel('Email')).toHaveValue('mohamed@example.com');
  });

  test('rate limit shows the Retry-After wait @REQ-UI-010', async ({ page }) => {
    const api = await mockApi(page, { signedIn: false });
    api.auth.rateLimit = true;
    await page.goto('/');
    await signIn(page);
    await expect(page.getByRole('alert')).toHaveText('Too many attempts. Try again in 2 minutes.');
  });

  test('rate limit message is Arabic with the dual minute form in RTL @REQ-UI-010', async ({ browser }) => {
    const ctx = await browser.newContext({ locale: 'ar-EG', viewport: { width: 375, height: 812 } });
    const page = await ctx.newPage();
    const api = await mockApi(page, { signedIn: false });
    api.auth.rateLimit = true;
    await page.goto('/');
    await page.getByLabel('البريد الإلكتروني').fill('mohamed@example.com');
    await page.getByLabel('كلمة المرور').fill(GOOD_PASSWORD);
    await page.getByRole('button', { name: 'دخول' }).click();
    await expect(page.getByRole('alert')).toContainText('محاولات كتير');
    await expect(page.getByRole('alert')).toContainText('دقيقتان');
    await ctx.close();
  });

  test('a stored refresh token restores the session on reload without showing login @REQ-UI-011', async ({ page }) => {
    const api = await mockApi(page);
    await page.goto('/');
    await expect(page.locator('.row')).toHaveCount(3);
    expect(api.auth.refreshCalls).toBe(1);
    await page.reload();
    await expect(page.locator('.row')).toHaveCount(3);
    expect(api.auth.refreshCalls).toBe(2);
  });

  test('an expired access token is silently refreshed and the request retried once @REQ-UI-011', async ({ page }) => {
    const api = await mockApi(page, { signedIn: false });
    await page.goto('/');
    await signIn(page);
    await expect(page.locator('.row')).toHaveCount(3);
    expect(api.auth.refreshCalls).toBe(0);
    api.auth.validAccess = 'expired-elsewhere';
    api.auth.validRefresh = (await page.evaluate(() => window.sessionStorage.getItem('masroof.rt'))) ?? '';
    await page.getByRole('link', { name: 'Transactions' }).click();
    await expect(page.locator('.row')).toHaveCount(3);
    expect(api.auth.refreshCalls).toBe(1);
    await expect(page.getByLabel('Password')).toHaveCount(0);
  });

  test('a rejected refresh (rotated token reuse) lands on login @REQ-UI-011', async ({ page }) => {
    const api = await mockApi(page, { signedIn: false });
    await page.goto('/');
    await signIn(page);
    await expect(page.locator('.row')).toHaveCount(3);
    api.auth.validAccess = 'expired-elsewhere';
    api.auth.validRefresh = 'someone-else-rotated-it';
    await page.getByRole('link', { name: 'Transactions' }).click();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    expect(await page.evaluate(() => window.sessionStorage.getItem('masroof.rt'))).toBeNull();
  });

  test('sign out calls the backend, clears state and shows login @REQ-UI-011', async ({ page }) => {
    const api = await mockApi(page);
    await page.goto('/');
    await expect(page.locator('.row')).toHaveCount(3);
    await page.getByRole('link', { name: 'More' }).click();
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    expect(api.auth.logoutBodies).toEqual([{ refreshToken: api.auth.validRefresh }]);
    expect(await page.evaluate(() => window.sessionStorage.getItem('masroof.rt'))).toBeNull();
  });
});
