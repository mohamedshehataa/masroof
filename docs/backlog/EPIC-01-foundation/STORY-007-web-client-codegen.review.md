# Review report: STORY-007 (TypeScript API client generated from the contract)

- Reviewer: qa-verifier acting as the combined verify + code-review agent (low risk tier), with a light security and bundle check
- Date: 2026-10-25
- Base: main ee08123; Head: bedbbc0 (fix commit on story/STORY-007-web-client-codegen); loop 2 of 3
- Companion: STORY-007-web-client-codegen.verify.md (commands and outputs)

## Loop 2

### Summary
The fix commit is focused and addresses the loop-1 review well:
- the js-yaml override clears osv and npm audit locally;
- `rewrite()` now really enforces a single match, and uses a function replacer so `$` sequences cannot be misread;
- the DOWN rule moved from a string patch inside generated code to a small, tested, hand-written `rejectDownHealth()` in clientConfig.ts;
- `window.fetch`, `globalThis.fetch` and `self.fetch` are banned outside src/api;
- web/CLAUDE.md now documents the committed generated output.

The design is now acceptable. The merge is still blocked: the required PR gate is red because semgrep (security-fast) flags 3 blocking patterns in the test-designer file web/src/api/generated.test.ts.

### Checklist (delta vs loop 1)
| Area | Verdict | Notes |
|---|---|---|
| Dependency fix (overrides js-yaml 4.3.2) | OK | Minimal and exact-pinned. The nested lock entry is removed. `npm audit` 0, local osv "No issues found". It forces the override for every js-yaml consumer; acceptable because 4.3.2 is the same major. |
| generate.mjs patches | OK | Exactly-once is enforced and the error message includes the count. The remaining patches are runtime-type tightening only. The `as unknown as` cast remains (cosmetic, accepted). |
| rejectDownHealth in clientConfig.ts | OK | It is hand-written and unit-tested: DOWN rejects; UP, other paths, non-ok responses and non-JSON pass through. It re-reads the body via `clone()`, which is fine for /health. Being path-keyed inside the fetch wrapper is a little implicit; a short comment that it is the only per-path rule would help future readers. |
| ESLint global-fetch selector | OK | src/api/**/*.ts gets only the contract selectors, so clientConfig.ts may use globalThis.fetch. Note: a `src/api/**/*.tsx` file would still get the global-fetch ban through the tsx block, which is harmless. Remaining static bypasses are listed in the verify report (MINOR follow-up). |
| NextCursor allowlist | Unchanged (MINOR) | Still a shape filter rather than a "cannot express" detector. Acceptable for now; propose a follow-up post-generation scan for emitted `any` per schema. |
| Docs | Mostly OK | web/CLAUDE.md updated. The DECISIONS line is corrected but still says "post-patched (... getHealth rejects on status DOWN)" before saying the rule moved (NIT). TRACEABILITY.md is not refreshed on the branch (NIT; integrate runs make trace). |
| Tests / integrity | OK | New clientConfig.test.ts (REQ-OPS-053 tags). No existing test touched. TEST_INTEGRITY OK (11 -> 102 assertions). |

### Security (light)
- Local: `npm audit` -> 0; `osv-scanner scan source --lockfile web/package-lock.json` -> No issues found.
- CI run 40143061439, job Security: **semgrep failed** with 3 blocking findings in web/src/api/generated.test.ts: `javascript.lang.security.audit.spawn-shell-true` (line 26) and `detect-non-literal-regexp` (lines 91, 97). The inputs are trusted (the repo's own contract and fixed npm args), so the real risk is nil, but the gate is blocking. The osv step was skipped because semgrep failed first, so osv is unproven in CI.
- Generated code: unchanged from loop 1 apart from the removed DOWN patch; still no `any` and no dynamic code execution.

### Bundle
- Not rebuilt in loop 2. The change set adds about 15 lines to clientConfig.ts and removes a patch; the effect is negligible against the loop-1 100.25 KB gzip.

### Findings (loop 2)
- [MAJOR] web/src/api/generated.test.ts:26,91,97 -- semgrep blocks the required security-fast job, so the PR gate is red. Route to test-designer: justified `// nosemgrep: <rule-id>` comments, or escape the regex inputs and avoid `shell` -- `gh run view 40143061439 -R mohamedshehataa/masroof --job 121018637089 --log-failed`
- [MINOR] web/eslint.config.js -- remaining fetch and money static bypasses (follow-up story)
- [MINOR] web/scripts/generate.mjs:13-22 -- NextCursor shape allowlist unchanged
- [NIT] docs/DECISIONS.md STORY-007 line partly self-contradictory about where the DOWN rule lives
- [NIT] docs/TRACEABILITY.md not refreshed on the branch

### Verdict
CHANGES_REQUESTED: implementation review items are resolved; the remaining blocker is the semgrep failure in the test file. The PR gate must be green before integrate.

## Loop 3 (head e424749) — PASS
- The test-designer fix (e424749) touches only web/src/api/generated.test.ts. Both `expect(missing).toEqual([])` assertions are unchanged. The `\b<id>\b` RegExp became a Set of `[A-Za-z0-9_]+` tokens, which matches the same names or fewer: all 79 operationIds are word-only, and schema names are word-only by construction. The spawn shell call keeps a scoped `nosemgrep` with its reason.
- CI run 40144396415 at e424749: every job is green (Android skipped, module absent), including Security: gitleaks, semgrep, and osv-scanner ("web/package-lock.json … 440 packages … No issues found"). PR gate success.
- `cd web && npx vitest run`: 10 files, 60/60.
- Open, non-blocking follow-ups: ESLint static bypasses (fetch via a variable, `new Request`, origin concatenation, relative api/; money via unary minus, destructuring, computed key, optional chain); a post-generation `any` detector to replace the NextCursor allowlist; contract `minLength` for `Health.version`.

STATUS: APPROVE
