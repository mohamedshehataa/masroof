---
id: STORY-033
epic: EPIC-04
title: Android login screen and session handling with silent refresh
phase: 2
status: READY
size: M
modules: [android]
depends_on: [STORY-009, STORY-032]
reqs: [REQ-AND-006, REQ-AND-007]
implements: ["PROMPT.md §9 (auth)", "PROMPT.md §11 Onboarding (Android: sign in)"]
owner_agent: android-engineer
---

# STORY-033 — Android login and session

## Goal
Mohamed signs in once on the Android app and stays signed in securely; the first onboarding step of §11 (the rest of onboarding is Phase 4).

## Requirements and acceptance criteria
### REQ-AND-006 — Login screen
**Given** the app with no session **Then** a login screen with email and password fields (autofill hints, IME actions), show/hide password, a Sign-in button and a language toggle; **When** valid credentials are submitted **Then** `POST /auth/login` via the generated client, progress indicator, then Home.
Unhappy path: **Given** `401` **Then** inline "Email or password is incorrect"; `429` → "Too many attempts, try again in N min"; network error → snackbar with Retry; empty fields → validation without a request.

### REQ-AND-007 — Secure session and silent refresh
**Given** a successful login **Then** the token pair is stored in `EncryptedSharedPreferences`/Tink-backed DataStore (never plain DataStore or logs); an OkHttp `Authenticator` refreshes on `401` once (single-flight across concurrent calls) and retries; **When** refresh fails **Then** tokens are wiped and the app returns to login; More → Log out calls `POST /auth/logout` and wipes tokens and the Room cache (STORY-038).
Unhappy path: **Given** the device is offline at launch with a stored session **Then** the app opens to Home with cached data (once STORY-038 ships; until then an offline banner) instead of login; **Given** the keystore is invalidated (device credential reset) **Then** the stored tokens are discarded and login is shown with a notice.

## UX states (UI stories only)
empty: N/A · loading: button progress · error: inline/snackbar · offline: banner, Sign-in disabled · RTL: mirrored, Arabic hints · dark: theme; TalkBack labels.

## Contract impact
Uses `/auth/login`, `/auth/refresh`, `/auth/logout` (v0).

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Tokens encrypted at rest via Android Keystore; no tokens in logs/crash reports; screenshots of the login screen allowed (no FLAG_SECURE needed, decision recorded). Triggers `security-reviewer`.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-009, STORY-032.

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
