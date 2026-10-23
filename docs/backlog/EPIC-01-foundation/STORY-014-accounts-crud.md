---
id: STORY-014
epic: EPIC-01
title: Payment accounts (cards, bank, wallets, cash) CRUD
phase: 1
status: READY
size: M
modules: [backend]
depends_on: [STORY-010]
reqs: [REQ-EXP-014, REQ-EXP-015]
implements: ["PROMPT.md §9 (CRUD /api/accounts)", "PROMPT.md §8 (payment_account)", "PROMPT.md §4.3 (card last-4 → account → owner)"]
owner_agent: backend-engineer
---

# STORY-014 — Payment accounts CRUD

## Goal
Each member registers their cards, bank accounts, wallets and cash so expenses — and later SMS last-4 digits — map to an account and its owner (PROMPT §8, §4.3).

## Requirements and acceptance criteria
### REQ-EXP-014 — List and create accounts
**Given** an authenticated member **When** `GET /api/accounts` **Then** `200 {items: PaymentAccount[]}` for the household (`id, memberId, type, institution, last4, nickname, currency, isShared, archived`), including the other member's accounts (needed to attribute shared expenses).
**When** `POST /api/accounts` with `{type: "CARD", institution: "CIB", last4: "1234", nickname: "CIB Visa", currency: "EGP", isShared: false}` **Then** `201` owned by the caller (`memberId` = caller; an ADMIN may set `memberId` to the other member).
Unhappy path: **Given** `last4: "12"` or `"abcd"` **Then** `422`; **Given** `type: "CASH"` with a `last4` **Then** `422` (cash has no last4); **Given** the same `(memberId, institution, last4)` already exists **Then** `409`; **Given** `currency: "EG"` **Then** `422` (ISO-4217 only); **Given** a MEMBER sets `memberId` to the admin **Then** `403`.

### REQ-EXP-015 — Update and archive accounts
**Given** the owner or an ADMIN **When** `PATCH /api/accounts/{id}` with `{nickname, isShared, institution}` **Then** `200`; `last4`/`type` are immutable (`422` if present).
**When** `DELETE /api/accounts/{id}` **Then** `204`, the account is archived (soft delete), hidden from pickers, and existing expenses keep the reference and still render its nickname.
Unhappy path: **Given** a MEMBER tries to edit the admin's account **Then** `403`; **Given** another household's id **Then** `404`; **Given** a second DELETE **Then** `204` (idempotent).

## UX states (UI stories only)
N/A.

## Contract impact
`/api/accounts`, `/api/accounts/{accountId}`, `PaymentAccount`, `PaymentAccountInput` in v0 (unchanged).

## Data / migration impact
None beyond V2.

## Security / privacy / LLM-safety impact
Only last-4 digits are ever stored (§12 "mask account numbers except last-4"); validation forbids longer values. Owner/admin write authorization.

## Eval impact
None (last4 → account mapping is consumed by the SMS parser in Phase 3, REQ-SMS-013).

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
