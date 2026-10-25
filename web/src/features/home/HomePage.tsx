import type { FC } from 'react';
import { useTranslation } from 'react-i18next';
import { HealthPanel } from '../health/HealthPanel';

export const HomePage: FC = () => {
  const { t } = useTranslation();
  return (
    <div className="page">
      <h1>{t('pages.home')}</h1>
      <HealthPanel />
    </div>
  );
};
