// In-memory session (never localStorage): a reload signs the user in again.
// The access token feeds the generated client's `auth` callback; refresh runs through the generated client.
import type { TokenPair } from './generated';

interface Session {
  accessToken: string;
  refreshToken: string;
}

let session: Session | null = null;
let timer: ReturnType<typeof setTimeout> | undefined;
const listeners = new Set<() => void>();

const REFRESH_MARGIN_S = 30;
const MIN_REFRESH_MS = 5_000;
const MS_PER_S = 1000;

function emit() {
  listeners.forEach((l) => {
    l();
  });
}

export const getAccessToken = (): string | undefined => session?.accessToken;
export const hasSession = (): boolean => session !== null;

export function subscribeSession(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function clearSession(): void {
  session = null;
  clearTimeout(timer);
  emit();
}

export function setSession(pair: Pick<TokenPair, 'accessToken' | 'refreshToken' | 'expiresIn'>): void {
  session = { accessToken: pair.accessToken, refreshToken: pair.refreshToken };
  clearTimeout(timer);
  const delay = Math.max(MIN_REFRESH_MS, (pair.expiresIn - REFRESH_MARGIN_S) * MS_PER_S);
  timer = setTimeout(() => {
    void refreshSession();
  }, delay);
  emit();
}

/** Refresh through the generated client; any failure ends the session (the app shows the login page). */
export async function refreshSession(): Promise<boolean> {
  const current = session;
  if (!current) return false;
  try {
    // Lazy import: the generated client config imports this module (getAccessToken), so avoid a static cycle.
    const { refreshToken } = await import('./generated');
    setSession(await refreshToken({ body: { refreshToken: current.refreshToken } }));
    return true;
  } catch {
    clearSession();
    return false;
  }
}
