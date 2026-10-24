---
id: STORY-141
epic: EPIC-00
title: "Walking skeleton (web): version page with i18n/RTL, states, web fast gate"
phase: 0
status: DONE
size: M
modules: [web]
depends_on: [STORY-143]
reqs: [REQ-OPS-021, REQ-OPS-025]
implements: ["PROMPT.md S15 item 7", "PROMPT.md §1 (bilingual, RTL)", "PROMPT.md §2 (web stack)", "PROMPT.md §11 (design language, states)", "PROMPT.md S8 (make check-fast, make e2e matrix)", "PROMPT.md S9 (web gates)"]
risk_tier: low
owner_agent: web-engineer
---

# STORY-141 — Walking skeleton (web): version page with i18n/RTL, states, web fast gate

## Goal
The web half of the S15 item 7 walking skeleton: a gated React/Vite module whose root page shows the backend version from `GET /health` in Arabic (RTL) or English with every UX state, so the full stack (STORY-142) completes **browser → Caddy → (web bundle | backend → Postgres)**. During this story the page runs against the Vite dev server, whose proxy forwards `/health` to the STORY-143 backend on `http://127.0.0.1:8080`.

## Requirements and acceptance criteria
### REQ-OPS-021 — Web page renders the backend version
i18n resources (`web/src/i18n/en.json`, `web/src/i18n/ar.json`) contain exactly these keys and copy for this story:

| key | en | ar |
|---|---|---|
| `health.version` | `Backend {{version}} ({{sha}})` | `الخادم {{version}} ({{sha}})` |
| `health.error` | `Can't reach the server.` | `تعذّر الوصول إلى الخادم.` |
| `health.offline` | `You're offline. Check your connection.` | `أنت غير متصل بالإنترنت. تحقق من الاتصال.` |
| `common.retry` | `Retry` | `إعادة المحاولة` |
| `common.language.en` | `English` | `English` |
| `common.language.ar` | `العربية` | `العربية` |

**Given** the backend returns `200` with `version: "0.1.0"` and `gitSha: "1a2b3c4d5e6f"` (Playwright route mock) or the real STORY-143 backend (one test, through the Vite proxy) **When** the root page `/` loads **Then** exactly one `GET /health` is sent and the page shows `Backend 0.1.0 (1a2b3c4)` in English or `الخادم 0.1.0 (1a2b3c4)` in Arabic: only the current language's string is rendered (never both), the SHA shows its first 7 characters, and the version and SHA are each wrapped in `<bdi dir="ltr">` so they never reorder in RTL.
**Given** `navigator.language` starts with `ar` (Playwright `locale: 'ar-EG'`) **When** the page loads **Then** `<html lang="ar" dir="rtl">` and Arabic copy; **given** any other language (`en-US`, `fr-FR`) **Then** `<html lang="en" dir="ltr">` and English copy.
**Given** the page in English **Then** a toggle button with accessible name `العربية` is present; **When** it is activated **Then** without reload the copy switches to Arabic, `<html>` becomes `lang="ar" dir="rtl"`, and the button's accessible name becomes `English` (activating it switches back). The choice is not persisted in this story (a reload returns to the `navigator.language` default); persistence, More → Language and detection beyond `navigator.language` belong to STORY-017.
**Given** the `/health` request is in flight (route held open) **Then** a skeleton element with `role="status"` and `aria-busy="true"` is shown in place of the version line (no blank area, no raw key, no `undefined`).
Unhappy path (server errors): **Given** `/health` answers `503 application/problem+json` (the STORY-143 Problem body), **or** `502`/`504` with a non-JSON `text/html` body (Caddy upstream error), **or** the request fails at network level (`route.abort('failed')`) **When** the page loads **Then** within 1 s of the failed response the `health.error` copy and a `common.retry` button are visible, the skeleton is gone, and no automatic retry happens (the HTTP client is configured with `retry: false`; exactly one request observed). **When** Retry is activated **Then** exactly one new `GET /health` is sent, the skeleton shows while it is in flight, and on `200` the version line renders.
Unhappy path (backend reports DOWN): **Given** `/health` answers `200` with `status: "DOWN"` **Then** the error state (`health.error` + Retry) is shown.
Unhappy path (contract drift): **Given** `/health` answers `200` with a body missing `version`, or with `gitSha: "XYZ"` (fails `^[0-9a-f]{7,40}$`) **Then** the runtime schema validation in `getHealth()` rejects it, the error state (`health.error` + Retry) is shown, and Playwright records zero `pageerror` events (no uncaught exception).
Unhappy path (injection): **Given** `/health` answers `200` with `version: "<img src=x onerror=alert(1)>"` (Vitest, schema validation bypassed for the render component) **Then** the string is rendered as literal text and no `img` element is created.
Unhappy path (offline): **Given** the page is in the error state (first `/health` routed to `503`) **When** Playwright calls `context.setOffline(true)` and Retry is activated **Then** the `health.offline` copy and Retry are shown instead of `health.error`; **when** `context.setOffline(false)` is called (the browser fires `online`) **Then** exactly one new `GET /health` is sent automatically and, on `200`, the version line renders.
**Given** Playwright `colorScheme: 'dark'` **Then** the page background's computed `background-color` equals the dark-theme background token value defined in the stylesheet and differs from the light-theme value (`colorScheme: 'light'`).
**Given** the e2e matrix LTR/RTL × light/dark at viewport `375×812` (4 combinations) **When** the success state is rendered in each **Then** axe-core reports zero `serious` or `critical` violations in all 4 renders, and each render matches its committed screenshot baseline (`toHaveScreenshot`, `maxDiffPixelRatio: 0.01`). The `1280×800` desktop renders belong to STORY-017 (REQ-UI-004), which owns the full matrix.
**Given** `cd web && npm run e2e` **Then** it runs the REQ-OPS-021 Playwright specs; Playwright `webServer` starts the Vite dev server; the real-backend spec requires the STORY-143 dev-overlay command to be running first and asserts `Backend <backend version> (<git rev-parse HEAD | cut -c1-7>)`.

