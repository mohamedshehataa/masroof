import { useState, type FC } from 'react';
import { useTranslation } from 'react-i18next';
import { login } from '../../api/generated';
import { setSession } from '../../api/session';

export const LoginScreen: FC = () => {
  const { t } = useTranslation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<'error' | 'errorGeneric' | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      setSession(await login({ body: { email, password } }));
    } catch (e) {
      const status = typeof e === 'object' && e !== null && 'status' in e ? e.status : null;
      setError(status === 401 ? 'error' : 'errorGeneric');
    } finally {
      setBusy(false);
    }
  };

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
        {error ? <p role="alert">{t(`login.${error}`)}</p> : null}
        <button type="submit" className="btn btn-block" disabled={busy}>
          {t('login.submit')}
        </button>
      </form>
    </main>
  );
};
