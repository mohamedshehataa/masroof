import { useState, type FC } from 'react';
import { useTranslation } from 'react-i18next';
import { apiErrorOf } from '../../api/errors';
import { acceptInvite } from '../../api/generated';
import { hasSession, setSession, signOut } from '../../api/session';
import { usePrefs } from '../../app/prefs';
import { OfflineBanner, useOnline } from '../../components/common';
import { useRouter } from '../../router';
import { formatWait } from './LoginScreen';

export const MIN_PASSWORD = 12;
const HTTP_BAD_REQUEST = 400;
const HTTP_NOT_FOUND = 404;
const HTTP_CONFLICT = 409;
const HTTP_GONE = 410;
const HTTP_TOO_MANY = 429;
const DEFAULT_WAIT_S = 60;

type AcceptError =
  | { kind: 'invalid' | 'conflict' | 'weak' | 'email' | 'name' | 'mismatch' | 'generic' | 'network' }
  | { kind: 'rate'; wait: string };

/** Which field a 400 problem names (`errors[0].field`), so the message matches the mistake. */
function badField(e: unknown): 'weak' | 'email' | 'name' {
  const errors =
    typeof e === 'object' && e !== null && 'errors' in e && Array.isArray(e.errors) ? (e.errors as unknown[]) : [];
  const first = errors[0];
  const field = typeof first === 'object' && first !== null && 'field' in first ? first.field : null;
  if (field === 'email') return 'email';
  if (field === 'displayName') return 'name';
  return 'weak';
}

/** The code travels in the URL fragment (never sent to a server) and is removed from the address bar at once. */
function takeCode(): string {
  const raw = window.location.hash.startsWith('#') ? window.location.hash.slice(1) : '';
  if (raw !== '') window.history.replaceState(null, '', window.location.pathname);
  try {
    return decodeURIComponent(raw).trim();
  } catch {
    return '';
  }
}

export const AcceptInviteScreen: FC = () => {
  const { t } = useTranslation();
  const { lang, digits, setLang } = usePrefs();
  const { navigate } = useRouter();
  const online = useOnline();
  const [code, setCode] = useState(takeCode);
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [reveal, setReveal] = useState(false);
  const [error, setError] = useState<AcceptError | null>(null);
  const [busy, setBusy] = useState(false);
  const target = lang === 'ar' ? 'en' : 'ar';

  const submit = async () => {
    setError(null);
    if (password.length < MIN_PASSWORD) {
      setError({ kind: 'weak' });
      return;
    }
    if (password !== confirm) {
      setError({ kind: 'mismatch' });
      return;
    }
    setBusy(true);
    try {
      // Accepting from a signed-in browser must not leave the old member's session alive.
      if (hasSession()) await signOut();
      const pair = await acceptInvite({
        body: { code, email: email.trim(), password, ...(name.trim() === '' ? {} : { displayName: name.trim() }) },
      });
      setPassword('');
      setConfirm('');
      setCode('');
      setSession(pair);
      navigate('/', { replace: true });
    } catch (e) {
      const { status, retryAfter } = apiErrorOf(e);
      if (status === HTTP_NOT_FOUND || status === HTTP_GONE) setError({ kind: 'invalid' });
      else if (status === HTTP_CONFLICT) setError({ kind: 'conflict' });
      else if (status === HTTP_BAD_REQUEST) setError({ kind: badField(e) });
      else if (status === HTTP_TOO_MANY)
        setError({
          kind: 'rate',
          wait: formatWait(retryAfter !== null && retryAfter > 0 ? retryAfter : DEFAULT_WAIT_S, lang, digits),
        });
      else setError({ kind: status === 0 ? 'network' : 'generic' });
    } finally {
      setBusy(false);
    }
  };

  const message =
    error === null
      ? null
      : error.kind === 'rate'
        ? t('invite.rateLimited', { wait: error.wait })
        : t(`invite.error.${error.kind}`);

  return (
    <>
      <OfflineBanner />
      <main className="page">
        <div className="login-head">
          <h1>{t('invite.title')}</h1>
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
        <p className="muted">{t('invite.intro')}</p>
        <form
          className="stack stretch"
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          {code === '' ? (
            <label className="field">
              <span>{t('invite.code')}</span>
              <input
                required
                autoComplete="off"
                dir="ltr"
                value={code}
                onChange={(e) => {
                  setCode(e.target.value.trim());
                }}
              />
            </label>
          ) : null}
          <label className="field">
            <span>{t('invite.name')}</span>
            <input
              autoComplete="name"
              maxLength={80}
              value={name}
              onChange={(e) => {
                setName(e.target.value);
              }}
            />
          </label>
          <label className="field">
            <span>{t('login.email')}</span>
            <input
              type="email"
              autoComplete="username"
              required
              dir="ltr"
              value={email}
              onChange={(e) => {
                setEmail(e.target.value);
              }}
            />
          </label>
          <div className="field">
            <label htmlFor="invite-password">{t('login.password')}</label>
            <div className="pw-row">
              <input
                id="invite-password"
                type={reveal ? 'text' : 'password'}
                autoComplete="new-password"
                required
                aria-describedby="invite-password-hint"
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
            <small id="invite-password-hint" className="muted">
              {t('invite.passwordHint', { min: MIN_PASSWORD })}
            </small>
          </div>
          <label className="field">
            <span>{t('invite.confirm')}</span>
            <input
              type={reveal ? 'text' : 'password'}
              autoComplete="new-password"
              required
              value={confirm}
              onChange={(e) => {
                setConfirm(e.target.value);
              }}
            />
          </label>
          {message === null ? null : <p role="alert">{message}</p>}
          <button type="submit" className="btn btn-block" disabled={busy || !online || code === ''} aria-busy={busy}>
            {busy ? <span className="spinner" aria-hidden="true" /> : null}
            {t('invite.submit')}
          </button>
        </form>
      </main>
    </>
  );
};
