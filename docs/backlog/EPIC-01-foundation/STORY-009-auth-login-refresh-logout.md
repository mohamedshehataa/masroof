---
id: STORY-009
epic: EPIC-01
title: Email + password login with rotating refresh tokens and logout
phase: 1
status: READY
size: M
modules: [backend]
depends_on: [STORY-004, STORY-006]
reqs: [REQ-SEC-001, REQ-SEC-005, REQ-SEC-006, REQ-SEC-007]
implements: ["PROMPT.md §9 (auth)", "PROMPT.md §12 (bearer tokens)"]
owner_agent: backend-engineer
---

# STORY-009 — Email + password login with rotating refresh tokens and logout

## Goal
A member signs in with email + password and receives a short-lived access JWT and a rotating refresh token; every `/api/*` request is scoped to that member's household (PROMPT §9).

## Requirements and acceptance criteria
### REQ-SEC-001 — Login issues a token pair
**Given** app user `mohamed@example.com` with a BCrypt password hash **When** `POST /auth/login` with `{email, password}` is valid **Then** `200` with `TokenPair {accessToken (JWT, exp ≤ 15 min, claims sub=userId (app_user.id), hid=householdId, role, iat, exp (15 min), jti), refreshToken (opaque ≥ 256 bits), expiresIn}` and the refresh token's SHA-256 is stored in `refresh_token` with `expires_at = now + 30 days`; the password is never logged.
Unhappy path: **Given** a wrong password or unknown email **Then** `401 application/problem+json` with the same `title: "Invalid credentials"` for both (no user enumeration), response time within ±20% of the success path (constant-time compare).
Unhappy path: **Given** a body missing `password` or with an email of 300 chars **Then** `422` Problem with `errors[]` naming the field.

### REQ-SEC-005 — Refresh rotates and detects reuse
**Given** a valid refresh token **When** `POST /auth/refresh` is called **Then** `200` with a new `TokenPair`, the old token row is marked `rotated_at` and linked to the new one (same `family_id`).
**Given** an already-rotated token **When** it is presented again **Then** `401` and every token in the family is revoked (reuse detection), and an `audit_log` row `REFRESH_REUSE` is written with the member id.
Unhappy path: **Given** an expired refresh token **Then** `401` Problem `title: "Refresh token expired"`.

### REQ-SEC-006 — Logout revokes the refresh token
**Given** an authenticated member **When** `POST /auth/logout` with `{refreshToken}` (or `allDevices: true`) **Then** `204`, the token (or all tokens of the member) are revoked and a subsequent refresh returns `401`; the access token keeps working until its `exp` (documented).
Unhappy path: **Given** a refresh token belonging to another member **Then** `204` with no effect (no oracle).

### REQ-SEC-007 — Login brute-force protection
**Given** 10 failed logins for the same email or from the same IP within 15 minutes **When** the 11th attempt arrives **Then** `429 application/problem+json` with `Retry-After` for the remainder of the window, and a metric `auth_login_blocked_total` increments.
Unhappy path: **Given** the lockout is active **When** the correct password is sent **Then** still `429` (window is not bypassed by a correct guess).

## UX states (UI stories only)
N/A.

## Contract impact
`POST /auth/login`, `/auth/refresh`, `/auth/logout` and schemas `LoginRequest`, `TokenPair`, `RefreshRequest`, `LogoutRequest` already in v0; add `429` response to `/auth/login` (non-breaking).

## Data / migration impact
Uses `app_user`, `refresh_token` from V2 (STORY-004); if `family_id`/`rotated_at` are missing, new migration `V5__refresh_token_family.sql` (expand).

## Security / privacy / LLM-safety impact
Trust boundary: public internet → auth endpoints. BCrypt cost 12; JWT signed HS256 with the key from `.env` (`MASROOF_JWT_SECRET`, >= 32 bytes, checked at prod start); refresh tokens stored hashed; constant-time comparisons; rate limit per email and IP; triggers `security-reviewer` (`**/auth/**`).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-004 (schema), STORY-006 (generated interfaces).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
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
