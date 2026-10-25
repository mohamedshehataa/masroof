import type { FC } from 'react';
import { useTranslation } from 'react-i18next';
import { useCategories, useMonthSpent, useRecentExpenses } from '../../api/queries';
import { AmountText, ExpenseRow, QueryBoundary } from '../../components/common';
import { Link } from '../../router';

const SpentCard: FC = () => {
  const { t } = useTranslation();
  const spent = useMonthSpent();
  return (
    <section className="card" aria-labelledby="spent-title">
      <h2 id="spent-title" className="muted">
        {t('home.spentThisMonth')}
      </h2>
      <QueryBoundary
        query={spent}
        isEmpty={() => false}
        emptyMessage=""
        errorMessage={t('home.summaryError')}
        skeletonCount={1}
      >
        {(total) => (
          <p className="hero-amount">
            <AmountText money={total} alwaysDecimals />
          </p>
        )}
      </QueryBoundary>
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
