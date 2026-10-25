import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearSession, setSession } from '../api/session';
import { Root } from './Root';

const reply = (status: number, body: unknown): Promise<Response> =>
  Promise.resolve(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'content-type': status >= 400 ? 'application/problem+json' : 'application/json' },
    }),
  );

const MEMBER = {
  id: '11111111-1111-4111-8111-111111111111',
  householdId: '22222222-2222-4222-8222-222222222222',
  displayName: 'Mohamed',
  role: 'ADMIN' as const,
};

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
});
afterEach(() => {
  cleanup();
  clearSession();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('REQ-UI-011 sign out clears the query cache', () => {
  it('REQ-UI-011 signing out and back in refetches instead of serving cached household data', async () => {
    const paths: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn((input: string | URL | Request) => {
        const path = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url)
          .pathname;
        paths.push(path);
        if (path === '/auth/logout') return Promise.resolve(new Response(null, { status: 204 }));
        if (path === '/auth/login')
          return reply(200, {
            accessToken: 'a2',
            refreshToken: 'r2',
            expiresIn: 900,
            tokenType: 'Bearer',
            member: MEMBER,
          });
        return reply(200, { items: [], nextCursor: null });
      }),
    );
    window.history.replaceState(null, '', '/');
    vi.spyOn(window.navigator, 'language', 'get').mockReturnValue('en-US');
    setSession({ accessToken: 'a1', refreshToken: 'r1', expiresIn: 900, member: MEMBER });
    render(<Root />);
    await waitFor(() => {
      expect(paths.filter((p) => p === '/api/expenses').length).toBeGreaterThan(0);
    });
    const before = paths.filter((p) => p === '/api/expenses').length;

    await userEvent.click(screen.getByRole('link', { name: 'More' }));
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await screen.findByRole('button', { name: 'Sign in' });
    expect(paths).toContain('/auth/logout');

    await userEvent.type(screen.getByLabelText('Email'), 'a@b.eg');
    await userEvent.type(screen.getByLabelText('Password', { exact: true }), 'long-enough-pw');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    // Back on a screen that loads expenses (the app returns to the current route, /more -> open Home).
    await userEvent.click(await screen.findByRole('link', { name: 'Home' }));
    await waitFor(() => {
      expect(paths.filter((p) => p === '/api/expenses').length).toBeGreaterThan(before);
    });
  });
});
