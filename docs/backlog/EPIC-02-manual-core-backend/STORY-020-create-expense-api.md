---
id: STORY-020
epic: EPIC-02
title: Create a manual expense via POST /api/expenses
phase: 2
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-005, STORY-013, STORY-014]
reqs: [REQ-EXP-002, REQ-EXP-020, REQ-EXP-021]
implements: ["PROMPT.md §9 (POST /api/expenses)", "PROMPT.md §1 (manual entry)", "PROMPT.md §8 (expense, raw_message MANUAL, expense_source, audit_log)"]
owner_agent: backend-engineer
---

# STORY-020 — Create a manual expense

## Goal
A member records an expense, income, refund, transfer or cash withdrawal by hand and it lands in the ledger as a CONFIRMED entry with an audit trail (PROMPT §9, §1 item 6).

## Requirements and acceptance criteria
### REQ-EXP-002 — Create expense
**Given** an authenticated member **When** `POST /api/expenses` with `ExpenseCreate {type: "EXPENSE", amount: {amountMinor: 20000, currency: "EGP"}, occurredAt: "2026-10-22T11:05:00Z", categoryId, paidByMemberId, paymentAccountId?, paymentMethod: "CASH", merchantRaw?: "بنزين", notes?, tags?: ["car"]}` **Then** `201 Expense` with `status: CONFIRMED`, `confidence: 1`, `amountEgp == amount` (EGP), `source: MANUAL`, `createdBy` = caller, `Location` header; a `raw_message` row (`source MANUAL`, `payload` = request) and an `expense_source` row (`role PRIMARY`) link it; an `audit_log` row `EXPENSE_CREATED` is written.
**Given** `type: "TRANSFER"` or `"CASH_WITHDRAWAL"` **Then** created with that type (excluded from spending totals by STORY-025).
Unhappy path: **Given** `categoryId` or `paidByMemberId` of another household / archived category **Then** `404` / `422`; **Given** `occurredAt` more than 1 day in the future **Then** `422`; **Given** `notes` > 500 chars or > 10 tags or a tag > 30 chars **Then** `422`; **Given** a MEMBER sets `paidByMemberId` to the other member **Then** allowed (`201`) — either member may record who paid.

### REQ-EXP-020 — Idempotent creation
**Given** header `Idempotency-Key: <uuid>` **When** the same key and identical body are sent twice (client retry) **Then** both return `201` with the same `Expense.id` and exactly one row exists.
Unhappy path: **Given** the same key with a different body **Then** `409 title: "Idempotency key reused"`; **Given** the key is older than 24 h **Then** it is treated as new.

### REQ-EXP-021 — Money validation invariants
**Given** `amount.amountMinor` ≤ 0, non-integer, or > 10^13 **Then** `422` with `errors[].field == "amount.amountMinor"`; **Given** `currency` not a known ISO-4217 code (`"EG"`, `"egp"`) **Then** `422`; **Given** `currency` ≠ household base currency **Then** `422 title: "Foreign currency needs FX"` until STORY-052 lifts it (recorded decision: FX arrives in Phase 3); the domain `Money` type has no `double`/`float` (REQ-OPS-024 rule covers it).
Unhappy path: **Given** `amountMinor: 1e5` as a JSON float **Then** `400` (strict integer parsing).

## UX states (UI stories only)
N/A.

## Contract impact
`POST /api/expenses`, `ExpenseCreate`, `Expense`, `Money`, `IdempotencyKey` parameter in v0; add `409` response (non-breaking).

## Data / migration impact
Uses V4 tables; if `expense.created_by_member_id` is missing → `V8__expense_created_by.sql` (expand).

## Security / privacy / LLM-safety impact
Household scoping (REQ-SEC-012); notes/merchantRaw are user text stored as-is and never logged (REQ-OPS-030). No LLM.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-005, STORY-013, STORY-014.

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
| 2026-10-25 | 5 implement | backend-engineer | Manual create/list/get/patch/delete shipped (speed mode). Deferred, see DECISIONS: raw_message, expense_source and audit_log rows wait for the ingestion stories (Phase 3/4); createdBy waits for auth (STORY-009). |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |

### Follow-ups (recorded, not built)
- Household-scoped composite foreign keys (category, payment_account, member must belong to the expense household at the database level).
- Optimistic locking via `version` (PATCH `version` is accepted and ignored; 409 on stale).
- PATCH clearing nullable fields with an explicit `null` (today `null` means unchanged).
- Items, splits, merchants, foreign currency (amountOriginal/fxRate) and recurring fields are rejected with 422.
