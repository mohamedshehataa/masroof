import type { FC, ReactElement } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useRouter } from '../router';

const svg = (d: string): ReactElement => (
  <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
    <path d={d} strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);

const TABS = [
  { to: '/', key: 'home', icon: svg('M3 11l9-8 9 8M5 10v10h14V10') },
  { to: '/transactions', key: 'transactions', icon: svg('M4 6h16M4 12h16M4 18h16') },
  { to: '/add', key: 'add', icon: svg('M12 5v14M5 12h14') },
  { to: '/insights', key: 'insights', icon: svg('M5 20V10M12 20V4M19 20v-7') },
  { to: '/more', key: 'more', icon: svg('M5 12h.01M12 12h.01M19 12h.01') },
] as const;

export const BottomNav: FC = () => {
  const { t } = useTranslation();
  const { path } = useRouter();
  return (
    <nav className="bottom-nav" aria-label={t('nav.label')}>
      {TABS.map((tab) => (
        <Link key={tab.key} to={tab.to} current={path === tab.to} className="tab">
          {tab.icon}
          <span>{t(`nav.${tab.key}`)}</span>
        </Link>
      ))}
    </nav>
  );
};
