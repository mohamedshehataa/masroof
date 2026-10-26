import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Member } from '../../api/generated';
import { clearSession, getAccessToken, setSession } from '../../api/session';
import { Root } from '../../app/Root';
import { inviteLink } from './MembersSection';

const HH = '22222222-2222-4222-8222-222222222222';
const INV = '44444444-4444-4444-8444-444444444444';
const CODE = 'abcDEF0123456789abcDEF0123456789abcDEF01234';

const admin: Member = {
  id: '33333333-3333-4333-8333-333333333331',
  householdId: HH,
  displayName: 'Mohamed',
  role: 'ADMIN',
};
const mai: Member = { id: '33333333-3333-4333-8333-333333333332', householdId: HH, displayName: 'Mai', role: 'MEMBER' };

const reply = (status: number, body: unknown, headers: Record<string, string> = {}): Promise<Response> =>
  Promise.resolve(
    new Response(status === 204 ? null : JSON.stringify(body), {
      status,
      headers: { 'content-type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
    }),
  );

interface Calls {
  created: unknown[];
  revoked: string[];
  accepted: { url: string; body: unknown }[];
}

function mockApi(opts: { invites?: unknown[]; accept?: () => Promise<Response> } = {}) {
  const calls: Calls = { created: [], revoked: [], accepted: [] };
  let invites = opts.invites ?? [];
  const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
    const req = input instanceof Request ? input : null;
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : (req?.url ?? ''));
    const method = init?.method ?? req?.method ?? 'GET';
    if (url.pathname === '/api/members') return reply(200, [admin, mai]);
    if (url.pathname === '/auth/invites' && method === 'GET') return reply(200, invites);
    if (url.pathname.startsWith('/auth/invites/') && method === 'DELETE') {
      calls.revoked.push(url.pathname.split('/').pop() ?? '');
      invites = [];
      return reply(204, null);
    }
    if (url.pathname === '/auth/invite' && method === 'POST') {
      calls.created.push(url.search);
      return reply(201, { id: INV, code: CODE, displayName: 'Mai', expiresAt: '2026-11-02T10:00:00Z' });
    }
    if (url.pathname === '/auth/invite/accept' && method === 'POST') {
      return (
        opts.accept?.() ??
        reply(201, {
          accessToken: 'new-access',
          refreshToken: 'new-refresh',
          expiresIn: 900,
          tokenType: 'Bearer',
          member: mai,
        })
      );
    }
    return reply(404, { type: 'about:blank', title: 'nf', status: 404 });
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, calls };
}

function go(path: string) {
  window.history.replaceState(null, '', path);
  vi.spyOn(window.navigator, 'language', 'get').mockReturnValue('en-US');
  return render(<Root />);
}

