---
id: STORY-022
epic: EPIC-02
title: Update, soft-delete and restore an expense with audit history
phase: 2
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-020]
reqs: [REQ-EXP-003, REQ-EXP-004, REQ-EXP-025]
implements: ["PROMPT.md §9 (PATCH/DELETE /api/expenses, /restore)", "PROMPT.md §11 (Transaction detail: audit history, delete; swipe delete with undo)", "PROMPT.md §8 (audit_log, soft delete)"]
owner_agent: backend-engineer
---

# STORY-022 — Update, soft-delete and restore an expense

## Goal
A member fixes any field of an entry, deletes it with the option to undo, and can see who changed what (PROMPT §9, §11).

## Requirements and acceptance criteria
### REQ-EXP-003 — Partial update
**Given** an existing expense **When** `PATCH /api/expenses/{id}` with any subset of `ExpensePatch {type, amount, occurredAt, categoryId, merchantRaw, merchantId, paidByMemberId, paymentAccountId, paymentMethod, notes, tags, status (CONFIRMED|IGNORED), isRecurring}` and header `If-Match: "<updatedAt etag>"` (or body `expectedUpdatedAt`) **Then** `200 Expense`, `updatedAt` advances, and one `audit_log` row per changed field (`field, oldValue, newValue, memberId`) is written; changing `categoryId` or `merchantId` also emits a `CORRECTION` audit kind (consumed by merchant-rule learning in Phase 3).
Unhappy path: **Given** a stale `If-Match` **Then** `409 title: "Expense was modified"` with the current `Expense` in `detail`-adjacent `current` property; **Given** the same validation errors as REQ-EXP-002/021 **Then** `422`; **Given** `status: PENDING_REVIEW` set manually **Then** `422` (only the pipeline sets review statuses); **Given** a deleted expense **Then** `404`.

### REQ-EXP-004 — Soft delete and restore
**Given** an expense **When** `DELETE /api/expenses/{id}` **Then** `204`, `deletedAt` set, it disappears from lists and analytics, and `audit_log` records `EXPENSE_DELETED`; **When** `POST /api/expenses/{id}/restore` **Then** `200 Expense` with `deletedAt: null` and `EXPENSE_RESTORED` logged.
Unhappy path: **Given** DELETE twice **Then** second returns `204` (idempotent); **Given** restore of a non-deleted expense **Then** `200` no-op; **Given** restore after 30 days **Then** `410` (purge job may have removed it — purge itself is Phase 9 backups/retention, so until then restore always works and the test for 410 is a contract test only).

### REQ-EXP-025 — Audit history
**Given** `GET /api/expenses/{id}/audit` **Then** `200 {items: AuditEntry[{at, memberId, kind, field?, oldValue?, newValue?}]}` newest first, including creation, edits, delete/restore and (later) merges.
Unhappy path: **Given** another household's expense **Then** `404`; values for fields `notes` are included (they are the household's own data) but never logged by the server.

## UX states (UI stories only)
N/A.

## Contract impact
`PATCH /api/expenses/{expenseId}`, `DELETE`, `POST .../restore`, `ExpensePatch` in v0; add `If-Match`/`409` and new `GET /api/expenses/{expenseId}/audit` with `AuditEntry` schema (non-breaking additions).

## Data / migration impact
Uses `audit_log` (V4); add `expense.version`/etag basis if needed (expand).

## Security / privacy / LLM-safety impact
Either member may edit any household expense (two-person household, decision recorded); every change is attributed in the audit log. No LLM.

## Eval impact
None now; `CORRECTION` audit rows become eval candidates in Phase 3 (S13 production feedback loop).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-020.

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
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
