# STORY-141 review report (combined with verify, low tier)

- Reviewer: qa-verifier with the code-review, ux-a11y and i18n-rtl checklists (fresh context)
- Date: 2026-10-24. Base 7fc1d46, head 8917b3f, loop 1 of 3.
- Evidence and commands: see STORY-141-walking-skeleton-web-version.verify.md.

## Correctness and design
- health.ts: hand-written runtime validation is sound and narrow. It checks `res.ok` before parsing, so 502/504 html never reaches `res.json()`; non-object, status not `UP`, empty or missing version, sha not `^[0-9a-f]{7,40}$`, non-string timestamps and malformed `checks` all reject. A 200 with an html body throws a SyntaxError inside the queryFn, which becomes error state (no pageerror). The `as Record<...>` on checks is justified by the preceding `every()`. Line 26 (invalid checks) has no test (coverage 87.5 percent stmts for this file).
- Query setup: `retry: false`, `refetchOnWindowFocus: false`, `refetchOnReconnect: false` and `networkMode: 'always'` match the spec (no auto-retry, single request). The QueryClient and i18n instance are created once per App via `useState`, so StrictMode double-mount still sends exactly one GET (observed in e2e).
- Offline logic: `fetchHealth` rejects without a network call when `navigator.onLine` is false, and the copy is chosen at render from `navigator.onLine`. Meets the spec, but (NIT) the `online` listener also refetches in the success state, and `isFetching` replaces the version line with the skeleton on any refetch (flicker on reconnect). The `offline` event alone does not re-render.
- VersionLine: placeholder tokens are substituted into the translated template and values are emitted as separate text nodes inside `<bdi dir="ltr">`, so values never go through i18next interpolation or HTML; injection-safe even with `escapeValue: false`.
- Design: small, readable, no dead code. `detectLanguage` uses navigator.language only, as scoped (detection beyond that is STORY-017).

## Test quality
- Good: table-driven failure variants shared between vitest and Playwright; request counting via route scripts; held-open route for the skeleton; zero-pageerror assertion; dark token compared against the resolved CSS variable rather than a hard-coded colour; matrix with axe + screenshot.
- MAJOR (also in verify): src/lint.test.ts runs ESLint with typed linting inside vitest and has no explicit timeout; under coverage + parallel load it exceeds 5000 ms in 3 of 4 runs, so `npm run test:coverage` / `make check` is unreliable.
- NIT: src/tsconfig.test.ts proves the rule through a fixture tsconfig rather than a plant under `src/` + `npm run check-fast`; acceptable as a proxy (the real plant was demonstrated manually: exit 2 naming the file).
- NIT: e2e error tests check `calls.count` right after the error is visible; a delayed auto-retry (for example React Query default 1 s back-off) would show up because the error would not render until retries are exhausted, so the assertion still holds. Fine.

## i18n and RTL
- Keys and copy in en.json and ar.json match the spec table exactly (Arabic strings checked character by character, incl. the shadda in تعذّر).
- `<html lang dir>` set from the i18n instance (`instance.dir(lang)`), toggled without reload; verified in e2e for ar-EG, en-US, fr-FR.
- Version and SHA isolated in `<bdi dir="ltr">`; real-browser Arabic render shows correct order and mirrored parentheses.
- Styles use logical properties (`padding-inline`, `padding-block`, `inline-size`, `block-size`, `min-inline-size`) and `align-items: flex-start`, which follows direction. No physical left/right anywhere.
- Digits: Latin digits for version and SHA (technical identifiers) inside LTR isolation, `tabular-nums`; appropriate.
- MINOR: `i18next/no-literal-string` uses `mode: jsx-text-only`, so literal aria-label / title / alt / placeholder strings pass (demonstrated: exit 0). STORY-017 relies on this rule; widen to attributes before it does.
- NIT: font stack lists Inter before IBM Plex Sans Arabic; Arabic glyphs fall through to Plex only if Inter lacks them (it does), so it works, but neither font is loaded yet.

