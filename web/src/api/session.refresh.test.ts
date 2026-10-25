import { afterEach, describe, expect, it, vi } from 'vitest';
import { listCategories, logout } from './generated';
import { clearSession, getAccessToken, getMember, hasSession, refreshOnce, setSession, signOut } from './session';

const MEMBER = {
  id: '11111111-1111-4111-8111-111111111111',
  householdId: '22222222-2222-4222-8222-222222222222',
  displayName: 'Mohamed',
  role: 'ADMIN',
};
const pairBody = (n: string) => ({
  accessToken: `a${n}`,
  refreshToken: `r${n}`,
  expiresIn: 900,
  tokenType: 'Bearer',
  member: MEMBER,
});
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
const problem = (status: number) => json(status, { type: 'about:blank', title: 'x', status });

afterEach(() => {
  clearSession();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('REQ-UI-011 single-flight refresh', () => {
  it('shares one /auth/refresh request between parallel callers and keeps the member', async () => {
    setSession({ accessToken: 'a1', refreshToken: 'r1', expiresIn: 900 });
    const fetchMock = vi.fn(() => Promise.resolve(json(200, pairBody('2'))));
    vi.stubGlobal('fetch', fetchMock);
    const outcomes = await Promise.all([refreshOnce(), refreshOnce(), refreshOnce()]);
    expect(outcomes).toEqual(['ok', 'ok', 'ok']);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(getAccessToken()).toBe('a2');
    expect(getMember()?.displayName).toBe('Mohamed');
  });

  it('keeps the session when the network fails, but ends it when the backend rejects (rotated-token reuse)', async () => {
    setSession({ accessToken: 'a1', refreshToken: 'r1', expiresIn: 900 });
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new TypeError('offline'))),
    );
    expect(await refreshOnce()).toBe('network');
    expect(hasSession()).toBe(true);
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(problem(401))),
    );
    expect(await refreshOnce()).toBe('rejected');
    expect(hasSession()).toBe(false);
  });

  it('refreshes silently on the timer shortly before expiry', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn(() => Promise.resolve(json(200, pairBody('2'))));
    vi.stubGlobal('fetch', fetchMock);
    setSession({ accessToken: 'a1', refreshToken: 'r1', expiresIn: 900 });
    await vi.advanceTimersByTimeAsync(869_000);
    expect(fetchMock).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(2_000);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(getAccessToken()).toBe('a2');
  });

  it('stores the refresh token in sessionStorage only, never localStorage', () => {
    setSession({ accessToken: 'a1', refreshToken: 'r1', expiresIn: 900 });
    expect(window.sessionStorage.getItem('masroof.rt')).toBe('r1');
    expect(window.localStorage.length).toBe(0);
    clearSession();
    expect(window.sessionStorage.getItem('masroof.rt')).toBeNull();
  });
});

describe('REQ-UI-011 401 retry', () => {
  const route = (handlers: Record<string, () => Response>) =>
    vi.fn((url: string) => Promise.resolve(handlers[new URL(url).pathname]?.() ?? problem(404)));

  it('refreshes once and retries the request once with the new token; parallel 401s share one refresh', async () => {
    setSession({ accessToken: 'a1', refreshToken: 'r1', expiresIn: 900 });
    const seen: (string | null)[] = [];
    const fetchMock = vi.fn((url: string, init: RequestInit) => {
      const path = new URL(url).pathname;
      if (path === '/auth/refresh') return Promise.resolve(json(200, pairBody('2')));
      const auth = new Headers(init.headers).get('Authorization');
      seen.push(auth);
      return Promise.resolve(auth === 'Bearer a2' ? json(200, { items: [] }) : problem(401));
    });
    vi.stubGlobal('fetch', fetchMock);
    const results = await Promise.all([listCategories(), listCategories()]);
    expect(results).toEqual([{ items: [] }, { items: [] }]);
    expect(fetchMock.mock.calls.filter(([u]) => u.endsWith('/auth/refresh'))).toHaveLength(1);
    expect(seen.sort()).toEqual(['Bearer a1', 'Bearer a1', 'Bearer a2', 'Bearer a2']);
  });

  it('ends the session when the refresh is rejected, without retrying', async () => {
    setSession({ accessToken: 'a1', refreshToken: 'r1', expiresIn: 900 });
    const fetchMock = route({ '/api/categories': () => problem(401), '/auth/refresh': () => problem(401) });
    vi.stubGlobal('fetch', fetchMock);
    await expect(listCategories()).rejects.toMatchObject({ status: 401 });
    expect(hasSession()).toBe(false);
    expect(fetchMock.mock.calls.filter(([u]) => u.endsWith('/api/categories'))).toHaveLength(1);
  });

  it('ends the session when the retried request is still 401 (no refresh loop)', async () => {
    setSession({ accessToken: 'a1', refreshToken: 'r1', expiresIn: 900 });
    const fetchMock = route({ '/api/categories': () => problem(401), '/auth/refresh': () => json(200, pairBody('2')) });
    vi.stubGlobal('fetch', fetchMock);
    await expect(listCategories()).rejects.toMatchObject({ status: 401 });
    expect(fetchMock.mock.calls.filter(([u]) => u.endsWith('/auth/refresh'))).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([u]) => u.endsWith('/api/categories'))).toHaveLength(2);
    expect(hasSession()).toBe(false);
  });

  it('does not refresh when the 401 comes from /auth/ itself', async () => {
    setSession({ accessToken: 'a1', refreshToken: 'r1', expiresIn: 900 });
    const fetchMock = route({ '/auth/logout': () => problem(401) });
    vi.stubGlobal('fetch', fetchMock);
    await expect(logout({ body: { refreshToken: 'r1' } })).rejects.toMatchObject({ status: 401 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('REQ-UI-011 sign out', () => {
  it('calls /auth/logout with the refresh token and clears state even when the call fails', async () => {
    setSession({ accessToken: 'a1', refreshToken: 'r1', expiresIn: 900 });
    let sent: unknown;
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => {
      sent = JSON.parse(new TextDecoder().decode(init.body as ArrayBuffer));
      return Promise.reject(new TypeError('offline'));
    });
    vi.stubGlobal('fetch', fetchMock);
    await signOut();
    expect(sent).toEqual({ refreshToken: 'r1' });
    expect(hasSession()).toBe(false);
    expect(window.sessionStorage.getItem('masroof.rt')).toBeNull();
  });
});
