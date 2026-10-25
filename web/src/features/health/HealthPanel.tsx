import { useQuery } from '@tanstack/react-query';
import { useEffect, type FC } from 'react';
import { useTranslation } from 'react-i18next';
import type { getHealth } from '../../api/generated';
import { VersionLine } from './VersionLine';

type Health = Awaited<ReturnType<typeof getHealth>>;

/** The queryFn is registered as the default for this key in App.tsx (generated client `getHealth`). */
export const HEALTH_QUERY_KEY = ['health'] as const;

export const HealthPanel: FC = () => {
  const { t } = useTranslation();
  const { data, isFetching, isError, refetch } = useQuery<Health>({ queryKey: HEALTH_QUERY_KEY });

  useEffect(() => {
    // Only recover from the error/offline state; never flash a skeleton over a rendered version.
    const onOnline = () => {
      if (isError) void refetch();
    };
    window.addEventListener('online', onOnline);
    return () => {
      window.removeEventListener('online', onOnline);
    };
  }, [refetch, isError]);

  if (isFetching) return <div role="status" aria-busy="true" className="skeleton" />;
  if (isError || !data) {
    return (
      <div className="stack">
        <p role="alert">{navigator.onLine ? t('health.error') : t('health.offline')}</p>
        <button
          type="button"
          className="btn"
          onClick={() => {
            void refetch();
          }}
        >
          {t('common.retry')}
        </button>
      </div>
    );
  }
  return <VersionLine version={data.version} gitSha={data.gitSha} />;
};
