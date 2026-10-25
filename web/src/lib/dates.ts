// Business dates are Africa/Cairo; instants stay UTC ISO strings (money-and-dates skill).
import { localizeDigits, type Digits, type Lang } from './money';

export const CAIRO = 'Africa/Cairo';

const dayFormat = new Intl.DateTimeFormat('en-CA', {
  timeZone: CAIRO,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

/** `YYYY-MM-DD` of an instant in Cairo. */
export function cairoDayKey(iso: string | Date): string {
  return dayFormat.format(typeof iso === 'string' ? new Date(iso) : iso);
}

export function cairoToday(now: Date = new Date()): string {
  return cairoDayKey(now);
}

export function cairoMonthKey(iso: string | Date): string {
  return cairoDayKey(iso).slice(0, 7);
}

/** Inclusive `[from, to]` day keys of the calendar month containing `dayKey` (month_start_day 1). */
export function monthRange(dayKey: string): { from: string; to: string } {
  const [y, m] = dayKey.split('-').map(Number);
  const year = y ?? 1970;
  const month = m ?? 1;
  const last = new Date(Date.UTC(year, month, 0)).getUTCDate();
  const ym = `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}`;
  return { from: `${ym}-01`, to: `${ym}-${String(last).padStart(2, '0')}` };
}

function cairoOffsetMinutes(at: Date): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: CAIRO,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).formatToParts(at);
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value ?? 0);
  const asUtc = Date.UTC(get('year'), get('month') - 1, get('day'), get('hour'), get('minute'));
  return Math.round((asUtc - at.getTime()) / 60_000);
}

/**
 * Instant for an expense on a chosen Cairo day. Today keeps the current time; another day uses 12:00 Cairo
 * (far from the midnight DST transitions, so the offset is unambiguous).
 */
export function occurredAtFor(dayKey: string, now: Date = new Date()): string {
  if (dayKey === cairoToday(now)) return now.toISOString();
  const [y, m, d] = dayKey.split('-').map(Number);
  const noonUtc = new Date(Date.UTC(y ?? 1970, (m ?? 1) - 1, d ?? 1, 12, 0));
  return new Date(noonUtc.getTime() - cairoOffsetMinutes(noonUtc) * 60_000).toISOString();
}

export function addDays(dayKey: string, delta: number): string {
  const [y, m, d] = dayKey.split('-').map(Number);
  return new Date(Date.UTC(y ?? 1970, (m ?? 1) - 1, (d ?? 1) + delta)).toISOString().slice(0, 10);
}

export function relativeDay(dayKey: string, now: Date = new Date()): 'today' | 'yesterday' | null {
  const today = cairoToday(now);
  if (dayKey === today) return 'today';
  return dayKey === addDays(today, -1) ? 'yesterday' : null;
}

export function formatDay(dayKey: string, lang: Lang, digits: Digits): string {
  const [y, m, d] = dayKey.split('-').map(Number);
  const date = new Date(Date.UTC(y ?? 1970, (m ?? 1) - 1, d ?? 1, 12));
  const text = new Intl.DateTimeFormat(`${lang === 'ar' ? 'ar-EG' : 'en-GB'}-u-nu-latn`, {
    timeZone: 'UTC',
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  }).format(date);
  return localizeDigits(text, digits);
}
