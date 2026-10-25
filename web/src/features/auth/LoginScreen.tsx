import { useState, type FC } from 'react';
import { useTranslation } from 'react-i18next';
import { apiErrorOf } from '../../api/errors';
import { login } from '../../api/generated';
import { setSession } from '../../api/session';
import { usePrefs } from '../../app/prefs';
import { OfflineBanner, useOnline } from '../../components/common';

type LoginError = { kind: 'bad' } | { kind: 'generic' } | { kind: 'network' } | { kind: 'rate'; wait: string };

const SECONDS_PER_MINUTE = 60;
const DEFAULT_WAIT_S = 60;
const HTTP_BAD_REQUEST = 400;
const HTTP_UNAUTHORIZED = 401;
const HTTP_TOO_MANY = 429;

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
  const { lang, digits, setLang } = usePrefs();
  const online = useOnline();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [reveal, setReveal] = useState(false);
  const [error, setError] = useState<LoginError | null>(null);
  const [busy, setBusy] = useState(false);
  const target = lang === 'ar' ? 'en' : 'ar';

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      setSession(await login({ body: { email, password } }));
    } catch (e) {
      const { status, retryAfter } = apiErrorOf(e);
      if (status === HTTP_UNAUTHORIZED || status === HTTP_BAD_REQUEST) {
        // Same generic message for a wrong or malformed credential: never say which part was wrong.
        setPassword('');
        setReveal(false);
        setError({ kind: 'bad' });
      } else if (status === HTTP_TOO_MANY) {
        const seconds = retryAfter !== null && retryAfter > 0 ? retryAfter : DEFAULT_WAIT_S;
        setError({ kind: 'rate', wait: formatWait(seconds, lang, digits) });
      } else {
        setError({ kind: status === 0 ? 'network' : 'generic' });
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
        : t(`login.${error.kind === 'bad' ? 'error' : error.kind === 'network' ? 'network' : 'errorGeneric'}`);

  return (
    <>
      <OfflineBanner />
      <main className="page">
        <div className="login-head">
          <h1>{t('login.title')}</h1>
          <button
            type="button"
            className="btn btn-plain"
            onClick={() => {
              setLang(target);
            }}
          >
            <span lang={target}>{t(`common.language.${target}`)}</span>
          </button>
        </div>
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
          <div className="field">
            <label htmlFor="login-password">{t('login.password')}</label>
            <div className="pw-row">
              <input
                id="login-password"
                type={reveal ? 'text' : 'password'}
                autoComplete="current-password"
                required
                value={password}
                onChange={(e) => {
                  setPassword(e.target.value);
                }}
              />
              <button
                type="button"
                className="btn btn-plain"
                aria-pressed={reveal}
                aria-label={t('login.showPassword')}
                onClick={() => {
                  setReveal((v) => !v);
                }}
              >
                {t('login.show')}
              </button>
            </div>
          </div>
          {message === null ? null : <p role="alert">{message}</p>}
          {error?.kind === 'network' ? (
            <button
              type="button"
              className="btn btn-plain"
              disabled={busy || !online}
              onClick={() => {
                void submit();
              }}
            >
              {t('login.retry')}
            </button>
          ) : null}
          <button type="submit" className="btn btn-block" disabled={busy || !online} aria-busy={busy}>
            {busy ? <span className="spinner" aria-hidden="true" /> : null}
            {t('login.submit')}
          </button>
        </form>
      </main>
    </>
  );
};
