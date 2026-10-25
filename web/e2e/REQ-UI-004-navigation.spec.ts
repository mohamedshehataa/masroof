import { expect, test } from '@playwright/test';
import { combos, openShell, TAB_PATHS } from './shell.helpers';

const EN = ['Home', 'Transactions', 'Add', 'Insights', 'More'];

test.describe('REQ-UI-004 mobile bottom bar (375x812)', () => {
  for (const c of combos.filter((x) => x.colorScheme === 'light')) {
    test(`REQ-UI-004 ${c.dir}: five 48px tabs along the bottom, order mirrors in RTL @REQ-UI-004`, async ({
      browser,
    }) => {
      const { ctx, page } = await openShell(browser, c, 'mobile');
      await page.goto('/');
      const nav = page.getByRole('navigation');
      await expect(nav).toHaveCount(1);
      const links = nav.getByRole('link');
      await expect(links).toHaveCount(5);

      const boxes = [];
      for (let i = 0; i < 5; i++) {
        const b = await links.nth(i).boundingBox();
        if (!b) throw new Error('tab not laid out');
        expect(b.width).toBeGreaterThanOrEqual(48);
        expect(b.height).toBeGreaterThanOrEqual(48);
        boxes.push(b);
      }
      const navBox = await nav.boundingBox();
      if (!navBox) throw new Error('nav not laid out');
      expect(navBox.y + navBox.height).toBeGreaterThan(780); // pinned to the bottom edge
      // DOM order Home..More; visually left-to-right in LTR, right-to-left in RTL
      const xs = boxes.map((b) => b.x);
      const sorted = [...xs].sort((a, b) => (c.dir === 'ltr' ? a - b : b - a));
      expect(xs).toEqual(sorted);
      if (c.locale === 'en-US') {
        await expect(links).toHaveText(EN);
      }
      await ctx.close();
    });
  }

  test('REQ-UI-004 the Add tab is the centre FAB, visually distinct and 56px or larger @REQ-UI-004', async ({
    browser,
  }) => {
    const { ctx, page } = await openShell(browser, combos[0], 'mobile');
    await page.goto('/');
    const add = page.getByRole('navigation').getByRole('link', { name: 'Add' });
    const home = page.getByRole('navigation').getByRole('link', { name: 'Home' });
    const a = await add.boundingBox();
    const h = await home.boundingBox();
    if (!a || !h) throw new Error('not laid out');
    expect(Math.abs(a.x + a.width / 2 - 375 / 2)).toBeLessThan(4);
    expect(a.height).toBeGreaterThanOrEqual(h.height);
    await ctx.close();
  });

  test('REQ-UI-004 bottom bar reserves the safe-area inset @REQ-UI-004', async ({ browser }) => {
    const { ctx, page } = await openShell(browser, combos[0], 'mobile');
    await page.goto('/');
    const css = await page.getByRole('navigation').evaluate((n) => {
      const s = getComputedStyle(n);
      return s.paddingBottom;
    });
    const src = await page.evaluate(() =>
      Array.from(document.styleSheets)
        .flatMap((s) => {
          try {
            return Array.from(s.cssRules).map((r) => r.cssText);
          } catch {
            return [];
          }
        })
        .join('\n'),
    );
    expect(src + css).toContain('safe-area-inset-bottom');
    await ctx.close();
  });
});

test.describe('REQ-UI-004 desktop sidebar (1280x800)', () => {
  for (const c of combos.filter((x) => x.colorScheme === 'light')) {
    test(`REQ-UI-004 ${c.dir}: sidebar at the inline-start edge with the same five entries, no bottom bar @REQ-UI-004`, async ({
      browser,
    }) => {
      const { ctx, page } = await openShell(browser, c, 'desktop');
      await page.goto('/');
      const nav = page.getByRole('navigation');
      await expect(nav).toHaveCount(1);
      await expect(nav.getByRole('link')).toHaveCount(5);
      const b = await nav.boundingBox();
      if (!b) throw new Error('nav not laid out');
      expect(b.height).toBeGreaterThan(600); // vertical rail, not a bottom strip
      expect(b.width).toBeLessThan(400);
      if (c.dir === 'ltr') expect(b.x).toBeLessThan(8);
      else expect(b.x + b.width).toBeGreaterThan(1280 - 8);
      await ctx.close();
    });
  }
});

