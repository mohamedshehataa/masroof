import { expect, test } from '@playwright/test';
import { AR_VERSION, EN_VERSION } from './health.helpers';
import { combos, expectNoSeriousAxe, openShell, viewports } from './shell.helpers';

// REQ-UI-004: LTR/RTL x light/dark x 375x812 / 1280x800, axe zero serious/critical + screenshot baselines.
// Baselines: create them on Linux in mcr.microsoft.com/playwright:v1.64.1-noble (web/CLAUDE.md), not on Windows:
//   docker run --rm -v "$PWD":/w -w /w/web --ipc=host mcr.microsoft.com/playwright:v1.64.1-noble \
//     npx playwright test REQ-UI-004-shell-matrix --update-snapshots
test.describe('REQ-UI-004 app shell matrix (axe + screenshot)', () => {
  for (const vp of Object.keys(viewports) as (keyof typeof viewports)[]) {
    for (const c of combos) {
      test(`REQ-UI-004 shell ${vp} ${c.dir} ${c.colorScheme}: no serious/critical axe, matches baseline @REQ-UI-004`, async ({
        browser,
      }) => {
        const { ctx, page } = await openShell(browser, c, vp);
        await page.goto('/more');
        await expect(page.locator('html')).toHaveAttribute('dir', c.dir);
        await expect(page.getByRole('navigation')).toBeVisible();
        await expectNoSeriousAxe(page);
        await expect(page).toHaveScreenshot(`shell-${vp}-${c.dir}-${c.colorScheme}.png`, { maxDiffPixelRatio: 0.01 });
        await ctx.close();
      });

      test(`REQ-UI-004 version page ${vp} ${c.dir} ${c.colorScheme}: no serious/critical axe, matches baseline @REQ-UI-004`, async ({
        browser,
      }) => {
        const { ctx, page } = await openShell(browser, c, vp);
        await page.goto('/');
        await expect(page.getByText(c.dir === 'rtl' ? AR_VERSION : EN_VERSION)).toBeVisible();
        await expect(page.locator('html')).toHaveAttribute('dir', c.dir);
        await expect(page.getByRole('navigation')).toBeVisible(); // the version page lives inside the shell now
        await expectNoSeriousAxe(page);
        await expect(page).toHaveScreenshot(`version-shell-${vp}-${c.dir}-${c.colorScheme}.png`, {
          maxDiffPixelRatio: 0.01,
        });
        await ctx.close();
      });
    }
  }
});
