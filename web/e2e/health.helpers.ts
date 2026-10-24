import type { Page, Route } from '@playwright/test';

export const good = {
  status: 'UP',
  version: '0.1.0',
  gitSha: '1a2b3c4d5e6f',
  buildTime: '2026-10-24T10:00:00Z',
  time: '2026-10-24T12:00:00Z',
  checks: { db: 'UP' },
};

export const EN_VERSION = 'Backend 0.1.0 (1a2b3c4)';
export const AR_VERSION = 'الخادم 0.1.0 (1a2b3c4)';
export const EN_ERROR = "Can't reach the server.";
export const EN_OFFLINE = "You're offline. Check your connection.";

export type Handler = (route: Route) => Promise<void>;

export const ok: Handler = (route) =>
  route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(good) });
export const problem503: Handler = (route) =>
  route.fulfill({
    status: 503,
    contentType: 'application/problem+json',
    body: JSON.stringify({ type: 'about:blank', title: 'Service Unavailable', status: 503 }),
  });
export const htmlError =
  (status: number): Handler =>
  (route) =>
    route.fulfill({ status, contentType: 'text/html', body: '<html><body>upstream error</body></html>' });
export const netFail: Handler = (route) => route.abort('failed');
export const json200 =
  (body: unknown): Handler =>
  (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });

/** Routes GET /health through a script of handlers (last one repeats) and counts requests. */
export async function mockHealth(page: Page, ...handlers: Handler[]) {
  const state = { count: 0 };
  await page.route('**/health', async (route) => {
    const h = handlers[Math.min(state.count, handlers.length - 1)];
    state.count += 1;
    if (!h) throw new Error('no handler');
    await h(route);
  });
  return state;
}
