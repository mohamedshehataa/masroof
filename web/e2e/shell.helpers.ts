import AxeBuilder from '@axe-core/playwright';
import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { mockHealth, ok } from './health.helpers';

/** Mirror of src/test/session.ts: the seam that makes the shell treat the visitor as signed in. */
export const SESSION_KEY = 'masroof.session';
export const SESSION_VALUE = JSON.stringify({ accessToken: 'test-access', refreshToken: 'test-refresh' });

export const viewports = {
  mobile: { width: 375, height: 812 },
  desktop: { width: 1280, height: 800 },
} as const;

export const combos = [
  { dir: 'ltr', locale: 'en-US', colorScheme: 'light' },
  { dir: 'ltr', locale: 'en-US', colorScheme: 'dark' },
  { dir: 'rtl', locale: 'ar-EG', colorScheme: 'light' },
  { dir: 'rtl', locale: 'ar-EG', colorScheme: 'dark' },
] as const;

export const TAB_PATHS = ['/', '/transactions', '/add', '/insights', '/more'] as const;

export async function openShell(
  browser: Browser,
  c: (typeof combos)[number],
  vp: keyof typeof viewports,
  opts: { signedIn?: boolean } = {},
): Promise<{ ctx: BrowserContext; page: Page }> {
  const ctx = await browser.newContext({ viewport: viewports[vp], locale: c.locale, colorScheme: c.colorScheme });
  if (opts.signedIn ?? true) {
    await ctx.addInitScript(
      ([k, v]) => {
        window.localStorage.setItem(k as string, v as string);
      },
      [SESSION_KEY, SESSION_VALUE],
    );
  }
  const page = await ctx.newPage();
  await mockHealth(page, ok);
  return { ctx, page };
}

export async function expectNoSeriousAxe(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page }).analyze();
  const bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  expect(bad.map((v) => v.id)).toEqual([]);
}
