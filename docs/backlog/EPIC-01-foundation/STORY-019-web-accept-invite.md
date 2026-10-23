---
id: STORY-019
epic: EPIC-01
title: Web accept-invite page sets the password and signs the new member in
phase: 1
status: READY
size: S
modules: [web]
depends_on: [STORY-011, STORY-018]
reqs: [REQ-UI-012]
implements: ["PROMPT.md §11 (Onboarding PWA: accept invite → set password)", "PROMPT.md §9 (POST /auth/invite)"]
owner_agent: web-engineer
---

# STORY-019 — Web accept-invite page

## Goal
Member 2 opens the invite link on their iPhone, sets a password and is signed in — the first step of the PWA onboarding (PROMPT §11).

## Requirements and acceptance criteria
### REQ-UI-012 — Accept invite
**Given** `/invite/<token>` **Then** the page shows the household name and inviter (from `GET /auth/invite/<token>` preview, or a generic welcome if the preview endpoint is not in the contract — architect decides), a password field with strength hints (≥ 10 chars) and a confirm field, and a language toggle; **When** submitted **Then** `POST /auth/invite/accept {token, password}` is called, the returned `TokenPair` is stored as in REQ-UI-011 and the user lands on `/` with a one-time toast "Welcome, Mai".
Unhappy path: **Given** `410` **Then** "This invite has expired — ask Mohamed to send a new one" with no form; **Given** `404` **Then** "This link is not valid"; **Given** mismatching confirm or a weak password **Then** inline errors and no request; **Given** `429` **Then** the Retry-After message; **Given** the user is already signed in **Then** a prompt to log out first (prevents accepting into the wrong session).

## UX states (UI stories only)
empty: N/A · loading: skeleton while validating the token · error: expired / invalid / network with Retry · offline: banner, submit disabled · RTL: mirrored · dark: tokens.

## Contract impact
Uses `POST /auth/invite/accept` (v0); optional new `GET /auth/invite/{token}` preview (non-breaking, architect decides).

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Token appears in the URL path once; the page replaces history state after reading it so it is not kept in browser history; no token in logs or analytics.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-011, STORY-018.

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
