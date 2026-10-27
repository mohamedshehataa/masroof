---
id: STORY-167
epic: EPIC-01
title: Refresh-token reuse grace window (60 s) so a duplicate refresh does not sign the user out
phase: 1
status: DONE
size: S
modules: [backend]
depends_on: [STORY-009]
reqs: [REQ-SEC-063, REQ-SEC-064]
implements: ["PROMPT.md §9 (auth)"]
owner_agent: backend-engineer
risk_tier: high
---

# STORY-167 — Refresh-token reuse grace window

## Goal
Production incident 2026-10-27: the Android app sent two refresh calls with the same token at once; the second tripped reuse detection and revoked the family, signing the user out. A short server-side grace window absorbs duplicates and retries (STORY-009 REQ-SEC-005 stays for real reuse).

## Requirements and acceptance criteria
### REQ-SEC-063 — A rotated token re-presented within 60 s gets a fresh pair
**Given** a refresh token that was rotated less than 60 s ago (measured from its first rotation, which never moves) **When** it is presented again **Then** `200` with a new pair (a further successor in the same family), nothing is revoked, the earlier successor still works, and an `audit_log` row `REFRESH_GRACE` is written (no tokens).
Unhappy path: **Given** the same token at 60 s or later **Then** `401`, the family is revoked and `REFRESH_REUSE` is audited. **Given** a token revoked by logout or earlier reuse **Then** `401`, never a grace token. An expired token is `Expired` first.

### REQ-SEC-064 — Grace re-issues are bounded
**Given** a rotated token that already received 5 grace re-issues **When** it is presented a 6th time inside the window **Then** it is treated as reuse: family revoked, `REFRESH_REUSE` audited, `401`.

## Contract impact
None. Migration V11 adds `refresh_token.grace_count` (expand-only).
