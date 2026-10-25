// @vitest-environment node
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

function cssFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) return f === 'generated' ? [] : cssFiles(p);
    return p.endsWith('.css') ? [p] : [];
  });
}
const css = () =>
  cssFiles(join(process.cwd(), 'src'))
    .map((f) => readFileSync(f, 'utf8'))
    .join('\n')
    .toLowerCase();

describe('REQ-UI-003 design tokens', () => {
  it('REQ-UI-003 defines the deep teal primary (#0F766E) and warm amber accent (#D97706)', () => {
    const all = css();
    expect(all).toContain('#0f766e');
    expect(all).toContain('#d97706');
  });

  it('REQ-UI-003 declares IBM Plex Sans Arabic and Inter with system fallbacks', () => {
    const all = css();
    expect(all).toContain('ibm plex sans arabic');
    expect(all).toContain('inter');
    expect(all).toMatch(/system-ui|sans-serif/);
  });

  it('REQ-UI-003 amounts use tabular numerals', () => {
    expect(css()).toMatch(/(amount|\.num|\.tnum)[^{]*\{[^}]*font-variant-numeric:\s*tabular-nums/);
  });

  it('REQ-UI-003 has a dark palette driven by both prefers-color-scheme and the .dark class', () => {
    const all = css();
    expect(all).toContain('prefers-color-scheme: dark');
    expect(all).toMatch(/\.dark\b/);
  });
});
