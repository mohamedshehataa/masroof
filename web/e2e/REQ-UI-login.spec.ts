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
  await page.getByLabel('Password', { exact: true }).fill(password);
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
    await expect(page.getByLabel('Password', { exact: true })).toHaveValue('');
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
    await page.getByLabel('كلمة المرور', { exact: true }).fill(GOOD_PASSWORD);
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
    await expect(page.getByLabel('Password', { exact: true })).toHaveCount(0);
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

  test('sign out with an expired access token still sends the refresh token and lands on login @REQ-UI-011', async ({
    page,
  }) => {
    const api = await mockApi(page);
    await page.goto('/');
    await expect(page.locator('.row')).toHaveCount(3);
    const stored = await page.evaluate(() => window.sessionStorage.getItem('masroof.rt'));
    api.auth.validAccess = 'expired-elsewhere';
    await page.getByRole('link', { name: 'More' }).click();
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    expect(api.auth.logoutBodies).toEqual([{ refreshToken: stored }]);
    expect(api.auth.refreshCalls).toBe(1);
  });

  test('a short password gets the same generic message and is cleared @REQ-UI-010', async ({ page }) => {
    await mockApi(page, { signedIn: false });
    await page.goto('/');
    await signIn(page, 'short');
    await expect(page.getByRole('alert')).toHaveText('Wrong email or password.');
    await expect(page.getByLabel('Password', { exact: true })).toHaveValue('');
  });

  test('show/hide password toggle is a pressed-state button @REQ-UI-010', async ({ page }) => {
    await mockApi(page, { signedIn: false });
    await page.goto('/');
    const field = page.getByLabel('Password', { exact: true });
    const toggle = page.getByRole('button', { name: 'Show password' });
    await field.fill('secret-value');
    await expect(field).toHaveAttribute('type', 'password');
    await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-pressed', 'true');
    await expect(field).toHaveAttribute('type', 'text');
  });

  test('language toggle switches to Arabic and RTL @REQ-UI-010', async ({ page }) => {
    await mockApi(page, { signedIn: false });
    await page.goto('/');
    await page.getByRole('button', { name: 'العربية' }).click();
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await expect(page.getByRole('button', { name: 'دخول' })).toBeVisible();
  });

  test('submit shows a spinner with aria-busy while the request is in flight @REQ-UI-010', async ({ page }) => {
    await mockApi(page, { signedIn: false });
    await page.route('**/auth/login', async (route) => {
      await new Promise((r) => setTimeout(r, 600));
      await route.fallback();
    });
    await page.goto('/');
    await signIn(page);
    await expect(page.getByRole('button', { name: 'Sign in' })).toHaveAttribute('aria-busy', 'true');
    await expect(page.locator('.spinner')).toBeVisible();
    await expect(page.locator('.row')).toHaveCount(3);
  });

  test('network failure shows an error with Retry that signs in once the network is back @REQ-UI-010', async ({
    page,
  }) => {
    await mockApi(page, { signedIn: false });
    let down = true;
    await page.route('**/auth/login', async (route) => {
      if (down) await route.abort('connectionrefused');
      else await route.fallback();
    });
    await page.goto('/');
    await signIn(page);
    await expect(page.getByRole('alert')).toHaveText("Can't reach the server. Check your connection.");
    down = false;
    await page.getByRole('button', { name: 'Retry' }).click();
    await expect(page.locator('.row')).toHaveCount(3);
  });

  test('offline shows the banner and disables submit @REQ-UI-010', async ({ page, context }) => {
    await mockApi(page, { signedIn: false });
    await page.goto('/');
    await context.setOffline(true);
    await expect(page.getByText("You're offline. Showing saved data.")).toBeVisible();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeDisabled();
    await context.setOffline(false);
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeEnabled();
  });

  test('reload with a dead network keeps the skeleton (not login) and restores on the online event @REQ-UI-011', async ({
    page,
  }) => {
    await mockApi(page);
    await page.goto('/');
    await expect(page.locator('.row')).toHaveCount(3);
    let up = false;
    await page.route('**/auth/refresh', async (route) => {
      if (up) await route.fallback();
      else await route.abort('internetdisconnected');
    });
    await page.reload();
    await expect(page.locator('[aria-busy="true"]')).toBeAttached();
    await expect(page.getByRole('button', { name: 'Sign in' })).toHaveCount(0);
    up = true;
    await page.evaluate(() => window.dispatchEvent(new Event('online')));
    await expect(page.locator('.row')).toHaveCount(3);
  });
});
