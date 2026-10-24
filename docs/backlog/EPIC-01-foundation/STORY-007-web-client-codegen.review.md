# Review report: STORY-007 (TypeScript API client generated from the contract)

- Reviewer: qa-verifier acting as the combined verify + code-review agent (low risk tier), with a light security and bundle check
- Date: 2026-10-25
- Base: main cbcad1f; Head: e337588 (story/STORY-007-web-client-codegen); loop 1 of 3
- Companion: STORY-007-web-client-codegen.verify.md (commands and outputs)

## Summary
The change does what REQ-OPS-053 asks. The client, types and valibot validators are generated from contracts/openapi.yaml, the output is deterministic, and the `clean: true` output removes stray and hand-edited files. getHealth moved to the generated client and the REQ-OPS-021 unit and Playwright suites stay green. The design is acceptable for a low-tier tooling story. One merge blocker: the new dev dependency chain brings in a js-yaml with 3 High advisories, which the required osv-scanner job will reject.

## Checklist
| Area | Verdict | Notes |
|---|---|---|
| Correctness vs spec | OK | All Given/When/Then and both unhappy paths observed (verify report, Criteria). |
| Generator choice (@hey-api/openapi-ts 0.99.0, valibot 1.5.0, yaml 2.9.1, exact pins) | OK, with a dependency issue | It is maintained, fetch-based and tree-shakeable, and valibot keeps the runtime validation the spec requires at a small size. Exact pins match the repo convention. Concern: 0.99.0's transitive js-yaml 4.2.0 (see Security). yaml 2.9.1 is used only by the pre-check; the generator already parses YAML, so a second YAML parser is one more dependency for a check that is weak anyway. |
| Post-patching generated output (generate.mjs) | Acceptable but fragile; partly cosmetic | Good: deterministic, it fails loudly when a pattern disappears, and it is documented in DECISIONS.md. Weak: (1) "exactly once" is not enforced (`includes` plus a first-match `replace`); (2) the any-removal patches swap `any` for `as unknown as` / `as BodyInit` casts, which is equally unchecked. It satisfies the no-any regex rather than adding type safety; (3) a business rule (status DOWN rejects) is injected as a string patch into generated code that is excluded from coverage. It does not hide a generator defect, but it is the least discoverable place for that rule. Prefer a hand-written wrapper or a validator in clientConfig.ts or the App queryFn, and keep patches to genuine generator gaps. |
| NextCursor allowlist pre-check | Not sound as a detector; harmless today | The generator expresses `type: [string,'null']` correctly both top-level (NextCursor) and in about 40 nested properties. So the pre-check rejects supported top-level constructs (false positives on future schemas) and ignores everything nested and everything the generator actually emits. It fits the test's example rather than the spec's intent ("cannot express -> fail by name instead of any"). It masks no real issue in the current contract. Recommend: replace or augment it with a post-generation scan that names any schema whose generated type is `any`, and correct the spec's example (product-analyst). |
| Hand-written src/api/clientConfig.ts | OK | Small and commented. It uses same-origin `baseUrl` and resolves `globalThis.fetch` per call, so test and service-worker stubs apply. It rebuilds the call from the Request (method, headers, body as ArrayBuffer, signal, credentials). It drops `mode`, `cache`, `redirect`, `keepalive` and `referrer`; harmless now, worth a comment when SSE/streaming (streamEvents) or uploads are wired. It attaches no credentials (spec: auth comes in STORY-018). |
| Committed generated files | OK | It matches .claude/rules/generated-code.md (commit when the build needs it offline). CI drift is caught by generate + `git diff --exit-code`, and protect-files.sh blocks web/src/api/generated. Generated files are excluded from eslint, prettier and coverage; that is reasonable. |
| ESLint rules | OK for canonical forms; bypassable | Both rules fire on the spec's forms, with no false positives in existing src/ or e2e/. Easy bypasses: `window.fetch` / `globalThis.fetch`, a URL held in a variable, relative `api/...`, and money via destructuring, optional chaining, a computed key or unary minus. Suggest a follow-up (type-aware rule or branded Money type, plus `no-restricted-properties` for `window.fetch` / `globalThis.fetch` outside src/api). |
| Test quality / integrity | OK | Test-designer tests are meaningful (they cover the contract, run generate in a sandbox, and lint fixtures). The implementation commit changed only the health.test.ts import (forced by the required deletion). TEST_INTEGRITY OK. The no-any regex misses forms like a second generic argument `any` inside angle brackets; I found no `any` by whole-word grep. |
| Invariants | OK | Money is `amountMinor: number` (integer minor units; no float arithmetic introduced). No logging of bodies. No migrations. Generated code changes only via the generator. No secrets. |
| Docs | OK, small gaps | DECISIONS.md line added; the generated-code rule now points at `npm run generate`. The DECISIONS line overstates the patch guarantee ("exactly once"). TRACEABILITY.md is not refreshed on the branch (make trace does it at integrate). The web/CLAUDE.md note on committed generated output was not checked/updated (rule says "document which in the module CLAUDE.md"). |
| Size | Over guideline | The story caps size at 400 changed lines; the hand-written part (eslint config, generate.mjs, clientConfig, package.json) is about 170 lines, which is fine. The rest is generated and lockfile. |

