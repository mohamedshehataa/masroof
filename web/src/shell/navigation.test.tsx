import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { byFullText } from '../test/matchers';
import { renderAt, useShellEnv } from './shell.helpers';

const TABS = [
  ['Home', '/'],
  ['Transactions', '/transactions'],
  ['Add', '/add'],
  ['Insights', '/insights'],
  ['More', '/more'],
] as const;

describe('REQ-UI-004 five-tab navigation', () => {
  useShellEnv();

  it('REQ-UI-004 a navigation landmark has the five entries in order, each linking to its route', async () => {
    renderAt('/');
    const navs = await screen.findAllByRole('navigation');
    for (const nav of navs) {
      const links = within(nav).getAllByRole('link');
      expect(links.map((l) => l.textContent.trim())).toEqual(TABS.map(([n]) => n));
      expect(links.map((l) => new URL(l.getAttribute('href') ?? '', 'http://x').pathname)).toEqual(
        TABS.map(([, p]) => p),
      );
    }
  });

  it.each(TABS)('REQ-UI-004 deep link to %s (%s) marks that tab aria-current=page and no other', async (name, path) => {
    renderAt(path);
    const nav = (await screen.findAllByRole('navigation'))[0];
    if (!nav) throw new Error('no navigation');
    const current = within(nav)
      .getAllByRole('link')
      .filter((l) => l.getAttribute('aria-current') === 'page');
    expect(current.map((l) => l.textContent.trim())).toEqual([name]);
  });

  it('REQ-UI-004 clicking a tab navigates without reload and moves aria-current', async () => {
    const user = userEvent.setup();
    renderAt('/');
    const nav = (await screen.findAllByRole('navigation'))[0];
    if (!nav) throw new Error('no navigation');
    await user.click(within(nav).getByRole('link', { name: 'Insights' }));
    expect(window.location.pathname).toBe('/insights');
    await waitFor(() => {
      expect(within(nav).getByRole('link', { name: 'Insights' })).toHaveAttribute('aria-current', 'page');
    });
    expect(within(nav).getByRole('link', { name: 'Home' })).not.toHaveAttribute('aria-current');
  });

  it('REQ-UI-004 the home tab still shows the STORY-141 version line', async () => {
    renderAt('/');
    expect(await screen.findByText(byFullText('Backend 0.1.0 (1a2b3c4)'))).toBeInTheDocument();
  });

  it('REQ-UI-004 the shell paints before data arrives (loading: no blank screen)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>(() => undefined)),
    );
    renderAt('/');
    expect((await screen.findAllByRole('navigation')).length).toBeGreaterThan(0);
  });

  it('REQ-UI-004 offline shows a top banner "You\'re offline"', async () => {
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(false);
    renderAt('/more');
    window.dispatchEvent(new Event('offline'));
    expect(await screen.findByText("You're offline")).toBeInTheDocument();
  });

  it('REQ-UI-004 the banner is gone when back online', async () => {
    const onLine = vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(false);
    renderAt('/more');
    window.dispatchEvent(new Event('offline'));
    await screen.findByText("You're offline");
    onLine.mockReturnValue(true);
    window.dispatchEvent(new Event('online'));
    await waitFor(() => {
      expect(screen.queryByText("You're offline")).toBeNull();
    });
  });
});

describe('REQ-UI-004 unknown route', () => {
  useShellEnv();

  it('REQ-UI-004 renders an English 404 with a link Home', async () => {
    renderAt('/definitely-not-a-page');
    expect(await screen.findByRole('heading', { name: /not found|404/i })).toBeInTheDocument();
    const main = screen.getByRole('main');
    expect(within(main).getByRole('link', { name: /home/i }).getAttribute('href')).toBe('/');
  });
});

describe('REQ-UI-004 unknown route in Arabic', () => {
  useShellEnv({ browserLanguage: 'ar-EG' });

  it('REQ-UI-004 the 404 page is Arabic (bilingual) with a link Home', async () => {
    renderAt('/definitely-not-a-page');
    const main = await screen.findByRole('main');
    expect(within(main).getByRole('heading').textContent).not.toMatch(/[A-Za-z]/);
    const link = within(main).getByRole('link');
    expect(link.getAttribute('href')).toBe('/');
    expect(link.textContent).not.toMatch(/[A-Za-z]/);
  });
});

describe('REQ-UI-004 unauthenticated visit', () => {
  useShellEnv({ signedIn: false });

  it.each(['/', '/transactions', '/add', '/insights', '/more'])(
    'REQ-UI-004 %s redirects to /login preserving the return path',
    async (path) => {
      renderAt(path);
      await waitFor(() => {
        expect(window.location.pathname).toBe('/login');
      });
      if (path !== '/') {
        expect(decodeURIComponent(window.location.search + window.location.hash)).toContain(path);
      }
    },
  );

  it('REQ-UI-004 the return path keeps its query string', async () => {
    renderAt('/transactions?filter=review');
    await waitFor(() => {
      expect(window.location.pathname).toBe('/login');
    });
    expect(decodeURIComponent(window.location.search)).toContain('/transactions?filter=review');
  });

  it('REQ-UI-004 does not render the tab bar while signed out', async () => {
    renderAt('/more');
    await waitFor(() => {
      expect(window.location.pathname).toBe('/login');
    });
    expect(screen.queryByRole('link', { name: 'Transactions' })).toBeNull();
  });
});
