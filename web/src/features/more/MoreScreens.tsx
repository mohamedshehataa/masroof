import type { FC } from 'react';
import { useTranslation } from 'react-i18next';
import { clearSession } from '../../api/session';
import { AUTH_ENABLED } from '../../app/flags';
import { usePrefs } from '../../app/prefs';
import { Link } from '../../router';

export const InsightsScreen: FC = () => {
  const { t } = useTranslation();
  return (
    <>
      <h1>{t('insights.title')}</h1>
      <p className="state muted">{t('insights.soon')}</p>
    </>
  );
};

export const MoreScreen: FC = () => {
  const { t } = useTranslation();
  const { lang, digits, setLang, setDigits } = usePrefs();
  const target = lang === 'ar' ? 'en' : 'ar';
  return (
    <>
      <h1>{t('more.title')}</h1>
      <div className="stack">
        <button
          type="button"
          className="btn"
          onClick={() => {
            setLang(target);
          }}
        >
          <span lang={target}>{t(`common.language.${target}`)}</span>
        </button>
        <label className="check">
          <input
            type="checkbox"
            checked={digits === 'arabic'}
            onChange={(e) => {
              setDigits(e.target.checked ? 'arabic' : 'western');
            }}
          />
          <span>{t('more.digits')}</span>
        </label>
        <Link to="/version" className="link-row">
          {t('more.version')}
        </Link>
        {AUTH_ENABLED ? (
          <button type="button" className="btn" onClick={clearSession}>
            {t('more.signOut')}
          </button>
        ) : null}
      </div>
    </>
  );
};
