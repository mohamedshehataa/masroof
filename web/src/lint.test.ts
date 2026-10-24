// @vitest-environment node
import { ESLint } from 'eslint';
import { describe, expect, it } from 'vitest';

describe('REQ-OPS-025 i18n lint rule', () => {
  it('REQ-OPS-025 flags hard-coded JSX text with exactly one i18next/no-literal-string message on the <h1> line', async () => {
    const eslint = new ESLint({ cwd: process.cwd(), ignore: false });
    const [result] = await eslint.lintFiles(['test/fixtures/lint/hardcoded.tsx']);
    expect(result).toBeDefined();
    const hits = (result?.messages ?? []).filter((m) => m.ruleId === 'i18next/no-literal-string');
    expect(hits).toHaveLength(1);
    expect(hits[0]?.severity).toBe(2);
    expect(hits[0]?.line).toBe(2);
  });
});
