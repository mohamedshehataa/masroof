// Session: the access token lives in memory only. The refresh token (the backend returns it in the JSON body and
// sets no cookie) is kept in sessionStorage so a reload restores the session silently; it is cleared on tab or
// installed-app close and never goes to localStorage (docs/DECISIONS.md, STORY-018).
// Duplicated tabs copy sessionStorage, so refreshes are serialized across tabs with a Web Lock and the rotated
// pair is handed to the sibling tabs over a BroadcastChannel; otherwise the second tab would present an already
// rotated token and trip the backend's reuse detection (which revokes the whole family).
import type { Member, TokenPair } from './generated';

interface Session {
  accessToken: string;
  refreshToken: string;
  member: Member | null;
}

export type SessionStatus = 'out' | 'restoring' | 'in';
export type RefreshOutcome = 'ok' | 'rejected' | 'network';

const RT_KEY = 'masroof.rt';
const LOCK_NAME = 'masroof-refresh';
const CHANNEL_NAME = 'masroof-session';
const REFRESH_MARGIN_S = 30;
const MIN_REFRESH_MS = 5_000;
const NETWORK_RETRY_MS = 15_000;
const RESTORE_RETRY_BASE_MS = 2_000;
const RESTORE_RETRY_MAX_MS = 60_000;
const MS_PER_S = 1000;

let persistFailed = false;

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
    // storage unavailable (private mode): the session then lasts until reload, and the UI says so
    if (value !== null) persistFailed = true;
  }
}

let session: Session | null = null;
let status: SessionStatus = readRefreshToken() === null ? 'out' : 'restoring';
let timer: ReturnType<typeof setTimeout> | undefined;
let inflight: Promise<RefreshOutcome> | null = null;
let restoreAttempts = 0;
let adopted = 0;
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
/** True when the refresh token could not be stored (private mode): a reload signs the user out. */
export const isPersistFailed = (): boolean => persistFailed;

export function subscribeSession(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function clearSession(): void {
  session = null;
  status = 'out';
  persistFailed = false;
  restoreAttempts = 0;
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
  restoreAttempts = 0;
  writeRefreshToken(pair.refreshToken);
  schedule(Math.max(MIN_REFRESH_MS, (pair.expiresIn - REFRESH_MARGIN_S) * MS_PER_S));
  emit();
}

// ---- cross-tab coordination (only where Web Locks exist; plain browsers fall back to per-tab refresh)
interface Handoff {
  prev: string;
  pair: Pair;
}

const crossTab = (): boolean => typeof navigator !== 'undefined' && 'locks' in navigator;
let channel: BroadcastChannel | null = null;

function getChannel(): BroadcastChannel | null {
  if (!crossTab() || typeof BroadcastChannel === 'undefined') return null;
  if (channel === null) {
    channel = new BroadcastChannel(CHANNEL_NAME);
    channel.onmessage = (e: MessageEvent<Handoff>) => {
      // Adopt only a rotation of the token this tab holds (same family); ignore other logins.
      if (session !== null && e.data.prev === session.refreshToken) {
        adopted += 1;
        setSession(e.data.pair);
      }
    };
  }
  return channel;
}

/** Start listening for sibling-tab rotations (call once on app start). */
export function initCrossTab(): void {
  getChannel();
}

async function withRefreshLock<T>(run: () => Promise<T>, alreadyDone: () => T | null): Promise<T> {
  if (!crossTab()) return run();
  return navigator.locks.request(LOCK_NAME, async () => alreadyDone() ?? (await run()));
}

/**
 * Single-flight refresh through the generated client: parallel callers (timer, several 401s) share one request,
 * because refresh tokens rotate and a second use of the old token would revoke the whole family.
 * 'rejected' (the backend said 4xx, including reuse of a rotated token) ends the session; 'network' keeps it.
 */
export function refreshOnce(): Promise<RefreshOutcome> {
  inflight ??= (() => {
    const before = adopted;
    return withRefreshLock(doRefresh, () => (adopted === before ? null : 'ok')).finally(() => {
      inflight = null;
    });
  })();
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
      const pair = await refreshToken({ body: { refreshToken: token } });
      setSession(pair);
      getChannel()?.postMessage({ prev: token, pair } satisfies Handoff);
      return 'ok';
    } catch (e) {
      const { status: code } = apiErrorOf(e);
      if (code < 400 || code >= 500) throw e;
      clearSession();
      return 'rejected';
    }
  } catch {
    if (session !== null) schedule(NETWORK_RETRY_MS);
    else retryRestore();
    return 'network';
  }
}

/** Offline (or server down) while restoring: stay on the skeleton and retry with backoff and on `online`. */
function retryRestore() {
  restoreAttempts += 1;
  const delay = Math.min(RESTORE_RETRY_MAX_MS, RESTORE_RETRY_BASE_MS * 2 ** (restoreAttempts - 1));
  schedule(delay);
}

if (typeof window !== 'undefined') {
  window.addEventListener('online', () => {
    if (status === 'restoring') void refreshOnce();
  });
}

export async function refreshSession(): Promise<boolean> {
  return (await refreshOnce()) === 'ok';
}

/** On app start: trade a stored refresh token for a fresh pair (the UI shows a skeleton while restoring). */
export async function restoreSession(): Promise<void> {
  if (status !== 'restoring') return;
  await refreshOnce();
}

/**
 * Revoke the refresh token on the backend (best effort, the token goes in the body so no bearer is needed), then
 * clear local state whatever the answer was (offline, 401 with an expired access token, 204).
 */
export async function signOut(): Promise<void> {
  const token = session?.refreshToken;
  try {
    if (token !== undefined) {
      const { logout } = await import('./generated');
      await logout({ body: { refreshToken: token } });
    }
  } catch {
    // any failure: local sign-out still proceeds; the refresh token expires server-side
  } finally {
    clearSession();
  }
}
