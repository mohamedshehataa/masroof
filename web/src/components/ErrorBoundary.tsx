import { Component, type FC, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

const ErrorFallback: FC<{ onRetry: () => void }> = ({ onRetry }) => {
  const { t } = useTranslation();
  return (
    <main className="page" role="alert">
      <h1>{t('shell.errorTitle')}</h1>
      <p>{t('shell.errorBody')}</p>
      <div className="row">
        <button type="button" className="btn" onClick={onRetry}>
          {t('common.retry')}
        </button>
        <button
          type="button"
          className="btn btn--quiet"
          onClick={() => {
            window.location.reload();
          }}
        >
          {t('common.reload')}
        </button>
      </div>
    </main>
  );
};

interface State {
  failed: boolean;
}

/** Global error boundary (UX states: error with Retry/Reload). Logs nothing: error text may carry user data. */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  componentDidCatch(): void {
    // Intentionally empty: error tracking arrives with the observability story; never log message content.
  }

  render(): ReactNode {
    if (!this.state.failed) return this.props.children;
    return (
      <ErrorFallback
        onRetry={() => {
          this.setState({ failed: false });
        }}
      />
    );
  }
}
