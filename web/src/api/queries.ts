import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { cairoToday, monthRange } from '../lib/dates';
import { sumMinor } from '../lib/money';
import { createExpense, listAccounts, listCategories, listExpenses } from './generated';
import type { Expense, ExpenseCreate } from './generated';

export const keys = {
  expenses: ['expenses'] as const,
  recent: ['expenses', 'recent'] as const,
  month: (from: string) => ['expenses', 'month', from] as const,
  list: (q: string, categoryId: string) => ['expenses', 'list', q, categoryId] as const,
  categories: ['categories'] as const,
  accounts: ['accounts'] as const,
};

const PAGE = 20;
const MONTH_PAGE = 200;
const MAX_MONTH_PAGES = 25;

export const useCategories = () =>
  useQuery({ queryKey: keys.categories, queryFn: async () => (await listCategories()).items });

export const useAccounts = () =>
  useQuery({ queryKey: keys.accounts, queryFn: async () => (await listAccounts()).items });

export const useRecentExpenses = () =>
  useQuery({
    queryKey: keys.recent,
    queryFn: async () => (await listExpenses({ query: { sort: '-occurredAt', limit: 5 } })).items,
  });

/** Total spent in the current Cairo calendar month, summed in minor units from the expense list. */
export const useMonthSpent = () => {
  const { from, to } = monthRange(cairoToday());
  return useQuery({
    queryKey: keys.month(from),
    queryFn: async () => {
      const all: Expense[] = [];
      let cursor: string | undefined;
      for (let i = 0; i < MAX_MONTH_PAGES; i += 1) {
        const page = await listExpenses({ query: { from, to, type: 'EXPENSE', limit: MONTH_PAGE, cursor } });
        all.push(...page.items);
        if (page.nextCursor === null) break;
        cursor = page.nextCursor;
      }
      const counted = all.filter((e) => e.status !== 'IGNORED' && e.deletedAt == null);
      const currency = counted[0]?.amount.currency ?? 'EGP';
      return { amountMinor: sumMinor(counted.map((e) => e.amount.amountMinor)), currency };
    },
  });
};

export const useExpenseList = (q: string, categoryId: string) =>
  useInfiniteQuery({
    queryKey: keys.list(q, categoryId),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      listExpenses({
        query: {
          sort: '-occurredAt',
          limit: PAGE,
          cursor: pageParam,
          q: q === '' ? undefined : q,
          categoryId: categoryId === '' ? undefined : categoryId,
        },
      }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });

export const useCreateExpense = () => {
  const client = useQueryClient();
  return useMutation({
    // networkMode 'always': offline fails fast instead of pausing forever on "Saving".
    networkMode: 'always',
    mutationFn: ({ body, idempotencyKey }: { body: ExpenseCreate; idempotencyKey: string }) =>
      createExpense({ body, headers: { 'Idempotency-Key': idempotencyKey } }),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.expenses }),
  });
};
