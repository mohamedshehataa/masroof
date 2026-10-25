// Session: the access token lives in memory only. The refresh token (the backend returns it in the JSON body and
// sets no cookie) is kept in sessionStorage so a reload restores the session silently; it is cleared on tab or
// installed-app close and never goes to localStorage (docs/DECISIONS.md, STORY-018).
import type { Member, TokenPair } from './generated';

interface Session {
  accessToken: string;
  refreshToken: string;
  member: Member | null;
}

export type SessionStatus = 'out' | 'restoring' | 'in';
export type RefreshOutcome = 'ok' | 'rejected' | 'network';

const RT_KEY = 'masroof.rt';
const REFRESH_MARGIN_S = 30;
const MIN_REFRESH_MS = 5_000;
const NETWORK_RETRY_MS = 15_000;
const MS_PER_S = 1000;

function readRefreshToken(): string | null {
  try {
    return window.sessionStorage.getItem(RT_KEY);
  } catch {
    return null;
  }
}

function writeRefreshToken(value: string | null) {
  try {
    if (value === null) window.sessionStorage.removeItem(RT_KEY);
    else window.sessionStorage.setItem(RT_KEY, value);
  } catch {
    // storage unavailable (private mode): the session then lasts until reload
  }
}

let session: Session | null = null;
let status: SessionStatus = readRefreshToken() === null ? 'out' : 'restoring';
let timer: ReturnType<typeof setTimeout> | undefined;
let inflight: Promise<RefreshOutcome> | null = null;
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((l) => {
    l();
  });
}

export const getAccessToken = (): string | undefined => session?.accessToken;
export const hasSession = (): boolean => session !== null;
export const getMember = (): Member | null => session?.member ?? null;
export const getSessionStatus = (): SessionStatus => status;

export function subscribeSession(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function clearSession(): void {
  session = null;
  status = 'out';
  writeRefreshToken(null);
  clearTimeout(timer);
  emit();
}

function schedule(ms: number) {
  clearTimeout(timer);
  timer = setTimeout(() => {
    void refreshOnce();
  }, ms);
}

type Pair = Pick<TokenPair, 'accessToken' | 'refreshToken' | 'expiresIn'> & { member?: Member };

export function setSession(pair: Pair): void {
  session = {
    accessToken: pair.accessToken,
    refreshToken: pair.refreshToken,
    member: pair.member ?? session?.member ?? null,
  };
  status = 'in';
  writeRefreshToken(pair.refreshToken);
  schedule(Math.max(MIN_REFRESH_MS, (pair.expiresIn - REFRESH_MARGIN_S) * MS_PER_S));
  emit();
}

/**
 * Single-flight refresh through the generated client: parallel callers (timer, several 401s) share one request,
 * because refresh tokens rotate and a second use of the old token would revoke the whole family.
 * 'rejected' (the backend said 4xx, including reuse of a rotated token) ends the session; 'network' keeps it.
 */
export function refreshOnce(): Promise<RefreshOutcome> {
  inflight ??= doRefresh().finally(() => {
    inflight = null;
  });
  return inflight;
}

async function doRefresh(): Promise<RefreshOutcome> {
  const token = session?.refreshToken ?? readRefreshToken();
  if (token === null) {
    clearSession();
    return 'rejected';
  }
  try {
    // Lazy import: the generated client config imports this module (getAccessToken), so avoid a static cycle.
    const { refreshToken } = await import('./generated');
    const { apiErrorOf } = await import('./errors');
    try {
      setSession(await refreshToken({ body: { refreshToken: token } }));
      return 'ok';
    } catch (e) {
      const { status } = apiErrorOf(e);
      if (status < 400 || status >= 500) throw e;
      clearSession();
      return 'rejected';
    }
  } catch {
    if (session !== null) schedule(NETWORK_RETRY_MS);
    else {
      status = 'out';
      emit();
    }
    return 'network';
  }
}

export async function refreshSession(): Promise<boolean> {
  return (await refreshOnce()) === 'ok';
}

/** On app start: trade a stored refresh token for a fresh pair (the UI shows a skeleton while restoring). */
export async function restoreSession(): Promise<void> {
  if (status !== 'restoring') return;
  await refreshOnce();
}

/** Revoke the refresh token on the backend (best effort), then clear local state. */
export async function signOut(): Promise<void> {
  const token = session?.refreshToken;
  try {
    if (token !== undefined) {
      const { logout } = await import('./generated');
      await logout({ body: { refreshToken: token } });
    }
  } catch {
    // offline: local sign-out still proceeds; the refresh token expires server-side
  } finally {
    clearSession();
  }
}
