import { useEffect, useMemo, useState, type FC } from 'react';
import { useTranslation } from 'react-i18next';
import type { Expense } from '../../api/generated';
import { useCategories, useExpenseList } from '../../api/queries';
import { usePrefs } from '../../app/prefs';
import { ExpenseRow, QueryBoundary } from '../../components/common';
import { cairoDayKey, formatDay, relativeDay } from '../../lib/dates';

const DEBOUNCE_MS = 300;

export function groupByDay(items: readonly Expense[]): { day: string; items: Expense[] }[] {
  const groups: { day: string; items: Expense[] }[] = [];
  for (const e of items) {
    const day = cairoDayKey(e.occurredAt);
    const last = groups[groups.length - 1];
    if (last?.day === day) last.items.push(e);
    else groups.push({ day, items: [e] });
  }
  return groups;
}

export const TransactionsScreen: FC = () => {
  const { t } = useTranslation();
  const { lang, digits } = usePrefs();
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const categories = useCategories();
  const list = useExpenseList(q, categoryId);

  useEffect(() => {
    const id = setTimeout(() => {
      setQ(text.trim());
    }, DEBOUNCE_MS);
    return () => {
      clearTimeout(id);
    };
  }, [text]);

  const items = useMemo(() => list.data?.pages.flatMap((p) => p.items), [list.data]);
  const view = { ...list, data: items };

  return (
    <>
      <h1>{t('tx.title')}</h1>
      <div className="filters">
        <label className="field">
          <span>{t('tx.search')}</span>
          <input
            type="search"
            value={text}
            onChange={(e) => {
              setText(e.target.value);
            }}
          />
        </label>
        <label className="field">
          <span>{t('tx.category')}</span>
          <select
            value={categoryId}
            onChange={(e) => {
              setCategoryId(e.target.value);
            }}
          >
            <option value="">{t('tx.allCategories')}</option>
            {categories.data?.map((c) => (
              <option key={c.id} value={c.id}>
                {lang === 'ar' ? c.nameAr : c.nameEn}
              </option>
            ))}
          </select>
        </label>
      </div>
      <QueryBoundary query={view} isEmpty={(d) => d.length === 0} emptyMessage={t('tx.empty')}>
        {(all) => (
          <>
            {groupByDay(all).map((g) => {
              const rel = relativeDay(g.day);
              return (
                <section key={g.day} aria-labelledby={`day-${g.day}`}>
                  <h2 id={`day-${g.day}`} className="day-head">
                    {rel ? t(`tx.${rel}`) : formatDay(g.day, lang, digits)}
                  </h2>
                  <ul className="list">
                    {g.items.map((e) => (
                      <ExpenseRow key={e.id} expense={e} categories={categories.data} />
                    ))}
                  </ul>
                </section>
              );
            })}
            {list.isFetchNextPageError ? (
              <p role="alert" className="state">
                {t('tx.loadMoreError')}
              </p>
            ) : null}
            {list.hasNextPage ? (
              <button
                type="button"
                className="btn"
                disabled={list.isFetchingNextPage}
                onClick={() => {
                  void list.fetchNextPage();
                }}
              >
                {list.isFetchingNextPage
                  ? t('tx.loadingMore')
                  : list.isFetchNextPageError
                    ? t('common.retry')
                    : t('tx.loadMore')}
              </button>
            ) : null}
          </>
        )}
      </QueryBoundary>
    </>
  );
};
