# STORY-141 verify report

- Verifier: qa-verifier (fresh context, combined verify + review, low tier)
- Date: 2026-10-24
- Base: 7fc1d46e289190685fd23cc62ed1c95b1c69fd38 (STORY-143 branch; 627cce7b0067b1f703f9789a028b105b46010fe8 also given)
- Head: 8917b3f1a00ee85a08e98029cdf2485986325747 (story/STORY-141-walking-skeleton-web-version, commits 6018ca0..8917b3f)
- Loop: 1 of 3. Checked out detached at 8917b3f in an isolated worktree; main checkout untouched.

## Environment
- Node v24.15.0, Windows 11, Git Bash, Docker Desktop. `npm ci` -> 373 packages in 6 s (warning: eslint@9.39.5 deprecated).
- `npm run check-fast` -> exit 0 (tsc, eslint, prettier clean, vitest 5 files / 31 tests passed) in 10 s.
- `make check-fast MODULE=web`: the make binary was refused by the agent sandbox, so ran the exact script make dispatches to: `bash scripts/make/check-fast.sh web < /dev/null` -> exit 0, "OK web: npm run check-fast", "check-fast GREEN [web] in 8s".
- `npm run test:coverage` (the web step of `make check`, scripts/make/check.sh:17) x4 -> FAIL, PASS (96.47 stmts / 92 branch / 100 funcs / 98.64 lines), FAIL, FAIL. Every failure: src/lint.test.ts "Test timed out in 5000ms" (5126-5290 ms).
- STORY-143 stack: `GIT_SHA=8917b3f docker compose -p s141verify --env-file .env.example -f infra/docker-compose.yml -f infra/docker-compose.dev.yml up -d --build --wait` -> exit 0, postgres + backend Healthy; `curl 127.0.0.1:8080/health` -> 200 status UP, version 0.1.0, sha 8917b3f. Torn down with `down -v` (volume removed).
- Playwright 1.64.1 chromium: `npx playwright test` with the stack up -> 19 passed incl. the real-backend spec; `npm run e2e -- --repeat-each=3 --workers=4` -> 57 passed, no flakes.
- `osv-scanner --lockfile=package-lock.json` (the `scan source` form was refused by the sandbox) -> 396 packages, "No issues found"; `npm audit` -> 0 vulnerabilities.
- `bash scripts/quality/test-integrity-check.sh 7fc1d46` -> exit 0, assertions 0 -> 185, TEST_INTEGRITY: OK.

## Criteria table
| REQ / clause | Result | Evidence |
|---|---|---|
| OPS-021 i18n keys and copy exactly as the spec table | PASS | en.json + ar.json: 6 keys, copy identical to the spec (incl. the shadda in the ar error string) |
| OPS-021 one GET /health, `Backend 0.1.0 (1a2b3c4)`, one language only, 7-char SHA, bdi dir=ltr | PASS | e2e "exactly one GET /health" ok (count 1, bdi texts 0.1.0 / 1a2b3c4); ar-EG spec asserts no `Backend`; vitest App + VersionLine ok |
| OPS-021 real backend through the Vite proxy | PASS | e2e real-backend spec ok vs the compose stack; real-browser screenshot `--lang=ar-EG` shows `الخادم 0.1.0 (8917b3f)` in correct RTL order |
| OPS-021 navigator.language ar* -> lang=ar dir=rtl; en-US / fr-FR -> en ltr | PASS | e2e ar-EG, fr-FR, en-US tests ok; vitest it.each ar-EG / en-US / fr-FR ok |
| OPS-021 toggle without reload, name flips, no persistence across reload | PASS | e2e toggle test ok (request count stays 1, reload -> lang=en); grep for localStorage / sessionStorage / document.cookie / indexedDB in web/src: no hits |
| OPS-021 skeleton role=status aria-busy=true, no raw key / undefined | PASS | e2e skeleton test (route held open) ok; vitest skeleton test ok |
| OPS-021 error within 1 s, skeleton gone, no auto-retry for 503 Problem, 502/504 html, network abort | PASS | 4 e2e tests ok with `toBeVisible({ timeout: 1000 })`, request count 1, zero pageerror |
| OPS-021 Retry sends exactly one request, skeleton in flight, 200 renders | PASS | e2e count 1 -> 2 and version renders; vitest asserts skeleton during Retry and fetch called twice |
| OPS-021 status DOWN -> error | PASS | e2e "200 status DOWN" ok; real backend with postgres stopped answered 503 problem+json and the real page showed error + Retry (en and ar-EG dark screenshots) |
| OPS-021 drift (missing version, sha XYZ) -> error, 0 pageerror | PASS | 2 e2e drift tests ok, `errors` empty; health.test.ts also rejects a 5-char sha and a non-object body |
| OPS-021 injection rendered as text, no img | PASS | VersionLine.test.tsx injection test ok; no dangerouslySetInnerHTML anywhere in web/src |
| OPS-021 offline copy after Retry, exactly one refetch on online, version renders | PASS | e2e offline test ok (delta 1); vitest offline test ok |
| OPS-021 dark background token differs from light | PASS | e2e dark-token test ok (body bg equals resolved --background in both schemes, dark != light) |
| OPS-021 375x812 matrix x4: axe 0 serious/critical + screenshot baselines | PASS | 4 matrix tests ok, 12/12 with repeat-each=3 |
| OPS-021 `npm run e2e` runs the specs, webServer starts Vite, real-backend spec asserts version + short HEAD sha | PASS | `npm run e2e -- --repeat-each=3` 57 passed; playwright.config webServer `npm run dev` |
| OPS-025 check-fast = tsc strict + noUncheckedIndexedAccess, eslint strict-type-checked + jsx-a11y + react-hooks + i18next error, prettier, vitest; exit 0 | PASS | `npm run check-fast` exit 0; tsconfig.json and eslint.config.js read |
| OPS-025 warm `make check-fast MODULE=web` < 3 min | PASS | dispatch script GREEN in 8 s (make binary itself not callable from this sandbox) |
| OPS-025 `make check` web part (check-fast + test:coverage) green | FAIL | `npm run test:coverage` failed 3 of 4 runs: src/lint.test.ts timed out at 5000 ms |
| OPS-025 lint fixture -> exactly one i18next/no-literal-string on the h1 line | PASS | src/lint.test.ts passes; manual plant src/ZzBad.tsx with `<h1>Hello</h1>` -> `2:14 error ... i18next/no-literal-string`, exit 1 (plant removed) |
| OPS-025 planted `const first: string = ["a"][0];` in src -> check-fast fails naming the file | PASS | plant src/zz-planted.ts in the throwaway worktree, `npm run check-fast` -> exit 2, `src/zz-planted.ts(1,14): error TS2322` (string or undefined not assignable to string); plant removed, tree clean |

