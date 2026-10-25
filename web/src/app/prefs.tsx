import { createInstance, type i18n } from 'i18next';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type FC, type ReactNode } from 'react';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import ar from '../i18n/ar.json';
import en from '../i18n/en.json';
import type { Digits, Lang } from '../lib/money';

// Language and digit setting are display preferences (not secrets), so localStorage is fine here.
const LANG_KEY = 'masroof.lang';
const DIGITS_KEY = 'masroof.digits';

function readStored(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStored(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // storage unavailable (private mode): the preference simply is not persisted
  }
}

function initialLang(): Lang {
  const stored = readStored(LANG_KEY);
  if (stored === 'ar' || stored === 'en') return stored;
  return navigator.language.toLowerCase().startsWith('ar') ? 'ar' : 'en';
}

function createI18n(lng: Lang): i18n {
  const instance = createInstance();
  void instance.use(initReactI18next).init({
    lng,
    fallbackLng: 'en',
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
    initAsync: false,
  });
  return instance;
}

interface Prefs {
  lang: Lang;
  digits: Digits;
  setLang: (lang: Lang) => void;
  setDigits: (digits: Digits) => void;
}

const PrefsContext = createContext<Prefs>({
  lang: 'en',
  digits: 'western',
  setLang: () => undefined,
  setDigits: () => undefined,
});

export const usePrefs = (): Prefs => useContext(PrefsContext);

export const PrefsProvider: FC<{ children: ReactNode }> = ({ children }) => {
  const [lang, setLangState] = useState<Lang>(initialLang);
  const [digits, setDigitsState] = useState<Digits>(() => {
    const stored = readStored(DIGITS_KEY);
    if (stored === 'arabic' || stored === 'western') return stored;
    return initialLang() === 'ar' ? 'arabic' : 'western';
  });
  const [instance] = useState(() => createI18n(lang));

  useEffect(() => {
    void instance.changeLanguage(lang);
    document.documentElement.lang = lang;
    document.documentElement.dir = instance.dir(lang);
  }, [lang, instance]);

  const setLang = useCallback((next: Lang) => {
    writeStored(LANG_KEY, next);
    setLangState(next);
  }, []);
  const setDigits = useCallback((next: Digits) => {
    writeStored(DIGITS_KEY, next);
    setDigitsState(next);
  }, []);
  const value = useMemo(() => ({ lang, digits, setLang, setDigits }), [lang, digits, setLang, setDigits]);
  return (
    <I18nextProvider i18n={instance}>
      <PrefsContext value={value}>{children}</PrefsContext>
    </I18nextProvider>
  );
};
