---
id: STORY-017
epic: EPIC-01
title: Web app shell — i18n ar/en with RTL, brand theme light/dark, 5-tab navigation, amount formatting
phase: 1
status: IN_PROGRESS
size: M
modules: [web]
depends_on: [STORY-007]
reqs: [REQ-UI-002, REQ-UI-003, REQ-UI-004, REQ-UI-005]
implements: ["PROMPT.md §11 (design language, navigation)", "PROMPT.md §1 (bilingual, RTL)", "PROMPT.md §11 PWA-specific (desktop layout)"]
risk_tier: low
owner_agent: web-engineer
---

# STORY-017 — Web app shell

## Goal
The PWA has its skeleton: language switch with full RTL, the brand theme in light and dark, the 5-tab bottom bar (sidebar on desktop) and locale-aware amount formatting, so every screen story only fills a tab (PROMPT §11).

## Requirements and acceptance criteria
### REQ-UI-002 — Language switch with full RTL
**Given** the app loads with no stored preference **When** the browser language is `ar-*` **Then** the UI renders in Arabic with `<html lang="ar" dir="rtl">`; otherwise English LTR. **When** the user switches language in More → Language **Then** every visible string changes without reload, the choice persists in `localStorage`, and the layout mirrors (bottom bar order, icons with directional meaning flipped, text alignment).
**Given** `npm run check-fast` **Then** the `i18next/no-literal-string` rule from REQ-OPS-025 (STORY-141) stays enabled and its fixture test stays green (not re-specified here), and a unit test asserts `ar.json` and `en.json` have identical key sets. The skeleton's language toggle and `navigator.language` default from STORY-141 are replaced by this REQ's More → Language with persistence.
Unhappy path: **Given** a missing key at runtime **Then** the English fallback renders and a console warning is logged in dev only (never a raw key like `home.title` in production builds).

### REQ-UI-003 — Brand theme, light and dark
**Given** the design tokens **Then** Tailwind/shadcn use the custom palette (primary deep teal `#0F766E` family, accent warm amber `#D97706` family, semantic red/green reserved for over-budget/income), fonts IBM Plex Sans Arabic (Arabic) and Inter (Latin) with `font-variant-numeric: tabular-nums` on amount components; **When** `prefers-color-scheme: dark` or More → Theme = Dark **Then** the dark palette applies with AA contrast (axe check in Playwright, zero serious/critical).
Unhappy path: **Given** the web fonts fail to load **Then** system fallbacks render with no layout shift > 0.1 CLS (Lighthouse assertion).

### REQ-UI-004 — Navigation: 5 tabs on mobile, sidebar on desktop
**Given** a viewport < 1024 px **Then** a bottom bar shows Home, Transactions, Add (centre FAB), Insights, More with 48 px touch targets, `aria-current` on the active tab, safe-area bottom padding; **Given** ≥ 1024 px **Then** a left sidebar with the same five entries and a wider content grid; routes are `/`, `/transactions`, `/add`, `/insights`, `/more` and deep links reload to the right tab.
**Given** the Playwright matrix LTR/RTL × light/dark × `375×812` / `1280×800` **Then** the app shell and the STORY-141 version page each have zero axe `serious`/`critical` violations and match committed screenshot baselines (`maxDiffPixelRatio: 0.01`); the `1280×800` version-page renders moved here from STORY-141, which covers only `375×812`.
Unhappy path: **Given** a route that does not exist **Then** a bilingual 404 page with a link Home; **Given** an unauthenticated visit to any tab **Then** redirect to `/login` preserving the return path (login itself is STORY-018).

### REQ-UI-005 — Amount and digit formatting
**Given** `Money{amountMinor: 125000, currency: "EGP"}` **When** rendered with locale `en` **Then** `1,250 EGP`; with locale `ar` and digits setting Arabic-Indic **Then** `١٬٢٥٠ ج.م`; with locale `ar` and digits Western **Then** `1,250 ج.م`; piasters show only when non-zero (`1,250.50 EGP`); the digits setting lives in More → Language/digits and persists.
Unhappy path: **Given** a currency without a known Arabic symbol (`SAR`) **Then** the ISO code is used (`١٬٢٥٠ SAR`); **Given** `amountMinor` is not a safe integer **Then** the formatter throws in dev and renders `—` in production.

## UX states (UI stories only)
empty: N/A · loading: app shell paints before data (no white screen > 300 ms) · error: global error boundary with Retry/Reload · offline: top banner "You're offline" (full offline shell is Phase 8) · RTL: all of the above mirrored · dark: palette tokens.

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None (presentation only); CSP for the web app is served by Caddy and must allow Google Fonts or self-hosted fonts (self-hosting preferred, decision for the architect).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-007 (which depends on STORY-141, where the i18n setup and lint rule originate).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — i18n lint rule now referenced from REQ-OPS-025 (STORY-141) instead of re-specified |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — owns the full LTR/RTL × light/dark × mobile/desktop screenshot/axe matrix incl. the 1280×800 version-page renders moved from STORY-141 |
