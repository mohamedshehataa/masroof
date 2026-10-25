import { useId, type FC } from 'react';
import { useTranslation } from 'react-i18next';
import { AmountText } from '../../components/AmountText';
import type { Digits } from '../../lib/money';
import { SignOutIcon } from '../../shell/icons';
import { signOut } from '../../shell/session';
import {
  DIGIT_OPTIONS,
  LANGUAGE_KEY,
  THEME_OPTIONS,
  useDigits,
  useThemePreference,
  type Language,
} from '../../shell/settings';
import { writeStored } from '../../shell/storage';

const PREVIEW = { amountMinor: 125050, currency: 'EGP' } as const;
const LANGUAGES: readonly Language[] = ['en', 'ar'];

interface ChoiceProps {
  name: string;
  value: string;
  label: string;
  checked: boolean;
  onSelect: () => void;
}

const Choice: FC<ChoiceProps> = ({ name, value, label, checked, onSelect }) => (
  <label className="choice">
    <input type="radio" name={name} value={value} checked={checked} onChange={onSelect} />
    <span>{label}</span>
  </label>
);

export const MorePage: FC = () => {
  const { t, i18n } = useTranslation();
  const [digits, setDigits] = useDigits();
  const [theme, setTheme] = useThemePreference();
  const ids = { language: useId(), digits: useId(), theme: useId(), account: useId() };

  const chooseLanguage = (language: Language) => {
    writeStored(LANGUAGE_KEY, language);
    void i18n.changeLanguage(language);
  };

  return (
    <div className="page">
      <h1>{t('more.title')}</h1>

      <section aria-labelledby={ids.language} className="card">
        <h2 id={ids.language}>{t('more.languageTitle')}</h2>
        <div className="row">
          {LANGUAGES.map((language) => (
            <button
              key={language}
              type="button"
              className={i18n.language === language ? 'btn btn--toggle btn--on' : 'btn btn--toggle'}
              aria-pressed={i18n.language === language}
              lang={language}
              onClick={() => {
                chooseLanguage(language);
              }}
            >
              {t(`common.language.${language}`)}
            </button>
          ))}
        </div>
      </section>

      <section aria-labelledby={ids.digits} className="card">
        <h2 id={ids.digits}>{t('more.digitsTitle')}</h2>
        <div role="radiogroup" aria-labelledby={ids.digits} className="choices">
          {DIGIT_OPTIONS.map((option: Digits) => (
            <Choice
              key={option}
              name="digits"
              value={option}
              label={t(`more.digits.${option}`)}
              checked={digits === option}
              onSelect={() => {
                setDigits(option);
              }}
            />
          ))}
        </div>
        <p className="muted">
          {t('more.digits.preview')}: <AmountText money={PREVIEW} />
        </p>
      </section>

      <section aria-labelledby={ids.theme} className="card">
        <h2 id={ids.theme}>{t('more.themeTitle')}</h2>
        <div role="radiogroup" aria-labelledby={ids.theme} className="choices">
          {THEME_OPTIONS.map((option) => (
            <Choice
              key={option}
              name="theme"
              value={option}
              label={t(`more.theme.${option}`)}
              checked={theme === option}
              onSelect={() => {
                setTheme(option);
              }}
            />
          ))}
        </div>
      </section>

      <section aria-labelledby={ids.account} className="card">
        <h2 id={ids.account}>{t('more.accountTitle')}</h2>
        <button type="button" className="btn btn--quiet btn--icon" onClick={signOut}>
          <SignOutIcon />
          <span>{t('more.signOut')}</span>
        </button>
      </section>
    </div>
  );
};
