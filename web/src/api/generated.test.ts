// @vitest-environment node
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';

const WEB = process.cwd();
const CONTRACT = join(WEB, '..', 'contracts', 'openapi.yaml');
const GENERATED = join(WEB, 'src', 'api', 'generated');
const NPM = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const SLOW = 120_000;

function npm(args: string[], cwd = WEB) {
  // Reason: fixed npm arguments, no user input; npm.cmd needs a shell on Windows.
  // nosemgrep: javascript.lang.security.audit.spawn-shell-true.spawn-shell-true
  return spawnSync(NPM, args, { cwd, encoding: 'utf8', shell: process.platform === 'win32' });
}

function listFiles(dir: string): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((e) => e.isFile())
    .map((e) => join(e.parentPath, e.name))
    .sort();
}

/** Stable digest of every generated file (path + bytes). */
function digest(dir: string): string {
  const h = createHash('sha256');
  for (const f of listFiles(dir)) {
    h.update(relative(dir, f).replaceAll('\\', '/'));
    h.update(readFileSync(f));
  }
  return h.digest('hex');
}

/** Whole-word identifier set (same as a \b match for [A-Za-z0-9_] names, without building a RegExp from input). */
function identifiers(src: string): Set<string> {
  return new Set(src.match(/[A-Za-z0-9_]+/g) ?? []);
}

function generatedSource(dir = GENERATED): string {
  return listFiles(dir)
    .filter((f) => /\.(ts|tsx)$/.test(f))
    .map((f) => readFileSync(f, 'utf8'))
    .join('\n');
}

const contract = readFileSync(CONTRACT, 'utf8');
const operationIds = [...contract.matchAll(/^\s+operationId:\s*(\S+)\s*$/gm)].map((m) => m[1] as string);
const schemaBlock = contract.slice(contract.indexOf('\n  schemas:\n') + '\n  schemas:\n'.length);
const schemaNames = [...schemaBlock.matchAll(/^ {4}([A-Za-z][A-Za-z0-9_]*):\s*$/gm)].map((m) => m[1] as string);

describe('REQ-OPS-053 npm scripts wire generation into check-fast and build', () => {
  const pkg = JSON.parse(readFileSync(join(WEB, 'package.json'), 'utf8')) as { scripts: Record<string, string> };

  it('REQ-OPS-053 defines an npm "generate" script', () => {
    expect(pkg.scripts.generate).toBeTruthy();
  });

  it('REQ-OPS-053 runs generate as part of check-fast and build', () => {
    expect(pkg.scripts['check-fast']).toMatch(/generate/);
    expect(pkg.scripts.build).toMatch(/generate/);
  });
});

describe('REQ-OPS-053 generated client covers the contract', () => {
  it('REQ-OPS-053 contract parsing found operations and schemas (guards the test itself)', () => {
    expect(operationIds.length).toBeGreaterThan(50);
    expect(schemaNames).toContain('Money');
    expect(schemaNames).toContain('Health');
  });

  it('REQ-OPS-053 npm run generate exits 0 and writes src/api/generated', { timeout: SLOW }, () => {
    const r = npm(['run', 'generate']);
    expect(r.status, `${r.stdout}\n${r.stderr}`).toBe(0);
    expect(listFiles(GENERATED).length).toBeGreaterThan(0);
  });

  it('REQ-OPS-053 generated sources are fetch-based', () => {
    expect(generatedSource()).toMatch(/\bfetch\b/);
  });

  it('REQ-OPS-053 every operationId in the contract has a generated function', () => {
    const src = generatedSource();
    const tokens = identifiers(src);
    const missing = operationIds.filter((id) => !tokens.has(id));
    expect(missing).toEqual([]);
  });

  it('REQ-OPS-053 every schema in the contract has a generated type', () => {
    const src = generatedSource();
    const tokens = identifiers(src);
    const missing = schemaNames.filter((n) => !tokens.has(n));
    expect(missing).toEqual([]);
  });

  it('REQ-OPS-053 generated sources never fall back to any', () => {
    const src = generatedSource();
    expect(src.length).toBeGreaterThan(0);
    expect(src).not.toMatch(/(:|<|\bas)\s*any\b/);
  });

  it('REQ-OPS-053 Money.amountMinor is typed number (not string, bigint or any)', { timeout: SLOW }, () => {
    const r = spawnSync(
      process.execPath,
      ['node_modules/typescript/bin/tsc', '-p', 'test/fixtures/generated/tsconfig.json', '--noEmit'],
      { cwd: WEB, encoding: 'utf8' },
    );
    expect(r.status, r.stdout).toBe(0);
  });
});

describe('REQ-OPS-053 generation is reproducible and detects drift', () => {
  it('REQ-OPS-053 running generate twice reproduces byte-identical output', { timeout: SLOW * 2 }, () => {
    expect(npm(['run', 'generate']).status).toBe(0);
    const first = digest(GENERATED);
    expect(listFiles(GENERATED).length).toBeGreaterThan(0);
    expect(npm(['run', 'generate']).status).toBe(0);
    expect(digest(GENERATED)).toBe(first);
  });

  it(
    'REQ-OPS-053 a hand edit in generated/ is overwritten by generate (CI git diff would catch it)',
    { timeout: SLOW },
    () => {
      expect(npm(['run', 'generate']).status).toBe(0);
      const before = digest(GENERATED);
      const target = listFiles(GENERATED).find((f) => f.endsWith('.ts'));
      expect(target).toBeDefined();
      const original = readFileSync(target as string);
      try {
        writeFileSync(target as string, `${original.toString('utf8')}\n// hand edit\n`);
        expect(digest(GENERATED)).not.toBe(before);
        expect(npm(['run', 'generate']).status).toBe(0);
        expect(digest(GENERATED)).toBe(before);
      } finally {
        writeFileSync(target as string, original);
      }
    },
  );
});

describe('REQ-OPS-053 unsupported schema fails generation by name', () => {
  it('REQ-OPS-053 a schema with type [string, null] makes generate fail naming the schema', { timeout: SLOW }, () => {
    // Sandbox mirrors the repo layout (contracts/ + web/) so the generate script resolves ../contracts/openapi.yaml.
    const root = mkdtempSync(join(tmpdir(), 'masroof-gen-'));
    try {
      mkdirSync(join(root, 'contracts'));
      const poisoned = contract.replace(
        '\n  schemas:\n',
        "\n  schemas:\n    UnsupportedNullableProbe:\n      type: [string, 'null']\n",
      );
      expect(poisoned).not.toBe(contract);
      writeFileSync(join(root, 'contracts', 'openapi.yaml'), poisoned);
      const web = join(root, 'web');
      cpSync(WEB, web, {
        recursive: true,
        filter: (src) =>
          !/[\\/](node_modules|dist|coverage|test-results|playwright-report)([\\/]|$)/.test(src.slice(WEB.length)),
      });
      symlinkSync(join(WEB, 'node_modules'), join(web, 'node_modules'), 'junction');
      const r = npm(['run', 'generate'], web);
      expect(r.status, 'generate must fail for the unsupported schema').not.toBe(0);
      expect(`${r.stdout}\n${r.stderr}`).toContain('UnsupportedNullableProbe');
      expect(generatedSource(join(web, 'src', 'api', 'generated'))).not.toContain('UnsupportedNullableProbe: any');
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });
});
