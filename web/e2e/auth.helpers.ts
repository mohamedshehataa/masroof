import type { Page, Route } from '@playwright/test';

// Mock bodies are shaped exactly like contracts/openapi.yaml (TokenPair, Member, Problem; 429 carries Retry-After).
export const MEMBER = {
  id: '55555555-5555-4555-8555-555555555555',
  householdId: '22222222-2222-4222-8222-222222222222',
  displayName: 'Mohamed',
  role: 'ADMIN',
};
export const GOOD_PASSWORD = 'correct-horse-battery';
export const SEED_REFRESH = 'seed-refresh';

export interface AuthMock {
  /** The only access token the mocked API accepts; set it to anything else to simulate expiry. */
  validAccess: string;
  /** The only refresh token /auth/refresh accepts (rotation: each use replaces it). */
  validRefresh: string;
  loginCalls: number;
  refreshCalls: number;
  logoutBodies: unknown[];
  rateLimit: boolean;
  rotated: number;
}

const problem = (route: Route, status: number, headers: Record<string, string> = {}) =>
  route.fulfill({
    status,
    contentType: 'application/problem+json',
    headers,
    body: JSON.stringify({ type: 'about:blank', title: 'Problem', status }),
  });

function issue(state: AuthMock) {
  state.rotated += 1;
  state.validAccess = `access-${String(state.rotated)}`;
  state.validRefresh = `refresh-${String(state.rotated)}`;
  return {
    accessToken: state.validAccess,
    refreshToken: state.validRefresh,
    expiresIn: 900,
    tokenType: 'Bearer',
    member: MEMBER,
  };
}

/** Start with a stored refresh token so the app restores a session on load (once per tab). */
export async function seedRefreshToken(page: Page, token = SEED_REFRESH): Promise<void> {
  await page.addInitScript((t) => {
    if (window.sessionStorage.getItem('e2e.seeded') === null) {
      window.sessionStorage.setItem('e2e.seeded', '1');
      window.sessionStorage.setItem('masroof.rt', t);
    }
  }, token);
}

export async function mockAuth(page: Page): Promise<AuthMock> {
  const state: AuthMock = {
    validAccess: 'access-0',
    validRefresh: SEED_REFRESH,
    loginCalls: 0,
    refreshCalls: 0,
    logoutBodies: [],
    rateLimit: false,
    rotated: 0,
  };
  await page.route('**/auth/login', async (route) => {
    state.loginCalls += 1;
    if (state.rateLimit) return problem(route, 429, { 'Retry-After': '120' });
    const body = route.request().postDataJSON() as { password?: string };
    if (body.password !== GOOD_PASSWORD) return problem(route, 401, { 'WWW-Authenticate': 'Bearer' });
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(issue(state)) });
  });
  await page.route('**/auth/refresh', async (route) => {
    state.refreshCalls += 1;
    const body = route.request().postDataJSON() as { refreshToken?: string };
    if (body.refreshToken !== state.validRefresh) return problem(route, 401, { 'WWW-Authenticate': 'Bearer' });
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(issue(state)) });
  });
  await page.route('**/auth/logout', async (route) => {
    state.logoutBodies.push(route.request().postDataJSON());
    await route.fulfill({ status: 204 });
  });
  return state;
}

/** 401 + WWW-Authenticate unless the request carries the currently valid access token. */
export async function guarded(route: Route, state: AuthMock): Promise<boolean> {
  if (route.request().headers()['authorization'] === `Bearer ${state.validAccess}`) return true;
  await problem(route, 401, { 'WWW-Authenticate': 'Bearer' });
  return false;
}
