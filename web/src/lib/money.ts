// The only place that turns Money into text (REQ-UI-005). Integer minor units (piasters) only: the split into
// major/minor parts uses BigInt, so no floating point ever touches an amount.

export type Locale = 'en' | 'ar';
export type Digits = 'western' | 'arabic-indic';

export interface Money {
  amountMinor: number;
  currency: string;
}

export interface MoneyFormatOptions {
  locale: Locale;
  digits: Digits;
}

const MINOR_PER_MAJOR = 100n;
const NBSP = ' ';
const INVALID = '—';
const ARABIC_INDIC = '٠١٢٣٤٥٦٧٨٩';

// Arabic currency symbols we know; every other currency renders as its ISO code (REQ-UI-005 unhappy path).
const ARABIC_SYMBOLS: Readonly<Record<string, string>> = { EGP: 'ج.م' };

/** Currency label per locale: the Arabic symbol when we know one, the ISO code otherwise. */
export function currencyLabel(currency: string, locale: Locale): string {
  return locale === 'ar' ? (ARABIC_SYMBOLS[currency] ?? currency) : currency;
}

function group(whole: string, separator: string): string {
  return whole.replace(/\B(?=(\d{3})+$)/g, separator);
}

function mapDigits(text: string, digits: Digits): string {
  if (digits === 'western') return text;
  return text.replace(/\d/g, (d) => ARABIC_INDIC.charAt(Number(d)));
}

/** Formats `1,250 EGP`, `1,250.50 EGP`, `١٬٢٥٠ ج.م`. Throws in dev and renders `—` in production on invalid input. */
export function formatMoney(money: Money, options: MoneyFormatOptions): string {
  const { amountMinor, currency } = money;
  if (!Number.isSafeInteger(amountMinor) || !/^[A-Z]{3}$/.test(currency)) {
    if (import.meta.env.DEV) throw new RangeError(`Invalid money: ${String(amountMinor)} ${currency}`);
    return INVALID;
  }
  const negative = amountMinor < 0;
  const abs = BigInt(amountMinor) * (negative ? -1n : 1n);
  const whole = String(abs / MINOR_PER_MAJOR);
  const minor = abs % MINOR_PER_MAJOR;
  const arabic = options.digits === 'arabic-indic';
  const grouped = group(whole, arabic ? '٬' : ',');
  const fraction = minor === 0n ? '' : (arabic ? '٫' : '.') + String(minor).padStart(2, '0');
  const number = mapDigits(grouped + fraction, options.digits);
  const symbol = currencyLabel(currency, options.locale);
  return `${negative ? '-' : ''}${number}${NBSP}${symbol}`;
}