## Security (light)
- osv-scanner `scan source --lockfile web/package-lock.json`: 3 High in js-yaml 4.2.0 (dev, via @hey-api/json-schema-ref-parser); `npm audit`: 4 high in the same chain. These are new in this branch (base resolves js-yaml 4.3.2 only). Exploitability is low (CPU DoS when parsing our own trusted contract at build time), but `security-fast` is a required need of the PR `gate`. MAJOR until fixed with an npm `overrides` pin of js-yaml (4.3.2 or later) or a justified, dated osv ignore.
- Generated code: no `any`; no eval calls, Function constructor, innerHTML or document.write. `Authorization` is set only when an `auth` callback is configured. Valibot response validation is on for every operation; the validator output is discarded (raw data returned), so unknown extra keys reach callers unstripped. That is acceptable, but note it for later stories that render data.
- No credentials or tokens in clientConfig; same-origin base URL; no new runtime network destinations.

## Bundle
- `npm run build` -> `dist/assets/index-BsycV3tW.js 319.98 kB, gzip 100.25 kB`, which confirms the reported 100.25 KB. Base 95.38 KB not rebuilt by me. +4.9 KB gzip for valibot plus the fetch runtime is proportionate. The barrel index.ts re-exports all 80 operations, but Vite tree-shakes the unused ones (only getHealth is used and the delta is small).

## Findings
- [MAJOR] web/package-lock.json -- js-yaml 4.2.0 (3 High GHSA) via @hey-api/openapi-ts 0.99.0; the required PR gate's security-fast osv job is expected to fail -- `osv-scanner scan source --lockfile web/package-lock.json`
- [MINOR] web/scripts/generate.mjs:13-22 -- the NextCursor allowlist pre-check is a shape filter, not a cannot-express detector (false positives on top-level nullables, blind to nested ones and to emitted `any`) -- add a top-level schema whose type is the array [string, 'null'] and run `npm run generate`; it fails although the generator supports that shape
- [MINOR] web/scripts/generate.mjs:43-50 -- "must match exactly once" is not enforced (includes plus first-match replace) -- read the code
- [MINOR] web/scripts/generate.mjs:53-61 -- any-removal patches are replaced by `as unknown as` casts (cosmetic) -- read the patched client.gen.ts
- [MINOR] web/scripts/generate.mjs:62-72 -- the DOWN rule is a string patch inside coverage-excluded generated code; move it to hand-written code
- [MINOR] web/eslint.config.js:9-37 -- money and fetch rules are bypassable (window/globalThis.fetch, variables, relative URL, destructuring, optional chain) -- see the verify report's probe list
- [MINOR] getHealth -- an empty `version` is now accepted (old code rejected it; the contract has no minLength) -- compare the cbcad1f health.ts line 22 with valibot vHealth
- [NIT] docs/TRACEABILITY.md not refreshed on the branch; web/CLAUDE.md not updated about committed generated output; the spec wording "unchanged" vs the forced import edit.

## Verdict
CHANGES_REQUESTED: the MAJOR (osv High in the required gate) must be resolved; MINORs are recommended, not blocking.
