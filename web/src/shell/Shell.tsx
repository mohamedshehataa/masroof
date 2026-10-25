import { useSyncExternalStore, type FC, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ChartIcon, HomeIcon, ListIcon, MoreIcon, PlusIcon } from './icons';
import { Link, useLocation } from './router';

interface Tab {
  path: string;
  labelKey: string;
  Icon: FC;
  fab?: boolean;
}

export const TABS: readonly Tab[] = [
  { path: '/', labelKey: 'nav.home', Icon: HomeIcon },
  { path: '/transactions', labelKey: 'nav.transactions', Icon: ListIcon },
  { path: '/add', labelKey: 'nav.add', Icon: PlusIcon, fab: true },
  { path: '/insights', labelKey: 'nav.insights', Icon: ChartIcon },
  { path: '/more', labelKey: 'nav.more', Icon: MoreIcon },
];

const isCurrent = (tabPath: string, pathname: string) =>
  tabPath === '/' ? pathname === '/' : pathname === tabPath || pathname.startsWith(`${tabPath}/`);

function subscribeOnline(listener: () => void): () => void {
  window.addEventListener('online', listener);
  window.addEventListener('offline', listener);
  return () => {
    window.removeEventListener('online', listener);
    window.removeEventListener('offline', listener);
  };
}

const OfflineBanner: FC = () => {
  const { t } = useTranslation();
  const online = useSyncExternalStore(subscribeOnline, () => navigator.onLine);
  if (online) return null;
  return (
    <div className="offline-banner" role="status">
      {t('shell.offline')}
    </div>
  );
};

/** One <nav>: a bottom bar with a centre FAB below 1024px, a sidebar on the inline-start edge from 1024px (CSS). */
const TabBar: FC = () => {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  return (
    <nav className="tabbar" aria-label={t('nav.label')}>
      <p className="tabbar__brand">{t('app.name')}</p>
      <ul className="tabbar__list">
        {TABS.map(({ path, labelKey, Icon, fab }) => {
          const current = isCurrent(path, pathname);
          return (
            <li key={path} className={fab ? 'tabbar__item tabbar__item--fab' : 'tabbar__item'}>
              <Link
                href={path}
                className={fab ? 'tab tab--fab' : 'tab'}
                {...(current ? { 'aria-current': 'page' as const } : {})}
              >
                <Icon />
                <span className="tab__label">{t(labelKey)}</span>
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
};

export const Shell: FC<{ children: ReactNode }> = ({ children }) => (
  <div className="shell">
    <OfflineBanner />
    <TabBar />
    <main className="content">{children}</main>
  </div>
);
