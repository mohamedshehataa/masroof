import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { onlineManager } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Category, Expense } from '../api/generated';
import { cairoToday } from '../lib/dates';
import { clearSession, setSession } from '../api/session';
import { Root } from './Root';

const CAT = '11111111-1111-4111-8111-111111111111';
const HH = '22222222-2222-4222-8222-222222222222';
const uid = (n: string) => `3333333${n}-3333-4333-8333-333333333333`;

const cat: Category = {
  id: CAT,
  householdId: HH,
  nameEn: 'Groceries',
  nameAr: 'بقالة',
  isEssential: true,
  sortOrder: 1,
  archived: false,
};

function expense(id: string, amountMinor: number, occurredAt: string, extra: Partial<Expense> = {}): Expense {
  return {
    id,
    householdId: HH,
    type: 'EXPENSE',
    amount: { amountMinor, currency: 'EGP' },
    occurredAt,
    paymentMethod: 'CASH',
    status: 'CONFIRMED',
    confidence: 1,
    tags: [],
    isRecurring: false,
    splitMode: 'NONE',
    items: [],
    sources: [],
    splits: [],
    createdAt: occurredAt,
    updatedAt: occurredAt,
    categoryId: CAT,
    ...extra,
  };
}

interface Api {
  expenses: (url: URL) => { status: number; body: unknown };
  created: unknown[];
  failPosts: number;
  accountsFail: boolean;
}

const reply = (status: number, body: unknown): Promise<Response> =>
  Promise.resolve(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'content-type': status >= 400 ? 'application/problem+json' : 'application/json' },
    }),
  );

function mockApi(api: Partial<Api> = {}) {
  const created: unknown[] = [];
  const keys: string[] = [];
  let failures = api.failPosts ?? 0;
  const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url);
    if (url.pathname === '/api/categories') return reply(200, { items: [cat] });
    if (url.pathname === '/api/accounts' && api.accountsFail === true)
      return reply(500, { type: 'about:blank', title: 'x', status: 500 });
    if (url.pathname === '/api/accounts') return reply(200, { items: [] });
    if (url.pathname === '/api/expenses' && init?.method === 'POST') {
      keys.push(new Headers(init.headers).get('idempotency-key') ?? '');
      if (failures > 0) {
        failures -= 1;
        return reply(503, { type: 'about:blank', title: 'x', status: 503 });
      }
      created.push(JSON.parse(new TextDecoder().decode(init.body as ArrayBuffer)));
      return reply(201, expense(uid('9'), 100, new Date().toISOString()));
    }
    if (url.pathname === '/api/expenses') {
      const r = (api.expenses ?? (() => ({ status: 200, body: { items: [], nextCursor: null } })))(url);
      return reply(r.status, r.body);
    }
    return reply(404, { type: 'about:blank', title: 'nf', status: 404 });
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, created, keys };
}

function go(path: string, lang = 'en-US') {
  window.history.replaceState(null, '', path);
  vi.spyOn(window.navigator, 'language', 'get').mockReturnValue(lang);
  return render(<Root />);
}

