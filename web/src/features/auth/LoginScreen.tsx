import { useState, type FC } from 'react';
import { useTranslation } from 'react-i18next';
import { login } from '../../api/generated';
import { apiErrorOf } from '../../api/errors';
import { setSession } from '../../api/session';
import { usePrefs } from '../../app/prefs';

type LoginError = { kind: 'bad' } | { kind: 'generic' } | { kind: 'rate'; wait: string };

const SECONDS_PER_MINUTE = 60;
const DEFAULT_WAIT_S = 60;

/** "5 minutes" / "٥ دقائق": locale and digit setting aware; the retry-after header is whole seconds. */
export function formatWait(seconds: number, lang: string, digits: 'western' | 'arabic'): string {
  const minutes = seconds >= SECONDS_PER_MINUTE;
  const value = minutes ? Math.ceil(seconds / SECONDS_PER_MINUTE) : Math.max(1, Math.ceil(seconds));
  const locale = `${lang}-u-nu-${digits === 'arabic' ? 'arab' : 'latn'}`;
  return new Intl.NumberFormat(locale, {
    style: 'unit',
    unit: minutes ? 'minute' : 'second',
    unitDisplay: 'long',
  }).format(value);
}

export const LoginScreen: FC = () => {
  const { t } = useTranslation();
  const { lang, digits } = usePrefs();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<LoginError | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      setSession(await login({ body: { email, password } }));
    } catch (e) {
      const { status, retryAfter } = apiErrorOf(e);
      if (status === 401) {
        setPassword('');
        setError({ kind: 'bad' });
      } else if (status === 429) {
        const seconds = retryAfter !== null && retryAfter > 0 ? retryAfter : DEFAULT_WAIT_S;
        setError({ kind: 'rate', wait: formatWait(seconds, lang, digits) });
      } else {
        setError({ kind: 'generic' });
      }
    } finally {
      setBusy(false);
    }
  };

  const message =
    error === null
      ? null
      : error.kind === 'rate'
        ? t('login.rateLimited', { wait: error.wait })
        : t(error.kind === 'bad' ? 'login.error' : 'login.errorGeneric');

  return (
    <main className="page">
      <h1>{t('login.title')}</h1>
      <form
        className="stack stretch"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <label className="field">
          <span>{t('login.email')}</span>
          <input
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => {
              setEmail(e.target.value);
            }}
          />
        </label>
        <label className="field">
          <span>{t('login.password')}</span>
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => {
              setPassword(e.target.value);
            }}
          />
        </label>
        {message === null ? null : <p role="alert">{message}</p>}
        <button type="submit" className="btn btn-block" disabled={busy}>
          {t('login.submit')}
        </button>
      </form>
    </main>
  );
};
