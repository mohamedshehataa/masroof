import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

type SessionModule = typeof import('./session');

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

// A fresh module per test: the session status is derived from sessionStorage at import time.
async function load(storedRefresh: string | null): Promise<SessionModule> {
  vi.resetModules();
  window.sessionStorage.clear();
  if (storedRefresh !== null) window.sessionStorage.setItem('masroof.rt', storedRefresh);
  return import('./session');
}

afterEach(() => {
  vi.unstubAllGlobals();
  Reflect.deleteProperty(navigator, 'locks');
  vi.restoreAllMocks();
  vi.useRealTimers();
  window.sessionStorage.clear();
});

describe('REQ-UI-011 restoring a session while offline', () => {
  it('stays on the restoring state, retries with backoff and on the online event, never drops to login', async () => {
    vi.useFakeTimers();
    const s = await load('r1');
    expect(s.getSessionStatus()).toBe('restoring');
    const fetchMock = vi.fn(() => Promise.reject(new TypeError('offline')));
    vi.stubGlobal('fetch', fetchMock);
    await s.restoreSession();
    expect(s.getSessionStatus()).toBe('restoring');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(2_100);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(s.getSessionStatus()).toBe('restoring');
    // backoff doubles: the next attempt is not due after another 2 s
    await vi.advanceTimersByTimeAsync(2_100);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(json(200, pairBody('2')))),
    );
    window.dispatchEvent(new Event('online'));
    await vi.advanceTimersByTimeAsync(10);
    expect(s.getSessionStatus()).toBe('in');
    expect(s.getAccessToken()).toBe('a2');
  });

  it('goes to login when the backend rejects the stored token', async () => {
    const s = await load('r1');
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(json(401, { type: 'about:blank', title: 'x', status: 401 }))),
    );
    await s.restoreSession();
    expect(s.getSessionStatus()).toBe('out');
    expect(window.sessionStorage.getItem('masroof.rt')).toBeNull();
  });
});

describe('REQ-UI-011 private mode', () => {
  it('flags a volatile session when the refresh token cannot be stored', async () => {
    const s = await load(null);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    s.setSession({ accessToken: 'a1', refreshToken: 'r1', expiresIn: 900 });
    expect(s.hasSession()).toBe(true);
    expect(s.isPersistFailed()).toBe(true);
    s.clearSession();
    expect(s.isPersistFailed()).toBe(false);
  });
});

describe('REQ-UI-011 sign out with an expired access token', () => {
  let s: SessionModule;
  beforeEach(async () => {
    s = await load(null);
  });

  it('sends the refresh token in the body, does not try to refresh, and clears state on 401', async () => {
    s.setSession({ accessToken: 'expired', refreshToken: 'r1', expiresIn: 900 });
    const calls: { path: string; body: unknown }[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init: RequestInit) => {
        calls.push({
          path: new URL(url).pathname,
          body: init.body === null ? null : JSON.parse(new TextDecoder().decode(init.body as ArrayBuffer)),
        });
        return Promise.resolve(json(401, { type: 'about:blank', title: 'x', status: 401 }));
      }),
    );
    await s.signOut();
    expect(calls).toEqual([{ path: '/auth/logout', body: { refreshToken: 'r1' } }]);
    expect(s.hasSession()).toBe(false);
  });
});

describe('REQ-UI-011 duplicated tabs share one refresh', () => {
  class FakeChannel {
    static all: FakeChannel[] = [];
    onmessage: ((e: MessageEvent) => void) | null = null;
    posted: unknown[] = [];
    constructor() {
      FakeChannel.all.push(this);
    }
    postMessage(m: unknown) {
      this.posted.push(m);
    }
    close() {
      return undefined;
    }
  }
  const stubLocks = (before?: () => void) => {
    const request = vi.fn(async (_name: string, cb: () => Promise<unknown>) => {
      before?.();
      return cb();
    });
    Object.defineProperty(navigator, 'locks', { value: { request }, configurable: true });
    vi.stubGlobal('BroadcastChannel', FakeChannel);
    return request;
  };
  beforeEach(() => {
    FakeChannel.all = [];
  });

  it('refreshes under the Web Lock and hands the rotated pair to sibling tabs', async () => {
    const lock = stubLocks();
    const s = await load(null);
    s.setSession({ accessToken: 'a1', refreshToken: 'r1', expiresIn: 900 });
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(json(200, pairBody('2')))),
    );
    expect(await s.refreshOnce()).toBe('ok');
    expect(lock).toHaveBeenCalledWith('masroof-refresh', expect.any(Function));
    expect(FakeChannel.all[0]?.posted[0]).toMatchObject({ prev: 'r1', pair: { refreshToken: 'r2' } });
  });

  it('adopts a sibling rotation of its own token, ignores unrelated ones, and skips a refresh it waited out', async () => {
    const deliver = (prev: string, n: string) => {
      FakeChannel.all[0]?.onmessage?.({ data: { prev, pair: pairBody(n) } } as MessageEvent);
    };
    stubLocks(() => {
      deliver('r1', '2'); // the other tab finished its refresh while this tab waited for the lock
    });
    const s = await load(null);
    s.initCrossTab();
    s.setSession({ accessToken: 'a1', refreshToken: 'r1', expiresIn: 900 });
    const fetchMock = vi.fn(() => Promise.resolve(json(200, pairBody('9'))));
    vi.stubGlobal('fetch', fetchMock);
    expect(await s.refreshOnce()).toBe('ok');
    expect(fetchMock).not.toHaveBeenCalled();
    expect(s.getAccessToken()).toBe('a2');
    deliver('someone-else', '7');
    expect(s.getAccessToken()).toBe('a2');
  });
});