REQ tags: `describe('REQ-OPS-021 ...')` in App.test.tsx, health.test.ts, VersionLine.test.tsx; `describe('REQ-OPS-025 ...')` in lint.test.ts and tsconfig.test.ts; Playwright titles carry `@REQ-OPS-021`. All ran (vitest verbose list 31 tests, Playwright 19).

## Findings table
| Sev | Where | What | Reproduce |
|---|---|---|---|
| MAJOR | web/src/lint.test.ts:6, used by scripts/make/check.sh:17 | `npm run test:coverage` is red most runs: the ESLint Node-API test (typed linting via projectService) takes 2.3 s in plain vitest but 5.1-5.3 s under v8 coverage with all files in parallel, over the default 5000 ms testTimeout. `make check` and the DoD therefore fail about 3 of 4 runs; claim 2 is false. Fix: an explicit timeout on this test or `testTimeout` in vitest.config.ts (time only, assertions untouched). | `cd web` then run `npm run test:coverage` 4 times -> 3 x "Test timed out in 5000ms" in src/lint.test.ts |
| MINOR | story size | 1059 hand-written added lines excluding package-lock.json and the PNGs (~960 even if index.html, main.tsx, vite.config.ts, tsconfig.json and package.json count as Vite template) vs the about-395 estimate and the DoR cap of 400; claim 7 is false. Tests alone are ~640 lines. | numstat of 7fc1d46..8917b3f minus lockfile and png |
| MINOR | web/eslint.config.js:28 | `i18next/no-literal-string` runs in `mode: jsx-text-only`, so hard-coded user-visible attribute strings (aria-label, title, alt, placeholder) pass the gate; STORY-017 (REQ-UI-002) relies on this rule. | plant `<button type="button" aria-label="Close" title="Close dialog" />` in src/ZzAttr.tsx, `npx eslint src/ZzAttr.tsx` -> exit 0 |
| MINOR | web/src/App.tsx:42-50 | The toggle label is in the other language but has no `lang` attribute (`lang="ar"` on the Arabic label, `lang="en"` on English), so screen readers voice it with the page language (WCAG 3.1.2). | inspect the toggle in the en page: no lang attribute |
| NIT | web/src/App.tsx:61-71 | The `online` listener refetches in every state incl. success, and `isFetching` swaps the version line for the skeleton on any refetch, so each reconnect flickers and sends an extra GET. The `offline` event does not re-render, so an error screen keeps the generic copy until Retry (the spec only requires the offline copy after Retry). | code read |
| NIT | web/src/App.tsx:71 | Skeleton `role=status` has no text or name, so assistive tech gets an empty live region; needs a loading key outside this story key list (STORY-017). | code read |
| NIT | web/vite.config.ts:9 | Proxy key `/health` is a prefix match: `/healthXYZ` is forwarded too. Target is still only 127.0.0.1:8080; `^/health$` would be exact. | `curl 127.0.0.1:5173/healthXYZ` -> 404 application/json from the backend |
| NIT | web/package.json:36 | eslint 9.39.5 is flagged deprecated on install. | `npm ci` warning |

