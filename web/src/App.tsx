import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useEffect, useLayoutEffect, useState, type FC } from 'react';
import { I18nextProvider, useTranslation } from 'react-i18next';
import { getHealth } from './api/generated';
import { ErrorBoundary } from './components/ErrorBoundary';
import { PlaceholderPage } from './components/PlaceholderPage';
import { LoginPage } from './features/auth/LoginPage';
import { HEALTH_QUERY_KEY } from './features/health/HealthPanel';
import { HomePage } from './features/home/HomePage';
import { MorePage } from './features/more/MorePage';
import { NotFoundPage } from './features/NotFoundPage';
import { createI18n } from './i18n/setup';
import { navigate, useLocation } from './shell/router';
import { useIsSignedIn } from './shell/session';
import { applyTheme, useThemePreference } from './shell/settings';
import { Shell } from './shell/Shell';
import './styles.css';

// retry: false is deliberate (REQ-OPS-021); networkMode 'always' lets Retry run while navigator.onLine is false so
// the offline copy can be shown; reconnect refetch is done explicitly in HealthPanel (exactly one request).
// staleTime keeps /health cached across tab switches: Home -> More -> Home is one request (REQ-OPS-021).
const STALE_MS = 60_000;
// Offline: do not hit the network; the error state then shows the offline copy.
const fetchHealth = () => (navigator.onLine ? getHealth() : Promise.reject(new Error('offline')));

const createClient = () => {
  const client = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        networkMode: 'always',
        refetchOnReconnect: false,
        refetchOnWindowFocus: false,
        staleTime: STALE_MS,
      },
    },
  });
  client.setQueryDefaults(HEALTH_QUERY_KEY, { queryFn: fetchHealth });
  return client;
};

const LOGIN_PATH = '/login';

/** Keeps <html lang/dir> and the theme class in step with the language and theme settings. */
const DocumentSettings: FC = () => {
  const { i18n } = useTranslation();
  const [theme] = useThemePreference();
  const lang = i18n.language;
  useLayoutEffect(() => {
    document.documentElement.lang = lang;
    document.documentElement.dir = i18n.dir(lang);
  }, [lang, i18n]);
  useLayoutEffect(() => applyTheme(theme), [theme]);
  return null;
};

const Routes: FC = () => {
  const { pathname, search } = useLocation();
  const signedIn = useIsSignedIn();
  const onLogin = pathname === LOGIN_PATH;

  useEffect(() => {
    if (!signedIn && !onLogin) {
      const target = pathname + search;
      navigate(target === '/' ? LOGIN_PATH : `${LOGIN_PATH}?next=${encodeURIComponent(target)}`, { replace: true });
    } else if (signedIn && onLogin) {
      const next = new URLSearchParams(search).get('next');
      navigate(next?.startsWith('/') === true && !next.startsWith('//') ? next : '/', { replace: true });
    }
  }, [signedIn, onLogin, pathname, search]);

  if (!signedIn) return onLogin ? <LoginPage /> : null;
  if (onLogin) return null;

  let screen;
  switch (pathname) {
    case '/':
      screen = <HomePage />;
      break;
    case '/transactions':
      screen = <PlaceholderPage titleKey="pages.transactions" />;
      break;
    case '/add':
      screen = <PlaceholderPage titleKey="pages.add" />;
      break;
    case '/insights':
      screen = <PlaceholderPage titleKey="pages.insights" />;
      break;
    case '/more':
      screen = <MorePage />;
      break;
    default:
      screen = <NotFoundPage />;
  }
  return <Shell>{screen}</Shell>;
};

export default function App() {
  const [i18nInstance] = useState(createI18n);
  const [client] = useState(createClient);
  return (
    <I18nextProvider i18n={i18nInstance}>
      <DocumentSettings />
      <QueryClientProvider client={client}>
        <ErrorBoundary>
          <Routes />
        </ErrorBoundary>
      </QueryClientProvider>
    </I18nextProvider>
  );
}
