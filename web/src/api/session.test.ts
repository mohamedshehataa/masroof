import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearSession, getAccessToken, hasSession, refreshSession, setSession } from './session';

const pair = (n: string) => ({ accessToken: `a${n}`, refreshToken: `r${n}`, expiresIn: 900 });

afterEach(() => {
  clearSession();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('REQ-UI-002 in-memory session', () => {
  it('holds the token in memory only and clears it', () => {
    setSession(pair('1'));
    expect(hasSession()).toBe(true);
    expect(getAccessToken()).toBe('a1');
    expect(window.localStorage.length).toBe(0);
    clearSession();
    expect(getAccessToken()).toBeUndefined();
  });

  it('refreshes through the generated client and replaces the pair', async () => {
    setSession(pair('1'));
    const fetchMock = vi.fn(() =>
      Promise.resolve(
        new Response(
          JSON.stringify({
            ...pair('2'),
            tokenType: 'Bearer',
            member: {
              id: '11111111-1111-4111-8111-111111111111',
              householdId: '22222222-2222-4222-8222-222222222222',
              displayName: 'Mohamed',
              role: 'ADMIN',
            },
          }),
          { status: 200, headers: { 'content-type': 'application/json' } },
        ),
      ),
    );
    vi.stubGlobal('fetch', fetchMock);
    expect(await refreshSession()).toBe(true);
    expect(getAccessToken()).toBe('a2');
  });

  it('ends the session when refresh fails', async () => {
    setSession(pair('1'));
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(new Response('{}', { status: 401, headers: { 'content-type': 'application/json' } })),
      ),
    );
    expect(await refreshSession()).toBe(false);
    expect(hasSession()).toBe(false);
  });
});
