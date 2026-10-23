---
id: STORY-018
epic: EPIC-01
title: Web login page and session handling with silent refresh
phase: 1
status: READY
size: M
modules: [web]
depends_on: [STORY-009, STORY-017]
reqs: [REQ-UI-010, REQ-UI-011]
implements: ["PROMPT.md §9 (auth)", "PROMPT.md §13 (Playwright: login)"]
owner_agent: web-engineer
---

# STORY-018 — Web login page and session handling

## Goal
A member signs in on the PWA and stays signed in across reloads via silent refresh, and is sent back to login when the session is gone (PROMPT §9).

## Requirements and acceptance criteria
### REQ-UI-010 — Login page
**Given** `/login` **Then** a form with email and password fields (labels, `autocomplete="username"/"current-password"`, show/hide password), a submit button and a language toggle; **When** valid credentials are submitted **Then** the button shows a spinner, `POST /auth/login` is called through the generated client, and the user lands on the return path or `/`.
Unhappy path: **Given** `401` **Then** an inline bilingual error "Email or password is incorrect" and the password field is cleared; **Given** `429` **Then** "Too many attempts, try again in N minutes" using `Retry-After`; **Given** network failure **Then** an error with Retry; **Given** empty fields **Then** client-side validation messages and no request.

### REQ-UI-011 — Session storage, silent refresh and logout
**Given** a successful login **Then** the access token is kept in memory and the refresh token in `localStorage` (decision recorded: no cookies in v1 because the Android app shares the same contract); **When** any API call returns `401` **Then** the client calls `POST /auth/refresh` once, retries the original request with the new token, and concurrent 401s share one refresh; **When** the refresh fails **Then** tokens are cleared and the user is redirected to `/login?returnTo=...`.
**Given** More → Log out **Then** `POST /auth/logout` is called, tokens cleared, TanStack Query cache cleared, redirect to `/login`.
Unhappy path: **Given** the tab is reopened after the access token expired **Then** the first request refreshes transparently and the user sees a loading skeleton, not the login page; **Given** `localStorage` is unavailable (private mode) **Then** the session lasts for the tab and a notice explains it.

## UX states (UI stories only)
empty: N/A · loading: submit spinner, route-level skeleton during refresh · error: inline messages above · offline: "You're offline" banner and disabled submit · RTL: mirrored form, Arabic placeholders · dark: tokens.

## Contract impact
Uses `/auth/login`, `/auth/refresh`, `/auth/logout` (v0).

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Tokens never go in URLs or logs; refresh token in `localStorage` is an accepted XSS trade-off mitigated by CSP and no third-party scripts (noted for `security-reviewer`); password field never autofills across households. Playwright test for login is the S13 e2e "login".

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-009, STORY-017.

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
