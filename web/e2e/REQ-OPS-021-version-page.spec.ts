import { expect, test } from '@playwright/test';
import {
  AR_VERSION,
  EN_ERROR,
  EN_OFFLINE,
  EN_VERSION,
  good,
  htmlError,
  json200,
  mockHealth,
  netFail,
  ok,
  problem503,
} from './health.helpers';

test.describe('REQ-OPS-021 version page (English default)', () => {
  test.use({ locale: 'en-US' });

  test('REQ-OPS-021 shows the English version line after exactly one GET /health @REQ-OPS-021', async ({ page }) => {
    const calls = await mockHealth(page, ok);
    await page.goto('/');
    await expect(page.getByText(EN_VERSION)).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');
    await expect(page.locator('bdi[dir="ltr"]')).toHaveText(['0.1.0', '1a2b3c4']);
    expect(calls.count).toBe(1);
  });

  test('REQ-OPS-021 fr-FR falls back to English LTR @REQ-OPS-021', async ({ browser }) => {
    const ctx = await browser.newContext({ locale: 'fr-FR' });
    const page = await ctx.newPage();
    await mockHealth(page, ok);
    await page.goto('/');
    await expect(page.getByText(EN_VERSION)).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');
    await ctx.close();
  });

  test('REQ-OPS-021 language toggle switches copy, lang and dir without reload, and does not persist @REQ-OPS-021', async ({
    page,
  }) => {
    const calls = await mockHealth(page, ok);
    await page.goto('/');
    await expect(page.getByText(EN_VERSION)).toBeVisible();
    await page.getByRole('button', { name: 'العربية' }).click();
    await expect(page.getByText(AR_VERSION)).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', 'ar');
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await expect(page.getByText('Backend')).toHaveCount(0);
    await page.getByRole('button', { name: 'English' }).click();
    await expect(page.getByText(EN_VERSION)).toBeVisible();
    expect(calls.count).toBe(1);
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  });

  test('REQ-OPS-021 skeleton with role=status aria-busy=true while /health is in flight @REQ-OPS-021', async ({
    page,
  }) => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => (release = r));
    await mockHealth(page, async (route) => {
      await gate;
      await ok(route);
    });
    await page.goto('/');
    await expect(page.getByRole('status')).toHaveAttribute('aria-busy', 'true');
    await expect(page.getByText('undefined')).toHaveCount(0);
    release();
    await expect(page.getByText(EN_VERSION)).toBeVisible();
  });

  const failures = [
    ['503 problem+json', problem503],
    ['502 html', htmlError(502)],
    ['504 html', htmlError(504)],
    ['network failure', netFail],
    ['200 status DOWN', json200({ ...good, status: 'DOWN' })],
    ['200 missing version', json200({ ...good, version: undefined })],
    ['200 gitSha XYZ', json200({ ...good, gitSha: 'XYZ' })],
  ] as const;
  for (const [name, handler] of failures) {
    test(`REQ-OPS-021 ${name}: error + Retry within 1s, one request, Retry sends one more, no pageerror @REQ-OPS-021`, async ({
      page,
    }) => {
      const errors: Error[] = [];
      page.on('pageerror', (e) => errors.push(e));
      const calls = await mockHealth(page, handler, ok);
      await page.goto('/');
      await expect(page.getByText(EN_ERROR)).toBeVisible({ timeout: 1000 });
      await expect(page.getByRole('status')).toHaveCount(0);
      expect(calls.count).toBe(1);
      await page.getByRole('button', { name: 'Retry' }).click();
      await expect(page.getByText(EN_VERSION)).toBeVisible();
      expect(calls.count).toBe(2);
      expect(errors).toEqual([]);
    });
  }

  test('REQ-OPS-021 offline Retry shows offline copy; coming online refetches exactly once @REQ-OPS-021', async ({
    page,
    context,
  }) => {
    const calls = await mockHealth(page, problem503, ok);
    await page.goto('/');
    await expect(page.getByText(EN_ERROR)).toBeVisible();
    await context.setOffline(true);
    await page.getByRole('button', { name: 'Retry' }).click();
    await expect(page.getByText(EN_OFFLINE)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
    await expect(page.getByText(EN_ERROR)).toHaveCount(0);
    const before = calls.count;
    await context.setOffline(false);
    await expect(page.getByText(EN_VERSION)).toBeVisible();
    expect(calls.count - before).toBe(1);
  });
});

test.describe('REQ-OPS-021 version page (Arabic locale)', () => {
  test.use({ locale: 'ar-EG' });

  test('REQ-OPS-021 ar-EG gives lang=ar dir=rtl and only Arabic copy @REQ-OPS-021', async ({ page }) => {
    await mockHealth(page, ok);
    await page.goto('/');
    await expect(page.getByText(AR_VERSION)).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', 'ar');
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await expect(page.getByText('Backend')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'English' })).toBeVisible();
  });
});

test.describe('REQ-OPS-021 dark theme token', () => {
  test('REQ-OPS-021 dark background equals the dark --background token and differs from light @REQ-OPS-021', async ({
    browser,
  }) => {
    async function bg(colorScheme: 'dark' | 'light') {
      const ctx = await browser.newContext({ colorScheme, locale: 'en-US' });
      const page = await ctx.newPage();
      await mockHealth(page, ok);
      await page.goto('/');
      await expect(page.getByText(EN_VERSION)).toBeVisible();
      const r = await page.evaluate(() => {
        const probe = document.createElement('div');
        probe.style.backgroundColor = getComputedStyle(document.documentElement)
          .getPropertyValue('--background')
          .trim();
        document.body.appendChild(probe);
        const token = getComputedStyle(probe).backgroundColor;
        probe.remove();
        return { body: getComputedStyle(document.body).backgroundColor, token };
      });
      await ctx.close();
      return r;
    }
    const dark = await bg('dark');
    const light = await bg('light');
    expect(dark.token).not.toBe('rgba(0, 0, 0, 0)');
    expect(dark.body).toBe(dark.token);
    expect(light.body).toBe(light.token);
    expect(dark.body).not.toBe(light.body);
  });
});