## Accessibility
- Names: buttons have visible text names (`Retry` / `إعادة المحاولة`, `العربية` / `English`); the error paragraph is `role="alert"`; the skeleton is `role="status" aria-busy="true"`.
- MINOR: the language toggle label is in the other language without a `lang` attribute (WCAG 3.1.2 Language of Parts).
- NIT: the skeleton status region has no text, so nothing is announced while loading (needs a loading key, out of this story key list).
- Focus: `.btn` sets `border: 0` but does not remove the outline, so the default focus ring stays. After Retry the button unmounts (skeleton then content), so focus drops to body; acceptable for this page, worth handling in STORY-017.
- Contrast: light accent #0b5cad on #ffffff text is about 6.4:1; dark #0a0a0a on #7db7f0 is about 10:1; body text #1a1a1a / #f1f2f4 on its background is high. axe reports 0 serious/critical in all 4 renders.
- Target size: `.btn` min 48x48 px (meets 2.5.5 AAA and 2.5.8).

## Security
- No dangerouslySetInnerHTML, innerHTML, eval, storage or cookies (grep: no hits). Proxy targets only 127.0.0.1:8080 (prefix match NIT). All package.json versions exact; lockfile committed. osv-scanner on the lockfile: no issues; npm audit: 0.

## Findings
| Sev | Where | What |
|---|---|---|
| MAJOR | web/src/lint.test.ts:6 | No timeout on the typed-ESLint test; test:coverage (the `make check` web step) fails 3 of 4 runs with "Test timed out in 5000ms". |
| MINOR | story size | 1059 hand-written added lines vs the about-395 estimate and the DoR cap of 400. |
| MINOR | web/eslint.config.js:28 | `jsx-text-only` lets literal attribute strings through the i18n gate. |
| MINOR | web/src/App.tsx:42-50 | Toggle label lacks `lang` for the other-language text. |
| NIT | web/src/App.tsx:61-71 | Reconnect refetch also in success state + skeleton flicker on any refetch; `offline` event does not update copy. |
| NIT | web/src/App.tsx:71 | Empty status region while loading. |
| NIT | web/vite.config.ts:9 | `/health` proxy key is a prefix match (`/healthXYZ` forwarded). |
| NIT | web/package.json:36 | eslint 9.39.5 deprecated. |

## Verdict
CHANGES_REQUESTED: one MAJOR (unreliable coverage run) blocks; MINORs should be fixed or accepted explicitly.

---

## Loop 2 (2026-10-24, head 36cca85)
Scope: loop-1 findings plus a regression pass over 825ff32 (fixes) and 36cca85 (json-summary coverage reporter). Evidence in the verify report, Loop 2 section.

### Review of the fixes
- vitest.config.ts: `testTimeout: 30_000` fixes the coverage flake (5/5 green). It is global; a per-test timeout on src/lint.test.ts would keep fast failure for the other tests (NIT). `reporter: ['text', 'json-summary']` produces the summary that the coverage gate reads; `make check` script GREEN.
- eslint.config.js: `no-restricted-syntax` on `JSXAttribute[name.name=/^(aria-label|aria-description|aria-placeholder|title|alt|placeholder)$/] > Literal` correctly flags the plain-string forms and has no false positives on className / type / dir / role / lang / src / aria-describedby. Gap (NIT): braced literals `aria-label={"x"}` and plain template literals are not covered; extend the selector via JSXExpressionContainer before STORY-017 leans on it.
- App.tsx toggle: `<span lang={target}>` inside the button gives the label its own language (WCAG 3.1.2) while the button accessible name stays `العربية` / `English`; verified in a real browser.
- App.tsx online handler: `if (isError) void refetch();` with `isError` in the effect deps. Correct: the listener is re-bound when the state changes, so a reconnect from the success state no longer refetches (observed 1 -> 1) and from the error state refetches exactly once (observed 1 -> 2). Offline Retry still lands in `isError` (fetchHealth rejects), so the spec path holds.
- Correctness, i18n/RTL and a11y conclusions from loop 1 otherwise unchanged; no regression in the 18 mocked Playwright specs or the 4 screenshot baselines.

### Findings after loop 2
| Sev | Where | What |
|---|---|---|
| NIT | web/eslint.config.js:33 | Attribute rule misses braced / template-literal strings. |
| NIT | web/vitest.config.ts:10 | Global 30 s timeout rather than per-test. |
| NIT | web/src/App.tsx:72 | Empty status region while loading (carried over). |
| NIT | web/vite.config.ts:9 | `/health` proxy prefix match (carried over). |
| NIT | web/package.json:36 | eslint 9.39.5 deprecated (carried over). |
| NOTE | story size | 1059 hand-written lines vs about 395; orchestrator decision, not counted. |

### Verdict (loop 2)
APPROVE: no BLOCKER/MAJOR/MINOR open; NITs may be taken now or in STORY-017.
