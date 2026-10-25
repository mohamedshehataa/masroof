/**
 * Test seam for "an authenticated visitor" (login itself is STORY-018). STORY-017 tests need a signed-in session to
 * reach the tabs, and a signed-out one to assert the /login redirect. Contract chosen by test-designer: the shell
 * treats a non-empty JSON object under this localStorage key as an active session. If the architect picks a different
 * storage seam, change SESSION_KEY / the payload here and in e2e/shell.helpers.ts only.
 */
export const SESSION_KEY = 'masroof.session';
export const SESSION_VALUE = JSON.stringify({ accessToken: 'test-access', refreshToken: 'test-refresh' });

export function signIn(): void {
  localStorage.setItem(SESSION_KEY, SESSION_VALUE);
}

/** jsdom has no matchMedia; the shell may read prefers-color-scheme through it. */
export function stubMatchMedia(prefersDark: boolean): void {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: (query: string) => ({
      matches: query.includes('prefers-color-scheme: dark') ? prefersDark : false,
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    }),
  });
}
