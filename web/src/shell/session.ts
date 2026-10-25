import { useSyncExternalStore } from 'react';
import { readStored, subscribeStored, writeStored } from './storage';

/** Seam for STORY-018: a non-empty JSON object under this key means an authenticated visitor. */
export const SESSION_KEY = 'masroof.session';

export function parseSession(raw: string | null): boolean {
  if (raw === null) return false;
  try {
    const value: unknown = JSON.parse(raw);
    return typeof value === 'object' && value !== null && !Array.isArray(value) && Object.keys(value).length > 0;
  } catch {
    return false;
  }
}

export function useIsSignedIn(): boolean {
  return parseSession(useSyncExternalStore(subscribeStored, () => readStored(SESSION_KEY)));
}

export function signOut(): void {
  writeStored(SESSION_KEY, null);
}
