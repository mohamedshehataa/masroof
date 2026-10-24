// @vitest-environment node
import { spawnSync } from 'node:child_process';
import { describe, expect, it } from 'vitest';

describe('REQ-OPS-025 tsc strictness', () => {
  it('REQ-OPS-025 noUncheckedIndexedAccess rejects `const first: string = ["a"][0]` naming the file', () => {
    const r = spawnSync(
      process.execPath,
      ['node_modules/typescript/bin/tsc', '-p', 'test/fixtures/tsc/tsconfig.json', '--noEmit'],
      {
        cwd: process.cwd(),
        encoding: 'utf8',
      },
    );
    expect(r.status).not.toBe(0);
    expect(r.stdout).toContain('planted.ts');
    expect(r.stdout).toContain('undefined');
  });
});
