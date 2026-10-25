import { describe, expect, it } from 'vitest';
import { cairoDayKey, cairoToday, monthRange, occurredAtFor, relativeDay } from './dates';
import { EMPTY_KEYPAD, formatMoney, keypadDisplay, keypadToMinor, pressKey, sumMinor, type KeypadKey } from './money';

const type = (keys: KeypadKey[]) => keys.reduce(pressKey, EMPTY_KEYPAD);

describe('REQ-UI-002 money formatting', () => {
  it('formats Western digits with grouping, dropping .00 on whole amounts', () => {
    expect(formatMoney({ amountMinor: 125000, currency: 'EGP' }, { lang: 'en', digits: 'western' })).toBe('1,250 EGP');
    expect(formatMoney({ amountMinor: 125050, currency: 'EGP' }, { lang: 'en', digits: 'western' })).toBe(
      '1,250.50 EGP',
    );
    expect(formatMoney({ amountMinor: 5, currency: 'EGP' }, { lang: 'en', digits: 'western' })).toBe('0.05 EGP');
  });
  it('formats Arabic-Indic digits and the ج.م label', () => {
    expect(formatMoney({ amountMinor: 125000, currency: 'EGP' }, { lang: 'ar', digits: 'arabic' })).toBe('١٬٢٥٠ ج.م');
    expect(formatMoney({ amountMinor: 125050, currency: 'EGP' }, { lang: 'ar', digits: 'arabic' })).toBe(
      '١٬٢٥٠٫٥٠ ج.م',
    );
  });
  it('keeps decimals on request and handles negatives and other currencies', () => {
    expect(
      formatMoney({ amountMinor: 100, currency: 'EGP' }, { lang: 'en', digits: 'western', alwaysDecimals: true }),
    ).toBe('1.00 EGP');
    expect(formatMoney({ amountMinor: -250, currency: 'USD' }, { lang: 'ar', digits: 'western' })).toBe('-2.50 USD');
  });
  it('sums integer minor units exactly', () => {
    expect(sumMinor([10, 20, 5])).toBe(35);
    expect(sumMinor([])).toBe(0);
  });
});

describe('REQ-UI-023 amount keypad produces integer minor units', () => {
  it('12.5 is 1250 and 12.05 is 1205', () => {
    expect(keypadToMinor(type(['1', '2', '.', '5']))).toBe(1250);
    expect(keypadToMinor(type(['1', '2', '.', '0', '5']))).toBe(1205);
  });
  it('limits to two decimals, ignores a second point and leading zeros', () => {
    expect(keypadToMinor(type(['0', '0', '7', '.', '1', '2', '3', '.']))).toBe(712);
  });
  it('backspace removes digits, then the point', () => {
    const s = type(['1', '.', '5', 'back', 'back', 'back']);
    expect(s).toEqual(EMPTY_KEYPAD);
    expect(keypadToMinor(s)).toBe(0);
  });
  it('caps the integer part and localizes the display', () => {
    expect(type(Array.from({ length: 12 }, () => '9' as const)).integer).toHaveLength(9);
    expect(keypadDisplay(type(['1', '2', '3', '4', '.', '5']), 'arabic')).toBe('١٬٢٣٤٫٥');
    expect(keypadDisplay(EMPTY_KEYPAD, 'western')).toBe('0');
  });
});

describe('REQ-UI-020 Cairo dates', () => {
  it('assigns instants to the Cairo day across the UTC midnight boundary', () => {
    expect(cairoDayKey('2026-03-10T22:30:00Z')).toBe('2026-03-11');
    expect(cairoDayKey('2026-01-10T21:30:00Z')).toBe('2026-01-10');
  });
  it('month range is the calendar month', () => {
    expect(monthRange('2026-02-14')).toEqual({ from: '2026-02-01', to: '2026-02-28' });
    expect(monthRange('2026-10-25')).toEqual({ from: '2026-10-01', to: '2026-10-31' });
  });
  it('occurredAtFor uses 12:00 Cairo for another day, honouring DST', () => {
    expect(occurredAtFor('2026-01-10', new Date('2026-02-01T00:00:00Z'))).toBe('2026-01-10T10:00:00.000Z');
    expect(occurredAtFor('2026-07-10', new Date('2026-08-01T00:00:00Z'))).toBe('2026-07-10T09:00:00.000Z');
  });
  it('occurredAtFor keeps the current instant for today', () => {
    const now = new Date('2026-05-05T08:15:00Z');
    expect(occurredAtFor(cairoToday(now), now)).toBe(now.toISOString());
  });
  it('labels today and yesterday', () => {
    const now = new Date('2026-05-05T08:15:00Z');
    expect(relativeDay('2026-05-05', now)).toBe('today');
    expect(relativeDay('2026-05-04', now)).toBe('yesterday');
    expect(relativeDay('2026-05-01', now)).toBeNull();
  });
});
