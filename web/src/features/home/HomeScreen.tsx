import type { FC } from 'react';
import { useTranslation } from 'react-i18next';
import { useCategories, useMonthSpent, useRecentExpenses } from '../../api/queries';
import { AmountText, ErrorState, ExpenseRow, QueryBoundary, SkeletonRows } from '../../components/common';
import { Link } from '../../router';

const SpentCard: FC = () => {
  const { t } = useTranslation();
  const spent = useMonthSpent();
  return (
    <section className="card" aria-labelledby="spent-title">
      <h2 id="spent-title" className="muted">
        {t('home.spentThisMonth')}
      </h2>
      {spent.data ? (
        <p className="hero-amount">
          <AmountText money={spent.data} alwaysDecimals />
        </p>
      ) : spent.isError ? (
        <ErrorState
          message={t('home.summaryError')}
          onRetry={() => {
            void spent.refetch();
          }}
        />
      ) : (
        <SkeletonRows count={1} />
      )}
    </section>
  );
};

export const HomeScreen: FC = () => {
  const { t } = useTranslation();
  const recent = useRecentExpenses();
  const categories = useCategories();
  return (
    <>
      <h1>{t('home.title')}</h1>
      <SpentCard />
      <section aria-labelledby="recent-title">
        <div className="section-head">
          <h2 id="recent-title">{t('home.recent')}</h2>
          <Link to="/transactions">{t('home.seeAll')}</Link>
        </div>
        <QueryBoundary query={recent} isEmpty={(d) => d.length === 0} emptyMessage={t('home.empty')}>
          {(items) => (
            <ul className="list">
              {items.map((e) => (
                <ExpenseRow key={e.id} expense={e} categories={categories.data} />
              ))}
            </ul>
          )}
        </QueryBoundary>
      </section>
    </>
  );
};
