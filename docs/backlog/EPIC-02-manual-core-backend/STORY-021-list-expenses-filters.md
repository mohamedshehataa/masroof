---
id: STORY-021
epic: EPIC-02
title: List and read expenses with filters, text search, sorting and cursor pagination
phase: 2
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-020]
reqs: [REQ-EXP-001, REQ-EXP-022, REQ-EXP-023, REQ-EXP-024]
implements: ["PROMPT.md §9 (GET /api/expenses filters, sort, cursor pagination)", "PROMPT.md S9 (p95 < 200 ms reads, no seq scans)"]
owner_agent: backend-engineer
---

# STORY-021 — List and read expenses with filters and pagination

## Goal
Both apps can page through the ledger with every filter the Transactions tab needs, fast (PROMPT §9, S9).

## Requirements and acceptance criteria
### REQ-EXP-001 — Filtered, sorted, paginated list
**Given** an authenticated member **When** `GET /api/expenses?from=2026-10-01&to=2026-10-31&memberId=&categoryId=&merchantId=&accountId=&source=SMS,WHATSAPP&status=CONFIRMED&minAmountMinor=&maxAmountMinor=&type=EXPENSE&sort=occurredAt:desc&limit=50&cursor=` **Then** `200 ExpensePage {items[], nextCursor}` where `from`/`to` are Cairo calendar dates (inclusive, converted to UTC bounds), `categoryId` includes child categories, repeated/comma values are ORs within a filter and ANDs across filters, default sort `occurredAt:desc,id:desc`, allowed sorts `occurredAt|amountEgp|createdAt`, `limit` 1..200 (default 50), deleted rows excluded unless `includeDeleted=true`, and `nextCursor` is opaque and stable under concurrent inserts (keyset).
Unhappy path: **Given** `to < from`, `limit=500`, `sort=notes:asc` or a tampered cursor **Then** `422`/`400` Problem naming the parameter.

### REQ-EXP-022 — Text search
**Given** `q=كارفور` or `q=carrefour` **When** listing **Then** items whose `merchantRaw`, normalized merchant name, `notes` or `tags` match case-insensitively after Arabic normalization (alef/hamza/taa-marbuta folding, diacritics stripped) are returned; `q` is trimmed and limited to 100 chars.
Unhappy path: **Given** `q` contains SQL metacharacters (`'; drop table`) **Then** results are empty or literal matches; no error, no injection (parameterised query, semgrep rule).

### REQ-EXP-023 — Read one expense
**Given** `GET /api/expenses/{id}` **Then** `200 Expense` including `items[]`, `sources[] (RawMessageSummary)`, `splits[]`, `category`, `merchant`, `paidBy` embedded summaries; **Given** a soft-deleted expense **Then** `404` unless `includeDeleted=true` (then `200` with `deletedAt`).
Unhappy path: **Given** another household's id or a malformed UUID **Then** `404` / `400`.

### REQ-EXP-024 — Read performance
**Given** 10,000 expenses in one household **When** k6 runs the list with the default filters at 20 rps for 60 s **Then** p95 < 200 ms; **Given** `EXPLAIN (FORMAT JSON)` of the list query in the integration test **Then** no `Seq Scan` on `expense`; Hibernate statistics show ≤ 3 queries per page (no N+1 for embedded summaries).
Unhappy path: **Given** `q` is set **Then** p95 < 400 ms (documented relaxation; trigram index if needed, architect decides).

## UX states (UI stories only)
N/A.

## Contract impact
`GET /api/expenses`, `GET /api/expenses/{expenseId}`, `ExpensePage`, `Expense`, `Cursor`, `Limit`, `FromDate`, `ToDate` in v0; add `q`, `type`, `includeDeleted`, `minAmountMinor`, `maxAmountMinor` parameters if absent (non-breaking).

## Data / migration impact
Indexes from V4 (REQ-OPS-043); optional `V9__expense_search_index.sql` (GIN trigram on normalized text; expand only).

## Security / privacy / LLM-safety impact
Household scoping; parameterised queries only; cursor is signed/opaque so ids are not enumerable. Triggers `performance-engineer`.

## Eval impact
None.

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