## Exploratory (inputs verbatim)
1. Real backend, Playwright CLI `npx playwright screenshot --lang=ar-EG --wait-for-selector=.version-line http://127.0.0.1:5173/` -> `الخادم 0.1.0 (8917b3f)`, correct RTL order and mirrored parentheses. OK.
2. Real backend with postgres stopped (`docker compose ... stop postgres`): `/health` through the proxy -> `503 application/problem+json` with detail "Database unavailable"; real page with `--lang=en-US` and with `--lang=ar-EG --color-scheme=dark` reached `[role=alert]` showing the error copy + Retry (Arabic: تعذّر الوصول إلى الخادم. / إعادة المحاولة / English). OK.
3. `curl 127.0.0.1:5173/healthXYZ` -> 404 application/json (prefix proxy, NIT).
4. `curl --path-as-is 127.0.0.1:5173/health/../actuator/env` -> 404. OK, no actuator exposure.
5. `curl -H "Host: evil.example" 127.0.0.1:5173/health` -> 403 (Vite host check). OK.
6. `curl -H "Accept-Language: ar" 127.0.0.1:5173/` -> static `<html lang="en">`; language comes from navigator.language in JS, as specified. OK.
7. Plant `export const first: string = ["a"][0];` in src -> check-fast exit 2 naming the file. OK.
8. Plant a component returning `<h1>Hello</h1>` in src -> eslint i18next/no-literal-string error. OK.
9. Plant `aria-label="Close" title="Close dialog"` -> eslint exit 0 (MINOR).
10. `npx vitest run src/lint.test.ts` x3 and once with `--coverage` alone -> pass; full `npm run test:coverage` x4 -> 3 fail (MAJOR; load-dependent).
11. `npm run e2e -- --repeat-each=3 --workers=4` -> 57/57; the within-1 s and offline tests are stable.
12. Grep `dangerouslySetInnerHTML|localStorage|sessionStorage|document.cookie|indexedDB|innerHTML|eval(` over web/src, web/e2e, configs, index.html -> no hits. OK.
13. package.json specifiers checked for `^ ~ > < * latest` -> none; all exact. OK.
14. Code-level: a version string equal to the internal placeholder `@@sha@@` cannot pull in the SHA, since values are rendered as separate text nodes after splitting the translated template. OK.
- NOT RUN: a scripted browser pass (uppercase SHA, 41-char SHA, Arabic-Indic digits and U+202E in version, null body, double-click Retry, focus after Retry, measured target sizes). The agent sandbox refused to write the scratch Playwright script. Partly covered by code reading: regex `^[0-9a-f]{7,40}$` rejects uppercase and 41 chars; `.btn` is min 48x48 px.

14 cases tried, 4 problems (1 MAJOR, 1 MINOR, 2 NIT).

## Test integrity
- `bash scripts/quality/test-integrity-check.sh 7fc1d46` -> exit 0, assertions 0 -> 185, TEST_INTEGRITY: OK.
- 8917b3f (implementation) touches only App.tsx, health.ts, VersionLine.tsx, styles.css and the 4 screenshot baselines; no test file.
- 8606db1 (test-designer) vs 7f2a52e: `findByText(X, { exact: false })` became `byFullText(X)` (textContent must equal X and the match is the innermost element), which is stricter. The offline vitest script changed from a fixed [503, netFail, 200] sequence to [503, connectivity-dependent step]; its assertions (offline copy shown, error copy absent, exactly one call after `online`, version renders) are unchanged; it only stops assuming Retry hits the network while offline, which the spec does not require. No weakening.
- No skip / only / todo added.

## Verdict
STATUS: FAIL (one MAJOR: test:coverage, the `make check` web step, is red in 3 of 4 runs).

---

## Loop 2 (2026-10-24, loop 2 of 3)
- Base 7fc1d46, head 36cca85 (d40c166 reports, 825ff32 implementer fixes, 36cca85 orchestrator json-summary coverage reporter). Isolated worktree detached at 36cca85; scope = loop-1 findings + regression pass.