Client code: `getHealth()` is **hand-written** in `web/src/api/health.ts` with runtime schema validation of the `Health` shape; STORY-007 replaces it with the generated client's `getHealth()` without changing this REQ's behaviour (its tests keep passing).

### REQ-OPS-025 — Web fast gate is green and enforces i18n
**Given** the `web/` module **When** `npm run check-fast` runs **Then** it executes, and fails on any violation of: `tsc --noEmit` (`strict: true`, `noUncheckedIndexedAccess: true`), ESLint with `typescript-eslint` `strict-type-checked`, `jsx-a11y`, `react-hooks` and `eslint-plugin-i18next` rule `i18next/no-literal-string` enabled as an error for JSX, `prettier --check`, and `vitest run` — and exits 0.
**Given** a warm cache (`node_modules` installed, second run) **When** `make check-fast MODULE=web` runs at the repo root **Then** it dispatches to `npm run check-fast` and exits 0 in < 3 min; **when** `make check` runs **Then** the web part (`check-fast`, `npm run test:coverage`) is green.
Unhappy path (i18n, asserted by a test, not by a manual plant): **Given** the fixture `web/test/fixtures/lint/hardcoded.tsx` containing a component that returns `<h1>Hello</h1>` (the fixture directory is excluded from the normal `eslint .` run) **When** a Vitest test lints that file with the project's ESLint configuration through ESLint's Node API **Then** the result contains exactly one message with that ruleId (`i18next/no-literal-string`; other rules may report), on the line of `<h1>Hello</h1>`.
Unhappy path: **Given** a planted `const first: string = ["a"][0];` in `src/` **When** `npm run check-fast` runs **Then** `tsc` fails (type `string | undefined` under `noUncheckedIndexedAccess`) and the command exits non-zero naming the file.
STORY-017 (REQ-UI-002) relies on this rule instead of re-specifying it.

## UX states (UI stories only)
- empty: N/A (a single value, never empty).
- loading: skeleton `role="status"` `aria-busy="true"` (REQ-OPS-021).
- error: `health.error` + `common.retry`; covers 503 Problem, 502/504 non-JSON, network failure, `200` with `status: DOWN`, schema-invalid 200; no auto-retry; Retry sends exactly one request.
- offline: `health.offline` + `common.retry`; automatic single refetch on `online`.
- RTL: `navigator.language` `ar*` → `lang="ar" dir="rtl"`; toggle button `English`/`العربية`; version and SHA in `<bdi dir="ltr">`.
- dark: `prefers-color-scheme: dark` → dark background token.
- matrix: LTR/RTL × light/dark at 375×812 (4 renders), axe zero serious/critical, screenshot baselines; 1280×800 desktop renders in STORY-017.

## Contract impact
None — consumes the existing `GET /health` (`Health`, `Problem`). No generated client yet (STORY-007).

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Low. The page renders server-provided strings as text only (React escaping; no `dangerouslySetInnerHTML`; asserted by the injection unhappy path). No credentials, cookies or storage are used. The Vite dev proxy targets only `http://127.0.0.1:8080` (dev-time); in the deployed stack the bundle is served by the `web` image behind Caddy (STORY-142), and CSP for the web app is set there / in STORY-010.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-143 (running backend `/health` on `127.0.0.1:8080` for the Vite proxy; it depends on STORY-002). Unblocks STORY-142 (stack), STORY-007 (generated client switch), STORY-017 (app shell builds on the i18n setup and lint rule).

## Size estimate (hand-written lines incl. tests; generated scaffolding excluded)
Excluded: the `npm create vite` React-TS template files, lockfile, screenshot baselines. Production + config ≈ 220 (ESLint/tsconfig/Vite-proxy/package-script additions ≈ 50, i18n setup + 2 JSON files ≈ 40, `health.ts` with schema ≈ 30, version page + language toggle ≈ 75, theme tokens light/dark ≈ 25); tests ≈ 175 (Vitest state tests, table-driven over the error variants incl. `status: DOWN`, + injection ≈ 85, ESLint-fixture test ≈ 25, Playwright config + real-backend/offline/dark/language specs + 4-render mobile matrix ≈ 65). **Total ≈ 395** (the desktop renders moved to STORY-017).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 395; generated Vite scaffolding excluded)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — split out of STORY-002 after spec review |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — depends_on re-pointed to STORY-143 (orchestrator size split of STORY-002) |
| 2026-10-23 | 2 spec review (loop 2) | spec-reviewer | CHANGES_REQUESTED |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — status DOWN case, proxy 127.0.0.1, `npm run e2e` + real-backend spec, matrix reduced to 375×812 (desktop renders → STORY-017), lint wording; ≈ 395 lines |

## Follow-ups (verify+review loop 2, 2026-10-24; NIT only)
- Make the attribute i18n lint rule also catch braced and template-literal strings (`aria-label={"x"}`); STORY-017 relies on it.
- Move the 30 s timeout from the global config onto `src/lint.test.ts`, which belongs to the test-designer.
- Give the loading status region an accessible text (needs an i18n key; STORY-017).
- Match only exactly `/health` with the Vite proxy key, not other paths starting with it.
- Upgrade eslint 9.39.5 (deprecated) when jsx-a11y supports ESLint 10.
