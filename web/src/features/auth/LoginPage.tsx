import type { FC } from 'react';
import { useTranslation } from 'react-i18next';

/** Placeholder: the real sign-in screen is STORY-018. Exists so the unauthenticated redirect has a target. */
export const LoginPage: FC = () => {
  const { t } = useTranslation();
  return (
    <main className="page page--centered">
      <h1>{t('login.title')}</h1>
      <p className="muted">{t('login.body')}</p>
    </main>
  );
};
