import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, vi } from 'vitest';
import App from '../App';
import { signIn, stubMatchMedia } from '../test/session';

export const healthOk = {
  status: 'UP',
  version: '0.1.0',
  gitSha: '1a2b3c4d5e6f',
  buildTime: '2026-10-24T10:00:00Z',
  time: '2026-10-24T12:00:00Z',
  checks: { db: 'UP' },
};

export function stubHealth(): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve(
        new Response(JSON.stringify(healthOk), { status: 200, headers: { 'content-type': 'application/json' } }),
      ),
    ),
  );
}

/** Common per-test setup: clean storage/DOM, browser language, health stub, signed-in unless told otherwise. */
export function useShellEnv(opts: { browserLanguage?: string; signedIn?: boolean; prefersDark?: boolean } = {}): void {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('class');
    vi.spyOn(window.navigator, 'language', 'get').mockReturnValue(opts.browserLanguage ?? 'en-US');
    stubMatchMedia(opts.prefersDark ?? false);
    stubHealth();
    if (opts.signedIn ?? true) signIn();
    window.history.replaceState({}, '', '/');
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
}

export function renderAt(path: string) {
  window.history.replaceState({}, '', path);
  return render(<App />);
}
