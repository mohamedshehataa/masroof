// @vitest-environment node
import { ESLint, type Linter } from 'eslint';
import { describe, expect, it } from 'vitest';

async function lint(file: string): Promise<Linter.LintMessage[]> {
  const eslint = new ESLint({ cwd: process.cwd(), ignore: false });
  const [result] = await eslint.lintFiles([file]);
  expect(result).toBeDefined();
  // Typed-lint noise is irrelevant here; the contract rules are non-@typescript-eslint rules.
  return (result?.messages ?? []).filter((m) => m.ruleId !== null && !m.ruleId.startsWith('@typescript-eslint/'));
}

describe('REQ-OPS-053 ESLint guards around the generated client', () => {
  it('REQ-OPS-053 flags arithmetic on Money.amountMinor outside src/lib/money.ts, and only arithmetic', async () => {
    const hits = (await lint('test/fixtures/lint/money-arithmetic.tsx')).filter((m) =>
      /amountMinor|money/i.test(m.message),
    );
    const lines = hits.map((h) => h.line);
    for (const line of [3, 4, 5, 7])
      expect(lines, `arithmetic on line ${String(line)} must be flagged`).toContain(line);
    expect(lines, 'plain assignment on line 8 must not be flagged').not.toContain(8);
    expect(hits.every((h) => h.severity === 2)).toBe(true);
  });

  it('REQ-OPS-053 flags raw fetch of /api/* URLs outside the generated client, but not other URLs', async () => {
    const hits = (await lint('test/fixtures/lint/raw-fetch.tsx')).filter((m) => /fetch|generated|api/i.test(m.message));
    const lines = hits.map((h) => h.line);
    expect(lines).toContain(1);
    expect(lines).not.toContain(2);
    expect(hits.find((h) => h.line === 1)?.severity).toBe(2);
  });
});
