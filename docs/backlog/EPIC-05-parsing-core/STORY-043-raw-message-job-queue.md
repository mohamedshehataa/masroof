---
id: STORY-043
epic: EPIC-05
title: Raw message audit trail and DB-backed job queue with SKIP LOCKED workers
phase: 3
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-005, STORY-003]
reqs: [REQ-PARSE-030, REQ-PARSE-031, REQ-PARSE-032]
implements: [PROMPT.md §6 (intro), PROMPT.md §2 (DB-backed job table), PROMPT.md S13 (job queue lag)]
owner_agent: backend-engineer
---

# STORY-043 — Raw message audit trail and DB-backed job queue with SKIP LOCKED workers

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Ingestion is asynchronous and durable: a message is persisted first, then processed by workers that survive restarts (PROMPT §6, §2).

## Requirements and acceptance criteria
### REQ-PARSE-030 — Every input becomes an immutable raw_message row before any processing
Given an uploaded SMS, when the endpoint answers 202, then a `raw_message` row exists with status QUEUED before any processing, and an unparsed message stays NEEDS_REVIEW instead of being dropped.

### REQ-PARSE-031 — Jobs are claimed with FOR UPDATE SKIP LOCKED by N workers, exactly-once per attempt, with lag metric
Given 40 queued messages and 4 concurrent workers, when they drain the queue with FOR UPDATE SKIP LOCKED, then each message is processed exactly once. The lag metric is not built yet (follow-up).

### REQ-PARSE-032 — Retry with exponential backoff, max attempts, dead-letter status with error and admin visibility
Given a failing attempt, when it is retried, then it backs off exponentially (10 s x 2^n, max 15 min) and after 5 attempts ends as FAILED with an error code. Admin visibility is not built yet (follow-up).


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`ProcessingStatus` enum in v0; `RawMessageSummary.processingStatus` exposed on expenses.

## Data / migration impact
Uses `raw_message`, `job` (V4); add indexes/columns by new migration if needed.

## Security / privacy / LLM-safety impact
raw_message.text/payload are sensitive: never logged (REQ-OPS-030), encryption at rest in STORY-122.

## Eval impact
None (infrastructure); latency budgets S9 (text p95 < 10 s) measured here.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-005, STORY-003

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (reserved, to be written by /spec)
- [x] UX states (UI stories)
- [x] Contract impact (headline)
- [x] Data / migration impact (headline)
- [x] Security / privacy / LLM-safety impact (headline)
- [x] Eval impact (headline)
- [ ] Size ≤ 400 changed lines (estimate M; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 3 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
