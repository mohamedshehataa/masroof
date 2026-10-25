// The only place money is formatted or computed (REQ-OPS-053 lint exempts this file). Integers only, no floats.
export type Lang = 'en' | 'ar';
export type Digits = 'western' | 'arabic';

export interface MoneyValue {
  amountMinor: number;
  currency: string;
}

const ARABIC_INDIC = ['٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩'];
const MINOR_PER_MAJOR = 100; // keypad is EGP-only
const MAX_INT_DIGITS = 9;
const MAX_FRACTION_DIGITS = 2;

export function localizeDigits(text: string, digits: Digits): string {
  return digits === 'arabic' ? text.replace(/\d/g, (d) => ARABIC_INDIC[Number(d)] ?? d) : text;
}

export function currencyLabel(currency: string, lang: Lang): string {
  return currency === 'EGP' && lang === 'ar' ? 'ج.م' : currency;
}

function groupThousands(int: string, sep: string): string {
  return int.replace(/\B(?=(\d{3})+(?!\d))/g, sep);
}

/** Minor-unit digits of an ISO-4217 currency (EGP 2, JPY 0, KWD 3); unknown codes fall back to 2. */
export function minorDigits(currency: string): number {
  try {
    return new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? 2;
  } catch {
    return 2;
  }
}

/**
 * `1,250 EGP` / `1,250.50 EGP` / `١٬٢٥٠ ج.م`. Whole amounts drop the decimals unless `alwaysDecimals`.
 * Throws RangeError for non-integer or unsafe minor units: money is never silently rounded.
 */
export function formatMoney(money: MoneyValue, opts: { lang: Lang; digits: Digits; alwaysDecimals?: boolean }): string {
  if (!Number.isSafeInteger(money.amountMinor)) throw new RangeError('amountMinor must be a safe integer');
  const fractionDigits = minorDigits(money.currency);
  const scale = 10 ** fractionDigits; // integer power of ten; not money arithmetic
  const negative = money.amountMinor < 0;
  const abs = Math.abs(money.amountMinor);
  const major = Math.trunc(abs / scale);
  const minor = abs % scale;
  const arabic = opts.digits === 'arabic';
  const group = arabic ? '٬' : ',';
  const decimal = arabic ? '٫' : '.';
  let body = groupThousands(String(major), group);
  if (fractionDigits > 0 && (minor !== 0 || opts.alwaysDecimals === true)) {
    body += decimal + String(minor).padStart(fractionDigits, '0');
  }
  return `${negative ? '-' : ''}${localizeDigits(body, opts.digits)} ${currencyLabel(money.currency, opts.lang)}`;
}

/** Sum of minor units (same currency assumed by the caller). */
export function sumMinor(values: readonly number[]): number {
  let total = 0;
  for (const v of values) total += v;
  return total;
}

export interface KeypadState {
  integer: string;
  fraction: string | null;
}

export const EMPTY_KEYPAD: KeypadState = { integer: '', fraction: null };

export type KeypadKey = '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' | '.' | 'back';

export function pressKey(state: KeypadState, key: KeypadKey): KeypadState {
  if (key === 'back') {
    if (state.fraction !== null) {
      return { integer: state.integer, fraction: state.fraction === '' ? null : state.fraction.slice(0, -1) };
    }
    return { integer: state.integer.slice(0, -1), fraction: null };
  }
  if (key === '.') return state.fraction === null ? { ...state, fraction: '' } : state;
  if (state.fraction !== null) {
    return state.fraction.length >= MAX_FRACTION_DIGITS ? state : { ...state, fraction: state.fraction + key };
  }
  const next = state.integer === '' && key === '0' ? '0' : state.integer === '0' ? key : state.integer + key;
  return next.length > MAX_INT_DIGITS ? state : { ...state, integer: next };
}

/** Keypad entry to integer minor units (EGP: 12.5 -> 1250). */
export function keypadToMinor(state: KeypadState): number {
  const major = state.integer === '' ? 0 : Number.parseInt(state.integer, 10);
  const fraction = (state.fraction ?? '').padEnd(MAX_FRACTION_DIGITS, '0');
  return major * MINOR_PER_MAJOR + (fraction === '' ? 0 : Number.parseInt(fraction, 10));
}

/** What the keypad display shows while typing (`12.` and `12.5` stay visible). */
export function keypadDisplay(state: KeypadState, digits: Digits): string {
  const int = groupThousands(state.integer === '' ? '0' : state.integer, digits === 'arabic' ? '٬' : ',');
  const text = state.fraction === null ? int : `${int}${digits === 'arabic' ? '٫' : '.'}${state.fraction}`;
  return localizeDigits(text, digits);
}
