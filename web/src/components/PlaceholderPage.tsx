import type { FC } from 'react';
import { useTranslation } from 'react-i18next';

/** Empty-state shell for a tab whose screen story has not landed yet. */
export const PlaceholderPage: FC<{ titleKey: string }> = ({ titleKey }) => {
  const { t } = useTranslation();
  return (
    <div className="page">
      <h1>{t(titleKey)}</h1>
      <p className="muted">{t('pages.comingSoon')}</p>
    </div>
  );
};
