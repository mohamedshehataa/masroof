import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { createInstance, type i18n } from 'i18next';
import { useEffect, useState, type FC } from 'react';
import { I18nextProvider, initReactI18next, useTranslation } from 'react-i18next';
import { getHealth } from './api/generated';
import { VersionLine } from './features/health/VersionLine';
import ar from './i18n/ar.json';
import en from './i18n/en.json';
import './styles.css';

type Lang = 'en' | 'ar';

function detectLanguage(): Lang {
  return navigator.language.toLowerCase().startsWith('ar') ? 'ar' : 'en';
}

function createI18n(): i18n {
  const instance = createInstance();
  void instance.use(initReactI18next).init({
    lng: detectLanguage(),
    fallbackLng: 'en',
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false }, // React escapes; values are only rendered as text nodes
    initAsync: false,
  });
  return instance;
}

// retry: false is deliberate (REQ-OPS-021); networkMode 'always' lets Retry run while navigator.onLine is false so
// the offline copy can be shown; reconnect refetch is done explicitly below (exactly one request).
const createClient = () =>
  new QueryClient({
    defaultOptions: {
      queries: { retry: false, networkMode: 'always', refetchOnReconnect: false, refetchOnWindowFocus: false },
    },
  });

const LanguageToggle: FC = () => {
  const { t, i18n: instance } = useTranslation();
  const target: Lang = instance.language === 'ar' ? 'en' : 'ar';
  return (
    <button
      type="button"
      className="btn"
      onClick={() => {
        void instance.changeLanguage(target);
      }}
    >
      <span lang={target}>{t(`common.language.${target}`)}</span>
    </button>
  );
};

// Offline: do not hit the network; the error state then shows the offline copy.
const fetchHealth = () => (navigator.onLine ? getHealth() : Promise.reject(new Error('offline')));

const HealthPanel: FC = () => {
  const { t } = useTranslation();
  const { data, isFetching, isError, refetch } = useQuery({ queryKey: ['health'], queryFn: fetchHealth });

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

const Page: FC = () => {
  const { i18n: instance } = useTranslation();
  const lang = instance.language;
  useEffect(() => {
    document.documentElement.lang = lang;
    document.documentElement.dir = instance.dir(lang);
  }, [lang, instance]);
  return (
    <main className="page">
      <HealthPanel />
      <LanguageToggle />
    </main>
  );
};

export default function App() {
  const [i18nInstance] = useState(createI18n);
  const [client] = useState(createClient);
  return (
    <I18nextProvider i18n={i18nInstance}>
      <QueryClientProvider client={client}>
        <Page />
      </QueryClientProvider>
    </I18nextProvider>
  );
}
