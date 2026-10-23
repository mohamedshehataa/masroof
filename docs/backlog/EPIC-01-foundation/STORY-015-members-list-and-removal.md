---
id: STORY-015
epic: EPIC-01
title: Members list and instant removal by the admin
phase: 1
status: READY
size: S
modules: [backend]
depends_on: [STORY-011]
reqs: [REQ-EXP-016, REQ-EXP-017]
implements: ["PROMPT.md §8 (member)", "PROMPT.md §12 (admin can remove a member/device instantly)", "PROMPT.md §11 (Household & members)"]
owner_agent: backend-engineer
---

# STORY-015 — Members list and instant removal

## Goal
Members see who is in the household and the admin can edit a member's display name/colour or remove them instantly, revoking all their access (PROMPT §12).

## Requirements and acceptance criteria
### REQ-EXP-016 — List and edit members
**Given** an authenticated member **When** `GET /api/members` **Then** `200 {items: Member[]}` with `id, displayName, role, color, avatarUrl?, phoneE164 (masked for non-admin), lastSeenAt, status ACTIVE|INVITED|REMOVED`.
**Given** a member **When** `PATCH /api/members/{id}` on themselves with `{displayName, color}` **Then** `200`; an ADMIN may patch any member; `role` changes are `422` in v1 (exactly one admin).
Unhappy path: **Given** `color` is not `#RRGGBB` **Then** `422`; **Given** a MEMBER patches the other member **Then** `403`.

### REQ-EXP-017 — Admin removes a member instantly
**Given** an ADMIN **When** `DELETE /api/members/{id}` for the other member **Then** `204`; within the same transaction every `refresh_token` and `device` token of that member is revoked, the member is soft-deleted (`status REMOVED`), their expenses and accounts remain (attributed to the removed member, shown greyed), and an `audit_log` row `MEMBER_REMOVED` is written; the member's next `/api/*` call returns `401` (REQ-SEC-012).
Unhappy path: **Given** the admin targets themselves **Then** `409 title: "Cannot remove the only admin"`; **Given** a MEMBER calls DELETE **Then** `403`; **Given** an already removed id **Then** `404`.

## UX states (UI stories only)
N/A.

## Contract impact
New paths `GET /api/members`, `PATCH /api/members/{memberId}`, `DELETE /api/members/{memberId}`; reuse schema `Member`, add `MemberPatch` (non-breaking addition; architect adds to `openapi.yaml`).

## Data / migration impact
`member.status` column if absent → migration `V7__member_status.sql` (expand). No contract/removal of columns.

## Security / privacy / LLM-safety impact
Admin-only destructive action; revocation is transactional (ASVS V3 session management). Triggers `security-reviewer` (auth-adjacent).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-011.

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
