// @vitest-environment node
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const SRC = join(process.cwd(), 'src');

function sources(dir: string): string[] {
  return readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((e) => e.isFile() && /\.(ts|tsx)$/.test(e.name))
    .map((e) => join(e.parentPath, e.name))
    .filter((f) => !/[\\/]api[\\/]generated[\\/]/.test(f));
}

describe('REQ-OPS-053 hand-written health client is replaced by the generated one', () => {
  it('REQ-OPS-053 src/api/health.ts is deleted', () => {
    expect(existsSync(join(SRC, 'api', 'health.ts'))).toBe(false);
  });

  it('REQ-OPS-053 App imports getHealth from the generated client', () => {
    const app = readFileSync(join(SRC, 'App.tsx'), 'utf8');
    expect(app).toMatch(/import\s*\{[^}]*\bgetHealth\b[^}]*\}\s*from\s*'\.\/api\/generated[^']*'/);
  });

  it('REQ-OPS-053 no source file still imports the hand-written health module', () => {
    const offenders = sources(SRC)
      .filter((f) => !/\.test\.tsx?$/.test(f))
      .filter((f) => /from\s*'(\.\/|\.\.\/)(api\/)?health'/.test(readFileSync(f, 'utf8')));
    expect(offenders).toEqual([]);
  });
});
