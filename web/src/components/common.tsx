import { useEffect, useState, type FC, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { Category, Expense } from '../api/generated';
import { usePrefs } from '../app/prefs';
import { formatMoney, type MoneyValue } from '../lib/money';

export function useOnline(): boolean {
  const [online, setOnline] = useState(() => navigator.onLine);
  useEffect(() => {
    const up = () => {
      setOnline(true);
    };
    const down = () => {
      setOnline(false);
    };
    window.addEventListener('online', up);
    window.addEventListener('offline', down);
    return () => {
      window.removeEventListener('online', up);
      window.removeEventListener('offline', down);
    };
  }, []);
  return online;
}

export const OfflineBanner: FC = () => {
  const { t } = useTranslation();
  return useOnline() ? null : (
    <p role="status" className="banner">
      {t('offline.banner')}
    </p>
  );
};

/** Amount in an isolated run with tabular numerals; the only way screens render money. */
export const AmountText: FC<{ money: MoneyValue; alwaysDecimals?: boolean; className?: string }> = ({
  money,
  alwaysDecimals,
  className,
}) => {
  const { lang, digits } = usePrefs();
  return <bdi className={`amount ${className ?? ''}`}>{formatMoney(money, { lang, digits, alwaysDecimals })}</bdi>;
};

export const SkeletonRows: FC<{ count?: number }> = ({ count = 4 }) => {
  const { t } = useTranslation();
  return (
    <div role="status" aria-busy="true" aria-label={t('list.loading')} className="skeleton-list">
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="skeleton-row" />
      ))}
    </div>
  );
};

export const ErrorState: FC<{ message?: string; onRetry: () => void }> = ({ message, onRetry }) => {
  const { t } = useTranslation();
  const online = useOnline();
  return (
    <div className="state">
      <p role="alert">{online ? (message ?? t('list.error')) : t('list.offline')}</p>
      <button type="button" className="btn" onClick={onRetry}>
        {t('common.retry')}
      </button>
    </div>
  );
};

export const EmptyState: FC<{ message: string }> = ({ message }) => <p className="state muted">{message}</p>;

interface QueryLike<T> {
  data: T | undefined;
  isPending: boolean;
  isError: boolean;
  fetchStatus: 'fetching' | 'paused' | 'idle';
  refetch: () => unknown;
}

/** Loading skeleton, error-with-retry (offline copy when disconnected), empty and ready states for one query. */
export function QueryBoundary<T>({
  query,
  isEmpty,
  emptyMessage,
  errorMessage,
  skeletonCount,
  children,
}: {
  query: QueryLike<T>;
  isEmpty: (data: T) => boolean;
  emptyMessage: string;
  errorMessage?: string;
  skeletonCount?: number;
  children: (data: T) => ReactNode;
}) {
  const retry = () => {
    void query.refetch();
  };
  if (query.data !== undefined) {
    return isEmpty(query.data) ? <EmptyState message={emptyMessage} /> : <>{children(query.data)}</>;
  }
  if (query.isError || (query.isPending && query.fetchStatus === 'paused')) {
    return <ErrorState message={errorMessage} onRetry={retry} />;
  }
  return <SkeletonRows count={skeletonCount} />;
}

export function categoryName(
  categories: readonly Category[] | undefined,
  id: string | null | undefined,
  lang: 'en' | 'ar',
) {
  const c = categories?.find((x) => x.id === id);
  if (!c) return null;
  return lang === 'ar' ? c.nameAr : c.nameEn;
}

export const ExpenseRow: FC<{ expense: Expense; categories: readonly Category[] | undefined }> = ({
  expense,
  categories,
}) => {
  const { t } = useTranslation();
  const { lang } = usePrefs();
  const category = categoryName(categories, expense.categoryId, lang);
  const title = expense.merchantName ?? expense.merchantRaw ?? expense.notes ?? category ?? t('common.unnamed');
  return (
    <li className="row">
      <div className="row-text">
        <span className="row-title">{title}</span>
        <span className="muted">{category ?? t('tx.uncategorized')}</span>
      </div>
      <AmountText money={expense.amount} />
    </li>
  );
};
