import { afterEach, describe, expect, it, vi } from 'vitest';

// The formatter is specified as src/lib/money.ts (web/CLAUDE.md, REQ-UI-005). Test-designer's expectation of the
// public API: formatMoney(money, { locale: 'en' | 'ar', digits: 'western' | 'arabic-indic' }): string.
type Digits = 'western' | 'arabic-indic';
interface Money {
  amountMinor: number;
  currency: string;
}
type Fmt = (m: Money, o: { locale: 'en' | 'ar'; digits: Digits }) => string;

async function load(): Promise<Fmt> {
  const mod = (await import('./money')) as { formatMoney: Fmt };
  return mod.formatMoney;
}

// Bidi isolates / marks and NBSP are allowed around the run (arabic-text skill 3); compare the visible text.
const MARKS = new RegExp('[\u200e\u200f\u061c\u2066-\u2069]', 'g');
const SPACES = new RegExp('[\u00a0\u202f]', 'g');
const MINUS = new RegExp('\u2212', 'g');
const visible = (s: string) => s.replace(MARKS, '').replace(SPACES, ' ').replace(MINUS, '-');

const egp = (amountMinor: number): Money => ({ amountMinor, currency: 'EGP' });

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe('REQ-UI-005 amount and digit formatting', () => {
  it('REQ-UI-005 en renders 125000 EGP minor units as 1,250 EGP', async () => {
    const f = await load();
    expect(visible(f(egp(125000), { locale: 'en', digits: 'western' }))).toBe('1,250 EGP');
  });

  it('REQ-UI-005 ar with Arabic-Indic digits renders ١٬٢٥٠ ج.م', async () => {
    const f = await load();
    expect(visible(f(egp(125000), { locale: 'ar', digits: 'arabic-indic' }))).toBe('١٬٢٥٠ ج.م');
  });

  it('REQ-UI-005 ar with Western digits renders 1,250 ج.م', async () => {
    const f = await load();
    expect(visible(f(egp(125000), { locale: 'ar', digits: 'western' }))).toBe('1,250 ج.م');
  });

  it.each([
    [125050, 'en', 'western', '1,250.50 EGP'],
    [125050, 'ar', 'arabic-indic', '١٬٢٥٠٫٥٠ ج.م'],
    [125050, 'ar', 'western', '1,250.50 ج.م'],
    [125005, 'en', 'western', '1,250.05 EGP'],
    [105, 'en', 'western', '1.05 EGP'],
    [1, 'en', 'western', '0.01 EGP'],
    [99, 'en', 'western', '0.99 EGP'],
  ] as const)('REQ-UI-005 piasters show only when non-zero: %i %s/%s -> %s', async (minor, locale, digits, want) => {
    const f = await load();
    expect(visible(f(egp(minor), { locale, digits }))).toBe(want);
  });

  it('REQ-UI-005 never shows .00 for whole amounts', async () => {
    const f = await load();
    for (const minor of [100, 125000, 100000000]) {
      expect(f(egp(minor), { locale: 'en', digits: 'western' })).not.toMatch(/\.0+\b/);
    }
  });

  it('REQ-UI-005 zero renders 0 EGP and ٠ ج.م', async () => {
    const f = await load();
    expect(visible(f(egp(0), { locale: 'en', digits: 'western' }))).toBe('0 EGP');
    expect(visible(f(egp(0), { locale: 'ar', digits: 'arabic-indic' }))).toBe('٠ ج.م');
  });

  it('REQ-UI-005 negative values keep the sign and the currency in one run', async () => {
    const f = await load();
    expect(visible(f(egp(-125000), { locale: 'en', digits: 'western' }))).toBe('-1,250 EGP');
    expect(visible(f(egp(-5), { locale: 'en', digits: 'western' }))).toBe('-0.05 EGP');
    expect(visible(f(egp(-125050), { locale: 'ar', digits: 'arabic-indic' }))).toMatch(/^-?١٬٢٥٠٫٥٠-? ج\.م$/);
    expect(visible(f(egp(-125050), { locale: 'ar', digits: 'arabic-indic' }))).toContain('-');
  });

  it('REQ-UI-005 large values group in thousands and stay exact (integer math, no float drift)', async () => {
    const f = await load();
    expect(visible(f(egp(123456789012), { locale: 'en', digits: 'western' }))).toBe('1,234,567,890.12 EGP');
    expect(visible(f(egp(123456789012), { locale: 'ar', digits: 'arabic-indic' }))).toBe('١٬٢٣٤٬٥٦٧٬٨٩٠٫١٢ ج.م');
    expect(visible(f(egp(Number.MAX_SAFE_INTEGER), { locale: 'en', digits: 'western' }))).toBe(
      '90,071,992,547,409.91 EGP',
    );
  });

  it('REQ-UI-005 digit setting only changes the digits: western and Arabic-Indic agree after mapping', async () => {
    const f = await load();
    const map = '٠١٢٣٤٥٦٧٨٩';
    for (const minor of [0, 1, 99, 100, 12345, 125050, 999999999, 123456789012]) {
      const w = visible(f(egp(minor), { locale: 'ar', digits: 'western' }));
      const a = visible(f(egp(minor), { locale: 'ar', digits: 'arabic-indic' }));
      const back = a
        .replace(/[٠-٩]/g, (d) => String(map.indexOf(d)))
        .replace(/٬/g, ',')
        .replace(/٫/g, '.');
      expect(back).toBe(w);
    }
  });

  it('REQ-UI-005 en locale uses the ISO code for other currencies', async () => {
    const f = await load();
    expect(visible(f({ amountMinor: 125000, currency: 'USD' }, { locale: 'en', digits: 'western' }))).toBe('1,250 USD');
    expect(visible(f({ amountMinor: 125050, currency: 'EUR' }, { locale: 'en', digits: 'western' }))).toBe(
      '1,250.50 EUR',
    );
  });

  it('REQ-UI-005 a currency without a known Arabic symbol (SAR) falls back to the ISO code', async () => {
    const f = await load();
    expect(visible(f({ amountMinor: 125000, currency: 'SAR' }, { locale: 'ar', digits: 'arabic-indic' }))).toBe(
      '١٬٢٥٠ SAR',
    );
    expect(visible(f({ amountMinor: 125000, currency: 'SAR' }, { locale: 'ar', digits: 'western' }))).toBe('1,250 SAR');
  });

  it.each([1.5, Number.NaN, Number.POSITIVE_INFINITY, Number.MAX_SAFE_INTEGER + 1])(
    'REQ-UI-005 throws in dev for a non-safe-integer amountMinor (%s)',
    async (bad) => {
      const f = await load();
      expect(() => f(egp(bad), { locale: 'en', digits: 'western' })).toThrow();
    },
  );

  it.each([1.5, Number.NaN, Number.MAX_SAFE_INTEGER + 1])(
    'REQ-UI-005 renders an em dash in production for a non-safe-integer amountMinor (%s)',
    async (bad) => {
      vi.stubEnv('DEV', false);
      vi.stubEnv('PROD', true);
      vi.resetModules();
      const f = await load();
      expect(f(egp(bad), { locale: 'en', digits: 'western' })).toBe('—');
      expect(f(egp(bad), { locale: 'ar', digits: 'arabic-indic' })).toBe('—');
    },
  );
});
