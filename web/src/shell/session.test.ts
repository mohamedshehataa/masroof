import { describe, expect, it } from 'vitest';
import { parseSession } from './session';
import { parseDigits, parseTheme } from './settings';

describe('REQ-UI-004 session seam', () => {
  it('REQ-UI-004 only a non-empty JSON object counts as signed in', () => {
    expect(parseSession(null)).toBe(false);
    expect(parseSession('')).toBe(false);
    expect(parseSession('not json')).toBe(false);
    expect(parseSession('[]')).toBe(false);
    expect(parseSession('{}')).toBe(false);
    expect(parseSession('"x"')).toBe(false);
    expect(parseSession('{"accessToken":"a"}')).toBe(true);
  });
});

describe('REQ-UI-003 / REQ-UI-005 stored setting parsing', () => {
  it('REQ-UI-003 unknown theme values fall back to system', () => {
    expect(parseTheme('dark')).toBe('dark');
    expect(parseTheme('light')).toBe('light');
    expect(parseTheme('purple')).toBe('system');
    expect(parseTheme(null)).toBe('system');
  });

  it('REQ-UI-005 unknown digit values fall back to western', () => {
    expect(parseDigits('arabic-indic')).toBe('arabic-indic');
    expect(parseDigits('x')).toBe('western');
    expect(parseDigits(null)).toBe('western');
  });
});
