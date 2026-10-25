import { useCallback, useSyncExternalStore } from 'react';
import type { Digits } from '../lib/money';
import { readStored, subscribeStored, writeStored } from './storage';

export const LANGUAGE_KEY = 'masroof.lang';
export const DIGITS_KEY = 'masroof.digits';
export const THEME_KEY = 'masroof.theme';

export type Language = 'en' | 'ar';
export type ThemePreference = 'system' | 'light' | 'dark';

export const DIGIT_OPTIONS: readonly Digits[] = ['western', 'arabic-indic'];
export const THEME_OPTIONS: readonly ThemePreference[] = ['system', 'light', 'dark'];

export function storedLanguage(): Language | null {
  const raw = readStored(LANGUAGE_KEY);
  return raw === 'ar' || raw === 'en' ? raw : null;
}

export function browserLanguage(): Language {
  return navigator.language.toLowerCase().startsWith('ar') ? 'ar' : 'en';
}

export function parseDigits(raw: string | null): Digits {
  return raw === 'arabic-indic' ? 'arabic-indic' : 'western';
}

export function parseTheme(raw: string | null): ThemePreference {
  return raw === 'light' || raw === 'dark' ? raw : 'system';
}

export function useDigits(): [Digits, (digits: Digits) => void] {
  const raw = useSyncExternalStore(subscribeStored, () => readStored(DIGITS_KEY));
  const set = useCallback((digits: Digits) => {
    writeStored(DIGITS_KEY, digits);
  }, []);
  return [parseDigits(raw), set];
}

export function useThemePreference(): [ThemePreference, (theme: ThemePreference) => void] {
  const raw = useSyncExternalStore(subscribeStored, () => readStored(THEME_KEY));
  const set = useCallback((theme: ThemePreference) => {
    writeStored(THEME_KEY, theme);
  }, []);
  return [parseTheme(raw), set];
}

type MatchMedia = ((query: string) => MediaQueryList) | undefined;
const DARK_QUERY = '(prefers-color-scheme: dark)';

/** Applies the theme preference to <html>: `dark` / `light` classes; System follows prefers-color-scheme. */
export function applyTheme(preference: ThemePreference): () => void {
  const root = document.documentElement;
  const mq = (window.matchMedia as MatchMedia)?.(DARK_QUERY);
  const apply = () => {
    const dark = preference === 'dark' || (preference === 'system' && mq?.matches === true);
    root.classList.toggle('dark', dark);
    root.classList.toggle('light', preference === 'light');
  };
  apply();
  if (preference !== 'system' || !mq) return () => undefined;
  mq.addEventListener('change', apply);
  return () => {
    mq.removeEventListener('change', apply);
  };
}