beforeEach(() => {
  window.localStorage.clear();
  // Auth is on by default: these screen tests run signed in (login/session behaviour has its own specs).
  setSession({ accessToken: 'test-access', refreshToken: 'test-refresh', expiresIn: 900 });
});
afterEach(() => {
  cleanup();
  clearSession();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('REQ-UI-020 Home', () => {
  it('REQ-UI-020 shows the month total (sum of minor units) and recent expenses', async () => {
    const now = new Date().toISOString();
    mockApi({
      expenses: (url) =>
        url.searchParams.has('from')
          ? {
              status: 200,
              body: { items: [expense(uid('1'), 12550, now), expense(uid('2'), 450, now)], nextCursor: null },
            }
          : {
              status: 200,
              body: { items: [expense(uid('1'), 12550, now, { merchantName: 'Carrefour' })], nextCursor: null },
            },
    });
    go('/');
    expect(await screen.findByText('130.00 EGP')).toBeInTheDocument();
    expect(await screen.findByText('Carrefour')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Main navigation' })).toBeInTheDocument();
  });

  it('REQ-UI-020 shows the empty state', async () => {
    mockApi();
    go('/');
    expect(await screen.findByText(/No expenses yet/)).toBeInTheDocument();
  });

  it('REQ-UI-020 shows error with retry, and Retry refetches', async () => {
    let fail = true;
    mockApi({
      expenses: () =>
        fail
          ? { status: 500, body: { type: 'about:blank', title: 'x', status: 500 } }
          : { status: 200, body: { items: [], nextCursor: null } },
    });
    go('/');
    await screen.findAllByRole('button', { name: 'Retry' });
    fail = false;
    while (screen.queryAllByRole('button', { name: 'Retry' }).length > 0) {
      await userEvent.click(screen.getAllByRole('button', { name: 'Retry' })[0] as HTMLElement);
    }
    await waitFor(() => {
      expect(screen.getByText(/No expenses yet/)).toBeInTheDocument();
    });
  });

  it('REQ-UI-020 renders Arabic with RTL and Arabic-Indic amounts', async () => {
    const now = new Date().toISOString();
    mockApi({ expenses: () => ({ status: 200, body: { items: [expense(uid('1'), 125000, now)], nextCursor: null } }) });
    go('/', 'ar-EG');
    expect((await screen.findAllByText('١٬٢٥٠٫٠٠ ج.م')).length).toBeGreaterThan(0);
    expect(document.documentElement).toHaveAttribute('dir', 'rtl');
    expect(screen.getByRole('link', { name: 'المعاملات' })).toBeInTheDocument();
  });
});

describe('REQ-UI-021 Transactions', () => {
  it('REQ-UI-021 groups by Cairo day, searches and loads more by cursor', async () => {
    const today = new Date().toISOString();
    const old = '2026-01-10T10:00:00Z';
    const seen: URL[] = [];
    mockApi({
      expenses: (url) => {
        seen.push(url);
        if (url.searchParams.get('cursor') === 'next') {
          return {
            status: 200,
            body: { items: [expense(uid('3'), 300, old, { merchantName: 'Old shop' })], nextCursor: null },
          };
        }
        return {
          status: 200,
          body: { items: [expense(uid('1'), 1000, today, { merchantName: 'Today shop' })], nextCursor: 'next' },
        };
      },
    });
    const user = userEvent.setup();
    go('/transactions');
    expect(await screen.findByText('Today shop')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Today' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByText('Old shop')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
    await user.type(screen.getByRole('searchbox', { name: 'Search expenses' }), 'milk');
    await waitFor(() => {
      expect(seen.some((u) => u.searchParams.get('q') === 'milk')).toBe(true);
    });
    await user.selectOptions(screen.getByRole('combobox', { name: 'Category' }), CAT);
    await waitFor(() => {
      expect(seen.some((u) => u.searchParams.get('categoryId') === CAT)).toBe(true);
    });
  });

  it('REQ-UI-021 shows the empty state and the offline copy on failure while disconnected', async () => {
    mockApi({ expenses: () => ({ status: 500, body: { type: 'about:blank', title: 'x', status: 500 } }) });
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(false);
    go('/transactions');
    expect(await screen.findByText(/You're offline and nothing is saved/)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(/offline/i);
  });

  it('REQ-UI-021 shows the empty state', async () => {
    mockApi();
    go('/transactions');
    expect(await screen.findByText('No expenses match.')).toBeInTheDocument();
  });
});

describe('REQ-UI-023 Add', () => {
  it('REQ-UI-023 posts integer minor units, category, Cairo-today date and note, then goes to Transactions', async () => {
    const { created } = mockApi();
    const user = userEvent.setup();
    go('/add');
    await user.click(screen.getByRole('button', { name: '1' }));
    await user.click(screen.getByRole('button', { name: '2' }));
    await user.click(screen.getByRole('button', { name: 'Decimal point' }));
    await user.click(screen.getByRole('button', { name: '5' }));
    await user.click(await screen.findByRole('button', { name: 'Groceries' }));
    await user.type(screen.getByLabelText('Note'), 'milk');
    expect(screen.getByLabelText('Date')).toHaveValue(cairoToday());
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => {
      expect(created).toHaveLength(1);
    });
    expect(created[0]).toMatchObject({
      type: 'EXPENSE',
      amount: { amountMinor: 1250, currency: 'EGP' },
      categoryId: CAT,
      paymentMethod: 'CASH',
      notes: 'milk',
    });
    await waitFor(() => {
      expect(window.location.pathname).toBe('/transactions');
    });
  });

  it('REQ-UI-023 rejects a zero amount without calling the API', async () => {
    const { created } = mockApi();
    const user = userEvent.setup();
    go('/add');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Enter an amount greater than zero.')).toBeInTheDocument();
    expect(created).toHaveLength(0);
  });

  it('REQ-UI-023 shows a validation error when the server answers 422', async () => {
    const m = mockApi();
    const base = m.fetchMock.getMockImplementation();
    m.fetchMock.mockImplementation((input, init) =>
      init?.method === 'POST'
        ? reply(422, { type: 'about:blank', title: 'Unprocessable', status: 422 })
        : (base?.(input, init) ?? reply(404, {})),
    );
    const user = userEvent.setup();
    go('/add');
    await user.click(screen.getByRole('button', { name: '5' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    const alert = await screen.findByRole('alert');
    expect(within(alert.parentElement as HTMLElement).getByText(/server rejected/)).toBeInTheDocument();
  });
});

describe('REQ-UI-023 Add hardening', () => {
  const enter = async (user: ReturnType<typeof userEvent.setup>, digit: string) => {
    await user.click(screen.getByRole('button', { name: digit }));
  };

  it('REQ-UI-023 sends a UUID Idempotency-Key and reuses it when the same entry is retried', async () => {
    const { keys, created } = mockApi({ failPosts: 1 });
    const user = userEvent.setup();
    go('/add');
    await enter(user, '5');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/Couldn't save the expense/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => {
      expect(created).toHaveLength(1);
    });
    expect(keys).toHaveLength(2);
    expect(keys[0]).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(keys[1]).toBe(keys[0]);
  });

  it('REQ-UI-023 an edited entry gets a new Idempotency-Key', async () => {
    const { keys } = mockApi({ failPosts: 1 });
    const user = userEvent.setup();
    go('/add');
    await enter(user, '5');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText(/Couldn't save the expense/);
    await enter(user, '6');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => {
      expect(keys).toHaveLength(2);
    });
    expect(keys[1]).not.toBe(keys[0]);
  });

  it('REQ-UI-023 offline disables Save and shows the offline message instead of hanging', () => {
    const { created } = mockApi();
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(false);
    go('/add');
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(screen.getByText("You're offline. Connect and try again.")).toBeInTheDocument();
    expect(created).toHaveLength(0);
  });

  it('REQ-UI-023 rejects a date more than one day ahead', async () => {
    const { created } = mockApi();
    const user = userEvent.setup();
    go('/add');
    await enter(user, '5');
    fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2999-01-01' } });
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Date can be at most one day ahead.')).toBeInTheDocument();
    expect(created).toHaveLength(0);
  });

  it('REQ-UI-023 shows a category skeleton while loading and an accounts load error', async () => {
    mockApi({ accountsFail: true });
    go('/add');
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(await screen.findByText(/Couldn't load accounts/)).toBeInTheDocument();
  });
});

describe('REQ-UI-021 load-more failure and REQ-UI-020 offline total', () => {
  it('REQ-UI-021 shows an error and a retry when load-more fails, then recovers', async () => {
    let failNext = true;
    const oldItems = [expense(uid('2'), 300, '2026-01-10T10:00:00Z', { merchantName: 'Old shop' })];
    const todayItems = [expense(uid('1'), 1000, new Date().toISOString(), { merchantName: 'Today shop' })];
    mockApi({
      expenses: (url) => {
        if (url.searchParams.get('cursor') === 'next') {
          return failNext
            ? { status: 500, body: { type: 'about:blank', title: 'x', status: 500 } }
            : { status: 200, body: { items: oldItems, nextCursor: null } };
        }
        return { status: 200, body: { items: todayItems, nextCursor: 'next' } };
      },
    });
    const user = userEvent.setup();
    go('/transactions');
    await screen.findByText('Today shop');
    await user.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByText("Couldn't load more.")).toBeInTheDocument();
    expect(screen.getByText('Today shop')).toBeInTheDocument();
    failNext = false;
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Old shop')).toBeInTheDocument();
  });

  it('REQ-UI-020 the month total shows the offline copy when disconnected with nothing cached', async () => {
    mockApi();
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(false);
    onlineManager.setOnline(false); // queries pause (fetchStatus 'paused') instead of failing
    go('/');
    try {
      expect((await screen.findAllByText(/You're offline and nothing is saved/)).length).toBeGreaterThan(0);
    } finally {
      onlineManager.setOnline(true);
    }
  });
});