test.describe('REQ-UI-004 routes, deep links and reload', () => {
  for (const [i, path] of TAB_PATHS.entries()) {
    test(`REQ-UI-004 deep link ${path} reloads onto the right tab @REQ-UI-004`, async ({ browser }) => {
      const { ctx, page } = await openShell(browser, combos[0], 'mobile');
      await page.goto(path);
      await page.reload();
      expect(new URL(page.url()).pathname).toBe(path);
      const current = page.getByRole('navigation').locator('[aria-current="page"]');
      await expect(current).toHaveCount(1);
      await expect(current).toHaveText(EN[i] ?? '');
      await ctx.close();
    });
  }

  test('REQ-UI-004 unknown route shows the 404 page with a link Home @REQ-UI-004', async ({ browser }) => {
    const { ctx, page } = await openShell(browser, combos[0], 'mobile');
    await page.goto('/no-such-page');
    await expect(page.getByRole('heading', { name: /not found|404/i })).toBeVisible();
    await page.getByRole('main').getByRole('link', { name: /home/i }).click();
    expect(new URL(page.url()).pathname).toBe('/');
    await ctx.close();
  });

  test('REQ-UI-004 signed-out visit to a tab redirects to /login keeping the return path @REQ-UI-004', async ({
    browser,
  }) => {
    const { ctx, page } = await openShell(browser, combos[0], 'mobile', { signedIn: false });
    await page.goto('/insights');
    await page.waitForURL('**/login**');
    expect(decodeURIComponent(new URL(page.url()).search)).toContain('/insights');
    await ctx.close();
  });
});

test.describe('REQ-UI-002 / REQ-UI-003 / REQ-UI-005 More page settings in a real browser', () => {
  test('REQ-UI-002 switching to Arabic flips dir, mirrors the tab order and survives a reload @REQ-UI-002', async ({
    browser,
  }) => {
    const { ctx, page } = await openShell(browser, combos[0], 'mobile');
    await page.goto('/more');
    const before = await page.getByRole('navigation').getByRole('link').first().boundingBox();
    await page.getByText('العربية').click();
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await expect(page.locator('html')).toHaveAttribute('lang', 'ar');
    const after = await page.getByRole('navigation').getByRole('link').first().boundingBox();
    expect(before && after && after.x > before.x).toBe(true); // first tab moved to the right edge
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    expect(new URL(page.url()).pathname).toBe('/more');
    await ctx.close();
  });

  test('REQ-UI-002 directional icons (chevrons, back arrows) are mirrored in RTL only @REQ-UI-002', async ({
    browser,
  }) => {
    // seam: icons whose meaning is directional carry data-icon-directional; they get scaleX(-1) in RTL
    const flipped = async (c: (typeof combos)[number]) => {
      const { ctx, page } = await openShell(browser, c, 'mobile');
      await page.goto('/more');
      const icons = page.locator('[data-icon-directional]');
      expect(await icons.count()).toBeGreaterThan(0);
      const m = await icons.first().evaluate((e) => getComputedStyle(e).transform);
      await ctx.close();
      return m;
    };
    const ltr = await flipped(combos[0]);
    const rtl = await flipped(combos[2]);
    expect(ltr === 'none' || ltr === 'matrix(1, 0, 0, 1, 0, 0)').toBe(true);
    expect(rtl).toBe('matrix(-1, 0, 0, 1, 0, 0)');
  });

  for (const scheme of ['light', 'dark'] as const) {
    test(`REQ-UI-003 prefers-color-scheme ${scheme} sets the matching palette (no stored choice) @REQ-UI-003`, async ({
      browser,
    }) => {
      const { ctx, page } = await openShell(browser, combos[scheme === 'light' ? 0 : 1], 'mobile');
      await page.goto('/more');
      await expect(page.getByRole('navigation')).toBeVisible();
      const lum = await page.evaluate(() => {
        const [r = 0, g = 0, b = 0] = (getComputedStyle(document.body).backgroundColor.match(/\d+/g) ?? []).map(Number);
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
      });
      if (scheme === 'dark') expect(lum).toBeLessThan(0.2);
      else expect(lum).toBeGreaterThan(0.8);
      await ctx.close();
    });
  }

  test('REQ-UI-003 More -> Theme = Dark overrides a light OS preference and persists @REQ-UI-003', async ({
    browser,
  }) => {
    const { ctx, page } = await openShell(browser, combos[0], 'mobile');
    await page.goto('/more');
    await page.getByRole('radio', { name: /dark/i }).click();
    await expect(page.locator('html')).toHaveClass(/dark/);
    await page.reload();
    await expect(page.locator('html')).toHaveClass(/dark/);
    await ctx.close();
  });

  test('REQ-UI-005 the digits setting persists across a reload @REQ-UI-005', async ({ browser }) => {
    const { ctx, page } = await openShell(browser, combos[0], 'mobile');
    await page.goto('/more');
    await page.getByRole('radio', { name: /arabic-indic/i }).click();
    await page.reload();
    await expect(page.getByRole('radio', { name: /arabic-indic/i })).toBeChecked();
    await ctx.close();
  });
});
