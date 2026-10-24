# Verify report: STORY-007 (TypeScript API client generated from the contract)

- Verifier: qa-verifier (combined verify + review, low risk tier), fresh context
- Date: 2026-10-25
- Base: main cbcad1f72ed31075e1c9eb54a62206df342d22a3
- Head: e3375882f831d7c36d30f00b5da7319f562ce164 (story/STORY-007-web-client-codegen; c7e0691 start, 16562e3 tests, e337588 impl)
- Loop: 1 of 3

## Environment
- Own worktree, detached at e337588, Windows 11, Git Bash, Node 24.15.0.
- `cd web; npm ci` -> ok (npm audit: 4 high, all in the @hey-api/openapi-ts -> json-schema-ref-parser -> js-yaml 4.2.0 chain).
- `cd web; npm run check-fast` -> EXIT=0; generate ok, tsc ok, eslint ok, prettier ok, vitest 9 files / 57 tests passed. No git diff afterwards.
- `make check-fast MODULE=web` -> `check-fast GREEN [web] in 15s`.
- `make check` -> `check GREEN` (check-fast sdlc+backend+web GREEN in 85s, COVERAGE_GATE OK, traceability gate PASS, TEST_INTEGRITY OK). It regenerated docs/TRACEABILITY.md (REQ-OPS-053 READY/no test -> IN_PROGRESS/covered); the branch does not carry that refresh yet. I restored the file in my worktree.
- The sandbox refused `source scripts/env.sh` and running hook scripts directly, so make ran with PATH set by hand (GnuWin32 make + Python312). The protect-files hook was checked by reading it, not by running it.

