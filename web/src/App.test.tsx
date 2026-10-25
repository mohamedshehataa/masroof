import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { byFullText } from './test/matchers';
import { signIn } from './test/session';

const good = {
  status: 'UP',
  version: '0.1.0',
  gitSha: '1a2b3c4d5e6f',
  buildTime: '2026-10-24T10:00:00Z',
  time: '2026-10-24T12:00:00Z',
  checks: { db: 'UP' },
};
const EN_ERROR = "Can't reach the server.";
const EN_OFFLINE = "You're offline. Check your connection.";
const EN_VERSION = 'Backend 0.1.0 (1a2b3c4)';

type Step = () => Promise<Response>;
const json =
  (status: number, body: unknown, ct = 'application/json'): Step =>
  () =>
    Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': ct } }));
const html =
  (status: number): Step =>
  () =>
    Promise.resolve(new Response('<html>upstream error</html>', { status, headers: { 'content-type': 'text/html' } }));
const netFail: Step = () => Promise.reject(new TypeError('Failed to fetch'));
const pending: Step = () => new Promise<Response>(() => undefined);

let fetchMock: ReturnType<typeof vi.fn>;
function script(...steps: Step[]) {
  let i = 0;
  fetchMock = vi.fn(() => {
    const step = steps[Math.min(i, steps.length - 1)];
    i += 1;
    if (!step) throw new Error('empty script');
    return step();
  });
  vi.stubGlobal('fetch', fetchMock);
}
function setLanguage(lang: string) {
  vi.spyOn(window.navigator, 'language', 'get').mockReturnValue(lang);
}
function setOnline(online: boolean) {
  vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(online);
}

// App-shell setup (STORY-017): a signed-in visitor, no stored language/theme, starting at Home (/).
beforeEach(() => {
  localStorage.clear();
  signIn();
  window.history.replaceState({}, '', '/');
  setLanguage('en-US');
});

// The language switch now lives in More -> Language; the version line lives on Home. Client-side navigation only.
async function goTo(user: ReturnType<typeof userEvent.setup>, path: string) {
  const link = document.querySelector(`nav a[href="${path}"]`);
  if (!link) throw new Error(`no tab link to ${path}`);
  await user.click(link);
}
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('REQ-OPS-021 version page states', () => {
  it('REQ-OPS-021 sends exactly one GET /health and shows the English version line', async () => {
    script(json(200, good));
    render(<App />);
    expect(await screen.findByText(byFullText(EN_VERSION))).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(document.documentElement).toHaveAttribute('lang', 'en');
    expect(document.documentElement).toHaveAttribute('dir', 'ltr');
    expect(screen.queryByText('الخادم', { exact: false })).toBeNull();
  });

  it.each([
    ['ar-EG', 'ar', 'rtl', 'الخادم 0.1.0 (1a2b3c4)'],
    ['en-US', 'en', 'ltr', EN_VERSION],
    ['fr-FR', 'en', 'ltr', EN_VERSION],
  ])('REQ-OPS-021 navigator.language %s gives lang=%s dir=%s', async (nav, lang, dir, copy) => {
    setLanguage(nav);
    script(json(200, good));
    render(<App />);
    expect(await screen.findByText(byFullText(copy))).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute('lang', lang);
    expect(document.documentElement).toHaveAttribute('dir', dir);
  });

  it('REQ-OPS-021 wraps version and SHA in bdi dir=ltr', async () => {
    script(json(200, good));
    const { container } = render(<App />);
    await screen.findByText(byFullText(EN_VERSION));
    const bdis = Array.from(container.querySelectorAll('bdi[dir="ltr"]')).map((b) => b.textContent);
    expect(bdis).toEqual(['0.1.0', '1a2b3c4']);
  });

  it('REQ-OPS-021 toggles language without reload and back', async () => {
    script(json(200, good));
    const user = userEvent.setup();
    render(<App />);
    await screen.findByText(byFullText(EN_VERSION));
    await goTo(user, '/more');
    await user.click(await screen.findByText('العربية'));
    await goTo(user, '/');
    expect(await screen.findByText(byFullText('الخادم 0.1.0 (1a2b3c4)'))).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute('lang', 'ar');
    expect(document.documentElement).toHaveAttribute('dir', 'rtl');
    await goTo(user, '/more');
    await user.click(await screen.findByText('English'));
    await goTo(user, '/');
    expect(await screen.findByText(byFullText(EN_VERSION))).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute('dir', 'ltr');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('REQ-OPS-021 shows a busy status skeleton while /health is in flight', () => {
    script(pending);
    render(<App />);
    const skeleton = screen.getByRole('status');
    expect(skeleton).toHaveAttribute('aria-busy', 'true');
    expect(document.body.textContent).not.toMatch(/undefined|health\.version/);
  });

  const failures: [string, Step][] = [
    [
      '503 problem+json',
      json(503, { type: 'about:blank', title: 'Service Unavailable', status: 503 }, 'application/problem+json'),
    ],
    ['502 html', html(502)],
    ['504 html', html(504)],
    ['network failure', netFail],
    ['200 status DOWN', json(200, { ...good, status: 'DOWN' })],
    ['200 missing version', json(200, { ...good, version: undefined })],
    ['200 gitSha XYZ', json(200, { ...good, gitSha: 'XYZ' })],
  ];
  it.each(failures)(
    'REQ-OPS-021 %s shows error + Retry, no auto-retry, Retry sends exactly one request',
    async (_n, bad) => {
      script(bad, pending, json(200, good));
      const user = userEvent.setup();
      render(<App />);
      expect(await screen.findByText(EN_ERROR)).toBeInTheDocument();
      expect(screen.queryByRole('status')).toBeNull();
      expect(fetchMock).toHaveBeenCalledTimes(1);

      await user.click(screen.getByRole('button', { name: 'Retry' }));
      expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true');
      expect(fetchMock).toHaveBeenCalledTimes(2);
    },
  );

  it('REQ-OPS-021 Retry after a failure renders the version on 200', async () => {
    script(json(503, { type: 'about:blank', title: 'x', status: 503 }, 'application/problem+json'), json(200, good));
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole('button', { name: 'Retry' }));
    expect(await screen.findByText(byFullText(EN_VERSION))).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('REQ-OPS-021 offline Retry shows offline copy, and the online event refetches exactly once', async () => {
    script(
      json(503, { type: 'about:blank', title: 'x', status: 503 }, 'application/problem+json'),
      // later calls depend on connectivity, whether or not Retry hits the network while offline
      () => (navigator.onLine ? json(200, good)() : netFail()),
    );
    const user = userEvent.setup();
    render(<App />);
    await screen.findByText(EN_ERROR);
    setOnline(false);
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText(EN_OFFLINE)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByText(EN_ERROR)).toBeNull();

    const before = fetchMock.mock.calls.length;
    setOnline(true);
    window.dispatchEvent(new Event('online'));
    expect(await screen.findByText(byFullText(EN_VERSION))).toBeInTheDocument();
    await waitFor(() => {
      expect(fetchMock.mock.calls.length - before).toBe(1);
    });
  });
});