beforeEach(() => {
  window.localStorage.clear();
});
afterEach(() => {
  cleanup();
  clearSession();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('REQ-SEC-002 Members in settings', () => {
  it('REQ-SEC-002 the invite link keeps the code in the fragment, not the query string', () => {
    const link = inviteLink(CODE, 'https://masroof.example');
    expect(link).toBe(`https://masroof.example/invite#${CODE}`);
    expect(new URL(link).search).toBe('');
  });

  it('REQ-SEC-002 an admin sees both members and creates an invite with a copyable link and code', async () => {
    setSession({ accessToken: 'a', refreshToken: 'r', expiresIn: 900, member: admin });
    const { calls } = mockApi();
    const user = userEvent.setup();
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(window.navigator, 'clipboard', { value: { writeText }, configurable: true });
    go('/more');
    const list = await screen.findByRole('list', { name: 'Members' });
    expect(within(list).getByText('Mohamed')).toBeInTheDocument();
    expect(within(list).getByText('Mai')).toBeInTheDocument();
    expect(await screen.findByText('No pending invites.')).toBeInTheDocument();

    await user.type(screen.getByRole('textbox', { name: 'Name' }), 'Mai');
    await user.click(screen.getByRole('button', { name: 'Create invite' }));
    expect(await screen.findByRole('heading', { name: 'Invite for Mai' })).toBeInTheDocument();
    expect(calls.created).toEqual(['']); // no query string on the request
    const linkBox = screen.getByRole('textbox', { name: 'Invite link' });
    expect(linkBox).toHaveValue(`${window.location.origin}/invite#${CODE}`);
    expect(screen.getByRole('textbox', { name: 'Invite code' })).toHaveValue(CODE);

    await user.click(screen.getByRole('button', { name: 'Copy link' }));
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/invite#${CODE}`);
    expect(await screen.findByRole('status', { name: '' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Done' }));
    expect(screen.queryByDisplayValue(CODE)).toBeNull(); // the code is gone from the page
  });

  it('REQ-SEC-002 an admin lists and revokes a pending invite', async () => {
    setSession({ accessToken: 'a', refreshToken: 'r', expiresIn: 900, member: admin });
    const { calls } = mockApi({
      invites: [
        {
          id: INV,
          displayName: 'Sara',
          status: 'PENDING',
          createdAt: '2026-10-26T10:00:00Z',
          expiresAt: '2026-11-02T10:00:00Z',
        },
      ],
    });
    const user = userEvent.setup();
    go('/more');
    await user.click(await screen.findByRole('button', { name: 'Revoke invite for Sara' }));
    await waitFor(() => {
      expect(calls.revoked).toEqual([INV]);
    });
    expect(await screen.findByText('No pending invites.')).toBeInTheDocument();
  });

  it('REQ-SEC-002 a plain member sees the members but no invite controls', async () => {
    setSession({ accessToken: 'a', refreshToken: 'r', expiresIn: 900, member: mai });
    const { fetchMock } = mockApi();
    go('/more');
    expect(await screen.findByRole('list', { name: 'Members' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create invite' })).toBeNull();
    expect(fetchMock.mock.calls.some(([u]) => String(u instanceof Request ? u.url : u).includes('/auth/invites'))).toBe(
      false,
    );
  });
});

describe('REQ-SEC-008 Accept invite page', () => {
  it('REQ-SEC-008 reads the code from the fragment, hides it from the URL, and signs the member in', async () => {
    const { fetchMock } = mockApi();
    const user = userEvent.setup();
    go(`/invite#${CODE}`);
    expect(window.location.hash).toBe(''); // removed from the address bar at once
    await user.type(await screen.findByLabelText('Email'), 'mai@example.com');
    await user.type(screen.getByLabelText('Password'), 'a long enough pass');
    await user.type(screen.getByLabelText('Confirm password'), 'a long enough pass');
    await user.click(screen.getByRole('button', { name: 'Join and sign in' }));
    await waitFor(() => {
      expect(getAccessToken()).toBe('new-access');
    });
    const call = fetchMock.mock.calls.find(([u]) =>
      String(u instanceof Request ? u.url : u).includes('/auth/invite/accept'),
    );
    expect(call).toBeDefined();
    const sent = new TextDecoder().decode(call?.[1]?.body as ArrayBuffer);
    expect(JSON.parse(sent)).toMatchObject({ code: CODE, email: 'mai@example.com', password: 'a long enough pass' });
    expect(String(call?.[0] instanceof Request ? call[0].url : call?.[0])).not.toContain(CODE);
    await waitFor(() => {
      expect(window.location.pathname).toBe('/');
    });
  });

  it('REQ-SEC-008 a short or mismatched password never reaches the server', async () => {
    const { fetchMock } = mockApi();
    const user = userEvent.setup();
    go(`/invite#${CODE}`);
    await user.type(await screen.findByLabelText('Email'), 'mai@example.com');
    await user.type(screen.getByLabelText('Password'), 'short');
    await user.type(screen.getByLabelText('Confirm password'), 'short');
    await user.click(screen.getByRole('button', { name: 'Join and sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('at least 12 characters');
    await user.clear(screen.getByLabelText('Password'));
    await user.type(screen.getByLabelText('Password'), 'a long enough pass');
    await user.click(screen.getByRole('button', { name: 'Join and sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent("passwords don't match");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('REQ-SEC-008 an invalid invite shows one generic message and stays signed out', async () => {
    mockApi({ accept: () => reply(404, { type: 'about:blank', title: 'Not Found', status: 404 }) });
    const user = userEvent.setup();
    go(`/invite#${CODE}`);
    await user.type(await screen.findByLabelText('Email'), 'mai@example.com');
    await user.type(screen.getByLabelText('Password'), 'a long enough pass');
    await user.type(screen.getByLabelText('Confirm password'), 'a long enough pass');
    await user.click(screen.getByRole('button', { name: 'Join and sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('not valid or has expired');
    expect(getAccessToken()).toBeUndefined();
  });

  it('REQ-SEC-008 rate limiting shows the wait time', async () => {
    mockApi({ accept: () => reply(429, { type: 'about:blank', title: 'x', status: 429 }, { 'Retry-After': '120' }) });
    const user = userEvent.setup();
    go(`/invite#${CODE}`);
    await user.type(await screen.findByLabelText('Email'), 'mai@example.com');
    await user.type(screen.getByLabelText('Password'), 'a long enough pass');
    await user.type(screen.getByLabelText('Confirm password'), 'a long enough pass');
    await user.click(screen.getByRole('button', { name: 'Join and sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Try again in 2 minutes/);
  });

  it('REQ-SEC-008 renders Arabic with RTL', async () => {
    window.history.replaceState(null, '', `/invite#${CODE}`);
    vi.spyOn(window.navigator, 'language', 'get').mockReturnValue('ar-EG');
    mockApi();
    render(<Root />);
    expect(await screen.findByRole('heading', { name: 'الانضمام إلى الأسرة' })).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute('dir', 'rtl');
  });
});
