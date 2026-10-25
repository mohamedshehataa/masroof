import { describe, expect, it } from 'vitest';
import ar from './ar.json';
import en from './en.json';

function flat(o: unknown, prefix = ''): string[] {
  if (typeof o === 'string') return [prefix];
  if (o && typeof o === 'object') return Object.entries(o).flatMap(([k, v]) => flat(v, prefix ? `${prefix}.${k}` : k));
  return [prefix];
}

describe('REQ-UI-002 translation resources', () => {
  it('REQ-UI-002 ar.json and en.json have identical key sets', () => {
    expect(flat(ar).sort()).toEqual(flat(en).sort());
  });

  it('REQ-UI-002 every value is a non-empty string in both languages', () => {
    const walk = (o: unknown): void => {
      if (typeof o === 'string') expect(o.trim()).not.toBe('');
      else if (o && typeof o === 'object') Object.values(o).forEach(walk);
      else throw new Error('non-string leaf');
    };
    walk(ar);
    walk(en);
  });

  it('REQ-UI-002 the shell adds navigation, language, theme and 404 strings beyond the skeleton', () => {
    const keys = flat(en);
    expect(keys.filter((k) => /nav|tab/i.test(k)).length).toBeGreaterThanOrEqual(5);
    expect(keys.some((k) => /theme/i.test(k))).toBe(true);
    expect(keys.some((k) => /not.?found|404/i.test(k))).toBe(true);
    expect(keys.some((k) => /offline/i.test(k) && !k.startsWith('health.'))).toBe(true);
  });
});
