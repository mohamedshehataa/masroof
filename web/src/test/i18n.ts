import { createInstance, type i18n } from 'i18next';
import { initReactI18next } from 'react-i18next';
import ar from '../i18n/ar.json';
import en from '../i18n/en.json';

/** Test-scoped i18n instance built from the shipped resource files (for components rendered in isolation). */
export async function makeI18n(lng: 'en' | 'ar'): Promise<i18n> {
  const instance = createInstance();
  await instance.use(initReactI18next).init({
    lng,
    fallbackLng: 'en',
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  });
  return instance;
}
