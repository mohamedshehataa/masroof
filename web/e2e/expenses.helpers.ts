import type { Page, Route } from '@playwright/test';

// Mock bodies are shaped exactly like contracts/openapi.yaml (Expense, Category, PaymentAccount, ExpensePage).
export const NOW = '2026-10-25T10:00:00Z';
const HH = '22222222-2222-4222-8222-222222222222';
export const CAT_GROCERIES = '11111111-1111-4111-8111-111111111111';
export const CAT_TRANSPORT = '11111111-1111-4111-8111-222222222222';
const ACCOUNT = '44444444-4444-4444-8444-444444444444';
const MEMBER = '55555555-5555-4555-8555-555555555555';

export const categories = [
  { id: CAT_GROCERIES, nameEn: 'Groceries', nameAr: 'بقالة', sortOrder: 1 },
  { id: CAT_TRANSPORT, nameEn: 'Transport', nameAr: 'مواصلات', sortOrder: 2 },
].map((c) => ({ ...c, householdId: HH, isEssential: true, archived: false }));

export const accounts = [
  { id: ACCOUNT, memberId: MEMBER, type: 'CARD', nickname: 'CIB', last4: '4321', currency: 'EGP', isShared: false },
];

export function expense(n: number, amountMinor: number, occurredAt: string, merchantName: string, categoryId: string) {
  return {
    id: `33333333-3333-4333-8333-${String(n).padStart(12, '0')}`,
    householdId: HH,
    type: 'EXPENSE',
    amount: { amountMinor, currency: 'EGP' },
    occurredAt,
    merchantName,
    categoryId,
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
  };
}

export const ledger = [
  expense(1, 12550, '2026-10-25T08:30:00Z', 'Carrefour', CAT_GROCERIES),
  expense(2, 4000, '2026-10-24T15:00:00Z', 'Uber', CAT_TRANSPORT),
  expense(3, 98075, '2026-10-02T09:00:00Z', 'Spinneys', CAT_GROCERIES),
];

const json = (route: Route, status: number, body: unknown) =>
  route.fulfill({
    status,
    contentType: status >= 400 ? 'application/problem+json' : 'application/json',
    body: JSON.stringify(body),
  });

export interface ApiMock {
  posted: unknown[];
  expenseCalls: URL[];
}

/** Routes the expense endpoints; `fail` makes GET /api/expenses answer 500. */
export async function mockApi(page: Page, opts: { items?: unknown[]; fail?: boolean } = {}): Promise<ApiMock> {
  const state: ApiMock = { posted: [], expenseCalls: [] };
  await page.clock.setFixedTime(new Date(NOW));
  await page.route('**/api/categories**', (route) => json(route, 200, { items: categories }));
  await page.route('**/api/accounts**', (route) => json(route, 200, { items: accounts }));
  await page.route('**/api/expenses**', async (route) => {
    const req = route.request();
    if (req.method() === 'POST') {
      state.posted.push(req.postDataJSON());
      return json(route, 201, ledger[0]);
    }
    state.expenseCalls.push(new URL(req.url()));
    if (opts.fail === true) return json(route, 500, { type: 'about:blank', title: 'Server error', status: 500 });
    return json(route, 200, { items: opts.items ?? ledger, nextCursor: null });
  });
  return state;
}
