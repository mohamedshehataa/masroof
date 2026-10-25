import { createInstance, type i18n } from 'i18next';
import { initReactI18next } from 'react-i18next';
import { browserLanguage, storedLanguage } from '../shell/settings';
import ar from './ar.json';
import en from './en.json';

/** Builds the app i18n instance: stored choice first, then the browser language, English fallback. */
export function createI18n(): i18n {
  const instance = createInstance();
  void instance.use(initReactI18next).init({
    lng: storedLanguage() ?? browserLanguage(),
    fallbackLng: 'en',
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false }, // React escapes; values are only rendered as text nodes
    initAsync: false,
    saveMissing: import.meta.env.DEV,
    missingKeyHandler: (_lngs, _ns, key) => {
      if (import.meta.env.DEV) console.warn(`[i18n] missing key: ${key}`);
    },
    // Never show a raw key like `home.title` in production.
    parseMissingKeyHandler: (key) => (import.meta.env.DEV ? key : ''),
  });
  return instance;
}
