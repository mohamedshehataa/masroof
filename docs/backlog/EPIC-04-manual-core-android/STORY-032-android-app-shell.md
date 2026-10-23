---
id: STORY-032
epic: EPIC-04
title: Android app shell — brand theme, 5-tab navigation, ar/en with RTL, amount formatting, screenshot gates
phase: 2
status: READY
size: M
modules: [android]
depends_on: [STORY-008]
reqs: [REQ-AND-002, REQ-AND-003, REQ-AND-004, REQ-AND-005]
implements: ["PROMPT.md §11 (design language, navigation)", "PROMPT.md §14 Phase 2 (Android app skeleton)", "PROMPT.md S9 (Roborazzi matrix)"]
owner_agent: android-engineer
---

# STORY-032 — Android app shell

## Goal
The Android app has its skeleton — Material 3 brand theme in light/dark, the five-tab bottom bar, Arabic/English with RTL and locale-aware amounts — with screenshot tests gating every later screen (PROMPT §11).

## Requirements and acceptance criteria
### REQ-AND-002 — Brand theme light and dark
**Given** the app starts **Then** a Material 3 theme with dynamic colour off, primary deep teal, accent warm amber, semantic red/green only for over-budget/income, typography IBM Plex Sans Arabic + Inter (bundled fonts) with tabular numerals for amounts, and dark theme following the system or the in-app setting (DataStore).
Unhappy path: **Given** a device with forced dark mode and font scale 1.5 **Then** no text is clipped on the shell screens (Roborazzi 1.5 variants).

### REQ-AND-003 — 5-tab navigation
**Given** the app **Then** a `NavigationBar` with Home, Transactions, Add (centre FAB-style item), Insights, More, each tab a nested nav graph preserving state on switch, 48 dp targets, content descriptions, back from a tab root exits the app with the system predictive-back animation; tapping Add opens a bottom sheet over the current tab; deep link `masroof://expenses/{id}` opens the detail route (used by notifications in Phase 4).
Unhappy path: **Given** an unknown deep link **Then** the app opens Home and logs a non-fatal.

### REQ-AND-004 — Language, RTL and amounts
**Given** More → Language = Arabic **Then** per-app locale is set via `AppCompatDelegate.setApplicationLocales` (Android 13+ settings integration), layout direction is RTL, strings come from `values-ar/` (no hard-coded strings — Android Lint `HardcodedText` is an error), and the digits setting renders `١٬٢٥٠ ج.م` vs `1,250 EGP` through one `MoneyFormatter` (unit-tested, `Long` minor units only).
Unhappy path: **Given** a missing Arabic string **Then** Lint `MissingTranslation` fails `checkFast`; **Given** `amountMinor` overflow of `Int` **Then** formatting still correct (`Long` everywhere).

### REQ-AND-005 — Quality gates for UI
**Given** `./gradlew checkFast` **Then** Roborazzi screenshot tests run the shell in LTR/RTL × light/dark × font scale 1.0/1.5 and fail on diff > 0.1%; Compose UI tests cover tab switching; Kover reports ≥ 80% on view-model code (none yet → gate configured, not failing on empty).
Unhappy path: **Given** a screenshot diff **Then** the report path is printed and the task fails (no auto-update of baselines in CI).

## UX states (UI stories only)
empty: N/A · loading: splash via `SplashScreen` API ≤ 1 s · error: global crash handler → friendly restart screen · offline: top banner component available to screens · RTL: bar order mirrored · dark: theme.

## Contract impact
None.

## Data / migration impact
None (DataStore preferences).

## Security / privacy / LLM-safety impact
`allowBackup=false`, network security config HTTPS-only (from STORY-008); no analytics SDK.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-008.

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
