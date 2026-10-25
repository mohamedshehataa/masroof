import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState, useSyncExternalStore, type FC } from 'react';
import App from '../App';
import { hasSession, subscribeSession } from '../api/session';
import { AUTH_ENABLED } from './flags';
import { BottomNav } from '../components/BottomNav';
import { OfflineBanner } from '../components/common';
import { LoginScreen } from '../features/auth/LoginScreen';
import { AddScreen } from '../features/add/AddScreen';
import { HomeScreen } from '../features/home/HomeScreen';
import { InsightsScreen, MoreScreen } from '../features/more/MoreScreens';
import { TransactionsScreen } from '../features/transactions/TransactionsScreen';
import { RouterProvider, useRouter } from '../router';
import { PrefsProvider } from './prefs';
import '../styles.css';

const Screen: FC = () => {
  const { path } = useRouter();
  switch (path) {
    case '/transactions':
      return <TransactionsScreen />;
    case '/add':
      return <AddScreen />;
    case '/insights':
      return <InsightsScreen />;
    case '/more':
      return <MoreScreen />;
    default:
      return <HomeScreen />;
  }
};

const Shell: FC = () => {
  const signedIn = useSyncExternalStore(subscribeSession, hasSession);
  if (AUTH_ENABLED && !signedIn) return <LoginScreen />;
  return (
    <div className="shell">
      <OfflineBanner />
      <main className="page">
        <Screen />
      </main>
      <BottomNav />
    </div>
  );
};

// Offline-friendly defaults: no automatic retry storms; queries pause offline and keep cached data.
const createClient = () =>
  new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false, staleTime: 15_000, gcTime: 24 * 60 * 60 * 1000 },
    },
  });

const Providers: FC = () => {
  const [client] = useState(createClient);
  const { path } = useRouter();
  // The version page (REQ-OPS-021) keeps its own providers and lives at /version.
  if (path === '/version') return <App />;
  return (
    <PrefsProvider>
      <QueryClientProvider client={client}>
        <Shell />
      </QueryClientProvider>
    </PrefsProvider>
  );
};

export const Root: FC = () => (
  <RouterProvider>
    <Providers />
  </RouterProvider>
);
