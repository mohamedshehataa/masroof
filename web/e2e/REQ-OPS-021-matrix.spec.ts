import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { AR_VERSION, EN_VERSION, mockHealth, ok } from './health.helpers';

const combos = [
  { dir: 'ltr', locale: 'en-US', colorScheme: 'light', copy: EN_VERSION },
  { dir: 'ltr', locale: 'en-US', colorScheme: 'dark', copy: EN_VERSION },
  { dir: 'rtl', locale: 'ar-EG', colorScheme: 'light', copy: AR_VERSION },
  { dir: 'rtl', locale: 'ar-EG', colorScheme: 'dark', copy: AR_VERSION },
] as const;

test.describe('REQ-OPS-021 375x812 matrix (axe + screenshot)', () => {
  for (const c of combos) {
    test(`REQ-OPS-021 ${c.dir} ${c.colorScheme} has no serious/critical axe violations and matches baseline @REQ-OPS-021`, async ({
      browser,
    }) => {
      const ctx = await browser.newContext({
        viewport: { width: 375, height: 812 },
        locale: c.locale,
        colorScheme: c.colorScheme,
      });
      const page = await ctx.newPage();
      await mockHealth(page, ok);
      await page.goto('/version');
      await expect(page.getByText(c.copy)).toBeVisible();
      await expect(page.locator('html')).toHaveAttribute('dir', c.dir);

      const results = await new AxeBuilder({ page }).analyze();
      const bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
      expect(bad.map((v) => v.id)).toEqual([]);

      // Baselines are produced by the implementer (`npx playwright test --update-snapshots`) once the page exists.
      await expect(page).toHaveScreenshot(`version-${c.dir}-${c.colorScheme}.png`, { maxDiffPixelRatio: 0.01 });
      await ctx.close();
    });
  }
});