### Environment
- `npm ci` -> 373 packages. `npm run test:coverage` x5 -> 5/5 exit 0, 31/31 tests each; All files 96.51 stmts / 90.38 branch / 100 funcs / 98.64 lines; `web/coverage/coverage-summary.json` produced.
- `BASE=7fc1d46 bash scripts/make/check.sh < /dev/null` (the script behind `make check`) -> exit 0, `check GREEN`: check-fast GREEN [sdlc backend web] in 70s, backend gate probes, Testcontainers integration, ArchUnit, `OK web unit coverage`, contract diff, `OK coverage gates`, traceability, `OK test integrity vs 7fc1d46`. (The regenerated docs/TRACEABILITY.md was restored in the throwaway worktree.)
- `npx playwright test --grep-invert "real backend"` -> 18 passed, incl. the 4 matrix renders against the unchanged baselines (no PNG touched since 8917b3f). The real-backend spec was not re-run: no change touches the fetch/proxy path.

### Loop-1 findings re-checked
| Loop-1 finding | Result | Evidence |
|---|---|---|
| MAJOR lint.test.ts timeout under coverage | FIXED | `testTimeout: 30_000` in vitest.config.ts; coverage 5/5 green; `make check` script GREEN |
| MINOR attribute literals pass the i18n gate | FIXED (NIT remains) | plant with `aria-label="Close" title="Close dialog"`, `alt="Logo"`, `placeholder="Search"` -> 4 x `no-restricted-syntax` errors at 4:40, 4:54, 5:28, 6:26, exit 1; no false positives on `className`, `type`, `dir`, `role`, `lang`, `src`, `aria-describedby` in the same file. Braced forms `aria-label={"Braced"}` and template literal `` title={`Tpl`} `` are NOT flagged (NIT below). Fixture test `src/lint.test.ts` still passes (exactly one i18next/no-literal-string on the h1 line). |
| MINOR toggle lacks lang | FIXED | real browser: toggle innerHTML `<span lang="ar">العربية</span>`; `getByRole('button', { name: 'العربية' })` count 1 (name unchanged); e2e ar-EG test still finds the `English` button |
| NIT online refetch in success state | FIXED | real browser (Vite + Playwright, `/health` mocked): success state then `setOffline(true)` / `setOffline(false)` -> requests 1 -> 1, version stays; error state (first 503) then offline/online -> 1 -> 2 and `Backend 0.1.0 (1a2b3c4)` renders; committed e2e offline test (Retry while offline, then online -> exactly one) passes |
| MINOR size (1059 vs about 395) | NOTED | orchestrator decision; not counted against STATUS |
| NIT empty status region, proxy prefix match, eslint deprecated | OPEN (NIT) | unchanged, not blocking |

### New / remaining findings
| Sev | Where | What | Reproduce |
|---|---|---|---|
| NIT | web/eslint.config.js:33 | Selector `JSXAttribute[...] > Literal` misses `aria-label={"x"}` (JSXExpressionContainer > Literal) and `` title={`x`} `` (TemplateLiteral). Adding `JSXAttribute[...] > JSXExpressionContainer > :matches(Literal, TemplateLiteral[expressions.length=0])` would close it without flagging `t('key')`. | plant `<button type="button" aria-label={"Braced"} />` -> no error |
| NIT | web/vitest.config.ts:10 | `testTimeout: 30_000` is global; a hung test now takes 30 s to fail. A per-test timeout on lint.test.ts would be narrower. Time-only change, no assertion loosened. | config read |

### Test integrity (loop 2)
- 825ff32 changes only eslint.config.js, App.tsx, vitest.config.ts; 36cca85 only vitest.config.ts. No `*.test.*`, `web/e2e`, `web/test` or PNG file changed between 8917b3f and 36cca85 (name-only listing empty). The test-integrity step inside `check.sh` -> OK.
- The global timeout raise is a runner-config change, explained in the commit and in a comment; assertions are unchanged.

### Exploratory (loop 2 inputs)
1. `aria-label="Close"` -> flagged. 2. `title="Close dialog"` -> flagged. 3. `alt="Logo"` -> flagged. 4. `placeholder="Search"` -> flagged. 5. `className="x" dir="ltr" role="note" lang="ar" type="button" src="a.png" aria-describedby="h"` -> not flagged (correct). 6. `aria-label={"Braced"}` -> not flagged (NIT). 7. `` title={`Tpl`} `` -> not flagged (NIT). 8. success state + offline/online -> 0 requests. 9. error state + offline/online -> exactly 1 request, version renders. 10. toggle markup and accessible name in en page -> `lang="ar"`, name unchanged. 11. coverage x5 -> green. 
- 11 cases, 1 problem (NIT).

### Loop 2 verdict
All criteria PASS (REQ-OPS-021 regression green; REQ-OPS-025 now including `make check` web part), TEST_INTEGRITY OK, no open BLOCKER/MAJOR.

STATUS: PASS