## Criteria
| REQ / clause | Result | Evidence |
|---|---|---|
| REQ-OPS-053 G1: `npm run generate` is wired into check-fast and build | PASS | package.json `generate`, `check-fast` and `build` all run `npm run generate`; the vitest `REQ-OPS-053 runs generate as part of check-fast and build` passed; the generate banner shows in the `npm run check-fast` and `npm run build` output |
| G1: fetch client + types for every operation and schema | PASS | `REQ-OPS-053 every operationId ... has a generated function` and `... every schema ... has a generated type` passed. index.ts exports 80 SDK functions; types and valibot validators cover Health, Money, NextCursor and others |
| G1: `Money.amountMinor` typed `number` | PASS | `REQ-OPS-053 Money.amountMinor is typed number` (tsc on test/fixtures/generated, with ts-expect-error for string and bigint) passed |
| G1: lint forbids arithmetic on amountMinor outside src/lib/money.ts | PASS (canonical forms), with bypasses | `REQ-OPS-053 flags arithmetic ...` passed. My stdin probe: `*`, `+`, `/`, `+=`, `++`, reduce-sum and parenthesised forms are flagged, `<` and Math.abs are not, and src/lib/money.ts is exempt. Unary minus, destructuring, computed key and optional-chain `m?.amountMinor + 1` are not flagged (see Findings) |
| G1: raw fetch of /api/* fails ESLint outside the generated client | PASS (canonical forms), with bypasses | `REQ-OPS-053 flags raw fetch ...` passed. Probe: `fetch('/api/x')` and a backtick `/api/x` literal are flagged, `/apix` and `/health` are not. `window.fetch`, `globalThis.fetch`, a URL in a variable, `new Request(...)`, origin concatenation and a relative `api/x` are not flagged |
| G2: hand-written health.ts deleted; App imports getHealth from the generated client | PASS | `health-replaced.test.ts` (3 tests) passed; `git show e337588 --stat` shows `web/src/api/health.ts` deleted (36 lines) and App.tsx importing `./api/generated` |
| G2: runtime validation kept; REQ-OPS-021 tests, incl. schema-invalid 200, pass | PASS | health.test.ts (REQ-OPS-021, 11 assertions both before and after) and generated-health.test.ts pass in vitest. `npx playwright test --grep-invert "real backend"` -> 18 passed (4.7s), including the 200 status DOWN, 200 missing version and 200 gitSha XYZ cases |
| U1: a hand edit in generated/ is overwritten, byte-for-byte | PASS | I appended a comment to sdk.gen.ts, renamed vGetHealthResponse and added a stray `generated/stray.ts`, then ran `npm run generate` -> exit 0; `git status --porcelain` was empty and the stray file was removed (clean: true). Two consecutive generate runs -> `git diff --exit-code` clean. protect-files.sh line 49 matches `^web/src/api/generated/` (read, not executed) |
| U2: an unsupported schema fails generate by name | PASS (literal), weak design | `REQ-OPS-053 a schema with type [string, null] makes generate fail naming the schema` passed. See the NextCursor finding: the check is a top-level shape allowlist, not a real "cannot express" detector |

## Findings
| Severity | Where | What | Reproduce |
|---|---|---|---|
| MAJOR | web/package-lock.json (`node_modules/@hey-api/json-schema-ref-parser/node_modules/js-yaml` 4.2.0) | The new generator pulls in js-yaml 4.2.0 with 3 High advisories (GHSA-2883-xcg3-v3hh, GHSA-52cp-r559-cp3m, GHSA-5p4m-2wfm-xmqj; fixed in 4.3.0 to 4.3.2). The base lockfile has only js-yaml 4.3.2. PR CI job `security-fast` runs osv-scanner-action recursively, and `gate` lists `security-fast` in `needs`, so the required PR gate is expected to go red. Real risk is low: a dev-only DoS on our own contract file. The gate still blocks the merge. Fix with an npm `overrides` entry pinning js-yaml to at least 4.3.2 under @hey-api/json-schema-ref-parser, or with a justified osv ignore entry plus a DECISIONS line. | `osv-scanner scan source --lockfile web/package-lock.json` -> "1 package affected by 3 known vulnerabilities (3 High)"; `npm audit` -> 4 high |
| MINOR | web/scripts/generate.mjs:13-22 | The unsupported-schema pre-check rejects any top-level schema whose `type` is an array, except the allowlisted NextCursor. NextCursor (string or null) and about 40 nested `type: [string, 'null']` / `[number, 'null']` properties (Member.phoneE164, ReviewItem.matchScore and others) are generated correctly (optional `string or null` types, `v.nullish(...)`). So the check produces false positives: the next top-level nullable schema will fail generate although the generator supports it. It is also blind to nested properties and to what the generator really emits. The criterion's premise ("type: [string, 'null'] is unsupported") is wrong for this generator. A sounder guard is a post-generation scan that fails, naming the schema, when a generated type is `any`. It does not hide a real issue in today's contract. | Read contracts/openapi.yaml lines 2187-2188, 2457, 2819; web/src/api/generated/types.gen.ts:33 and :197; valibot.gen.ts:238 |
| MINOR | web/scripts/generate.mjs:43-50 | `rewrite()` checks `includes` (at least once) and `String.replace` swaps only the first match. DECISIONS.md claims "each patch must match exactly once", which is not enforced: if a pattern appears twice, the second copy stays silently. The vitest no-any regex is the only backstop. | Read lines 46-49 |
| MINOR | web/scripts/generate.mjs:53-61 | The "any-free" patches mostly move unsafety rather than remove it: `requestImpl as unknown as Client['request']` and the `getValidRequestBody(opts) as BodyInit ...` cast switch off the same checking the `any`s did. This is an honest workaround for a test regex, not stronger typing. Acceptable if documented as cosmetic. | Read the patched web/src/api/generated/client/client.gen.ts |
| MINOR | web/eslint.config.js:9-37 | The AST-selector rules have common bypasses. Money rule: unary minus, destructured `amountMinor * 2`, computed `m['amountMinor'] * 2`, optional-chain `m?.amountMinor + 1` (ChainExpression). Fetch rule: `window.fetch('/api/..')`, `globalThis.fetch('/api/..')`, a URL held in a variable, `fetch(new Request('/api/..'))`, `fetch(location.origin + '/api/..')`, a relative `fetch('api/expenses')`. The spec's canonical forms are covered. Suggest a follow-up story: a type-aware rule, a branded Money type, and `no-restricted-globals` / `no-restricted-properties` for fetch in src outside src/api. | `npx eslint --no-ignore --stdin --stdin-filename test/fixtures/lint/probe.tsx` fed the probe file (inputs listed below) -> only lines 6, 9, 10, 14 flagged by no-restricted-syntax |
| MINOR | web/src/api/generated/sdk.gen.ts:30-33 (patched) | Behaviour change vs the deleted health.ts: `version: ''` used to be rejected and is now accepted (the contract has no minLength), and buildTime/time must now be ISO timestamps (stricter, matches the contract). No REQ-OPS-021 test covers an empty version. Contract-faithful, but a silent change. | Compare `git show cbcad1f:web/src/api/health.ts` line 22 with the vHealth schema in valibot.gen.ts:181-188 |
| MINOR | business rule inside generated code | The "DOWN means failure" rule now lives only as a string patch in generate.mjs and runs in generated code that is excluded from coverage (vitest.config.ts `src/api/generated/**`). It is tested by generated-health.test.ts and Playwright, but it is hard to find. A hand-written validator or interceptor in clientConfig.ts, or a check in the App queryFn, would be clearer. | Read web/scripts/generate.mjs:62-72 |
| NIT | docs/TRACEABILITY.md | Not regenerated on the branch; `make check` / `make trace` rewrites it (REQ-OPS-053 row). The integrate step will pick it up. | `make check` then `git status` -> ` M docs/TRACEABILITY.md` |
| NIT | web/src/api/health.test.ts:2 | The spec says the REQ-OPS-021 tests "pass unchanged". The import line had to change because health.ts was deleted (the spec requires that). Assertions stay at 11 -> 11. This is acceptable, but the spec wording should say "unchanged except the import". | `git show e337588 -- web/src/api/health.test.ts` -> one-line import change |
| NIT | eslint money rule | `'EGP ' + m.amountMinor` (string concatenation) is flagged as arithmetic. That is a false positive, but restrict-plus-operands already flags it. | Probe line 10 |

## Exploratory
24 cases tried, 13 problems (lint bypasses and the osv issue, grouped in Findings above).
1. Hand edit: appended `// HAND EDIT` to sdk.gen.ts, sed `vGetHealthResponse` -> `vHacked`, created `src/api/generated/stray.ts`; then `npm run generate` -> all reverted, stray file deleted, git clean. OK
2. `npm run generate` twice in a row -> `git diff --exit-code` clean. OK
3. Nested nullable properties (Member.phoneE164, ReviewItem.matchScore `[number,'null']`) -> generated as optional `string or null` and `v.nullish(...)`. OK (shows the allowlist premise is wrong)
4. Whole-word grep for `any`, eval calls, the Function constructor, innerHTML and document.write in src/api/generated -> only English prose "any"; none of the others. OK
5. Credentials: grep in generated -> `Authorization` is only applied when `auth` is configured; clientConfig sets no token. OK (spec: no credentials by itself)
6. Lint probe (stdin, as test/fixtures/lint/probe.tsx), each line verbatim:
   - `export const m1 = -m.amountMinor;` -> not flagged (PROBLEM)
   - `const { amountMinor } = m; export const m2 = amountMinor * 2;` -> not flagged (PROBLEM)
   - `export const m3 = m['amountMinor'] * 2;` -> not flagged (PROBLEM)
   - `export const m4 = m?.amountMinor + 1;` -> not flagged (PROBLEM)
   - `export const m5 = ms.reduce((a, e) => a + e.amountMinor, 0);` -> flagged OK
   - `export const m6 = m.amountMinor < 1;` -> not flagged OK
   - `export const m8 = Math.abs(m.amountMinor);` -> not flagged OK
   - `export const m9 = (m.amountMinor) * 1.1;` -> flagged OK
   - `export const m10 = 'EGP ' + m.amountMinor;` -> flagged (false positive, NIT)
   - `export const f1 = window.fetch('/api/expenses');` -> not flagged (PROBLEM)
   - `export const f2 = globalThis.fetch('/api/expenses');` -> not flagged (PROBLEM)
   - `const u = '/api/expenses'; export const f3 = fetch(u);` -> not flagged (PROBLEM)
   - f5: fetch of a backtick template literal `/api/expenses/1` -> flagged OK
   - `export const f6 = fetch(new Request('/api/expenses'));` -> not flagged (PROBLEM)
   - `export const f7 = fetch(location.origin + '/api/expenses');` -> not flagged (PROBLEM)
   - `export const f8 = fetch('/apix');` -> not flagged OK
   - `export const f9 = fetch('/health');` -> not flagged (by design, outside /api)
   - `export const f10 = fetch('/API/expenses');` -> not flagged (server paths are case-sensitive, OK)
   - `export const f11 = fetch('api/expenses');` -> not flagged (PROBLEM: relative URL that resolves to /api from the root page)
7. `osv-scanner scan source --lockfile web/package-lock.json` -> 3 High js-yaml (PROBLEM, MAJOR)
8. Existing code false positives: `eslint .` inside `npm run check-fast` is clean on all of src/ and e2e/ -> no false positives in existing code. OK
9. `npm run build` -> `dist/assets/index-BsycV3tW.js 319.98 kB, gzip 100.25 kB`; matches the claimed 100.25 KB. I did not rebuild the 95.38 KB base figure.

## Test integrity
- `make check` -> "### test integrity vs main ... assertions: 11 (base) -> 90 (now); net change 79; in deleted files: 0 / TEST_INTEGRITY: OK".
- `git show e337588 --stat`: the only test file touched by the implementation commit is web/src/api/health.test.ts (1 line); its diff is exactly the import `./health` -> `./generated`. Assertions 11 -> 11. No skip, only, or loosened tolerances. Fixtures and the four new test files come from 16562e3 (test-designer).
- vitest.config.ts excludes `src/api/generated/**` from coverage. Excluding generated code is reasonable; note it also hides the patched getHealth rule (see Findings).
- Result: OK.

## Verdict
STATUS: FAIL. Every REQ-OPS-053 clause was observed passing. The one MAJOR is the High js-yaml advisories that the new generator pulls in; they are expected to turn the required PR `gate` (security-fast) red. Fix that, consider the MINOR items, then re-verify (loop 2).
