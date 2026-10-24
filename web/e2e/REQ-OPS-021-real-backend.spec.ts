import { execSync } from 'node:child_process';
import { expect, test } from '@playwright/test';

// Requires the STORY-143 dev-overlay backend on http://127.0.0.1:8080 (Vite proxies /health there). No route mocks.
test.describe('REQ-OPS-021 real backend through the Vite proxy', () => {
  test.use({ locale: 'en-US' });

  test('REQ-OPS-021 page shows the real backend version and short git sha @REQ-OPS-021', async ({ page, request }) => {
    const sha = execSync('git rev-parse HEAD', { encoding: 'utf8' }).trim().slice(0, 7);
    const direct = await request.get('/health');
    expect(direct.ok()).toBe(true);
    const body = (await direct.json()) as { version: string };
    await page.goto('/');
    await expect(page.getByText(`Backend ${body.version} (${sha})`)).toBeVisible();
  });
});
