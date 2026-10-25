import type { FC } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from '../shell/router';

export const NotFoundPage: FC = () => {
  const { t } = useTranslation();
  return (
    <div className="page">
      <h1>{t('notFound.title')}</h1>
      <p className="muted">{t('notFound.body')}</p>
      <Link href="/" className="btn">
        {t('notFound.home')}
      </Link>
    </div>
  );
};
