---
id: STORY-011
epic: EPIC-01
title: Admin invites member 2 and the invite is accepted with a new password
phase: 1
status: READY
size: M
modules: [backend]
depends_on: [STORY-010]
reqs: [REQ-SEC-002, REQ-SEC-008]
implements: ["PROMPT.md §9 (POST /auth/invite)", "PROMPT.md §1 (exactly two members)", "PROMPT.md §11 (PWA onboarding: accept invite)"]
owner_agent: backend-engineer
---

# STORY-011 — Admin invites member 2 and the invite is accepted

## Goal
The admin creates a one-time invite link for the second household member, who accepts it by setting a password and lands signed in (PROMPT §9, §11 onboarding step 1).

## Requirements and acceptance criteria
### REQ-SEC-002 — Admin creates an invite
**Given** an authenticated ADMIN **When** `POST /auth/invite` with `{displayName: "Mai", phoneE164: "+2010...", email?: "mai@example.com", color?}` **Then** `201` with `Invite {id, token (≥ 128 bits, shown once), url, expiresAt = now + 7 days, status: PENDING}`; the DB stores only the token hash; an `audit_log` row `INVITE_CREATED` is written.
Unhappy path: **Given** a MEMBER (non-admin) calls it **Then** `403`; **Given** the household already has 2 active members **Then** `409 title: "Household is full"`; **Given** `phoneE164` is not E.164 (`0101234567`) **Then** `422` naming the field; **Given** a pending invite exists for the same phone **Then** the old one is revoked and a new token issued (`201`).

### REQ-SEC-008 — Invite acceptance creates the member
**Given** a pending invite token **When** `POST /auth/invite/accept` with `{token, password}` (password ≥ 10 chars, not in the top-10k breached list) **Then** `200` with `TokenPair` for the new member, a `member` row (role MEMBER, display name/colour from the invite) and `app_user` row are created, the invite becomes `ACCEPTED` and cannot be reused.
Unhappy path: **Given** an expired token **Then** `410 title: "Invite expired"`; **Given** an unknown or already-accepted token **Then** `404` (same body for both); **Given** password `password1` **Then** `422` with `errors[0].field == "password"`; **Given** 20 accept attempts per minute from one IP **Then** `429`.

## UX states (UI stories only)
N/A (web screen in STORY-019).

## Contract impact
`POST /auth/invite`, `POST /auth/invite/accept`, schemas `InviteRequest`, `Invite`, `InviteAcceptRequest` in v0; add `410` response to accept (non-breaking).

## Data / migration impact
New migration `V5__invite.sql` (or merged with STORY-009's V5 if sequenced first — architect decides numbering): table `invite (id, household_id, token_hash, display_name, phone_e164, email, color, expires_at, status, accepted_member_id)`.

## Security / privacy / LLM-safety impact
Invite token is a bearer secret: hashed at rest, shown once, 7-day expiry, rate-limited acceptance. Triggers `security-reviewer`. Sending the link to the member's phone is manual by the admin in this phase (WhatsApp delivery is Phase 5).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-010.

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
