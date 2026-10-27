---
id: STORY-038
epic: EPIC-04
title: Android offline-first — Room cache for lists and an outbox that syncs manual entries
phase: 2
status: DESCOPED
size: M
modules: [android]
depends_on: [STORY-034, STORY-035, STORY-036]
reqs: [REQ-AND-015]
implements: ["PROMPT.md §11 Android-specific (offline-first with Room cache and outbox sync)", "PROMPT.md §2 (Room offline cache + outbox, WorkManager)"]
owner_agent: android-engineer
---

# STORY-038 — Android offline-first cache and outbox

## Goal
The app opens and is useful without a network, and an expense saved offline reaches the backend exactly once when connectivity returns (PROMPT §11 Android-specific). The SMS outbox of Phase 4 reuses this mechanism.

## Requirements and acceptance criteria
### REQ-AND-015 — Room cache and outbox sync
**Given** Home/Transactions/categories/accounts/members were loaded once **When** the app is opened offline **Then** the screens render from Room (`expense`, `category`, `payment_account`, `member`, `summary` tables) with an "Updated <relative time>" label; the cache is refreshed on every successful fetch (single source of truth = Room, UI observes `Flow`s).
**Given** Quick add is saved while offline (or the request fails with a network error) **Then** a row is inserted in `outbox` (`id, kind = CREATE_EXPENSE, payload json, idempotency_key, attempts, next_attempt_at`), the entry appears immediately in lists with a "pending sync" icon, and a WorkManager unique periodic + network-constrained job posts outbox rows in order with the stored `Idempotency-Key`, deleting them on `201` and replacing the local temp id with the server id.
**Given** Kover **Then** ≥ 80% coverage on the outbox/sync code.
Unhappy path: **Given** `422` from the server **Then** the row is marked `FAILED` with the Problem title, shown in Transactions with a red icon and an "Edit / Discard" action (never silently dropped); **Given** `5xx`/timeout **Then** exponential backoff (30 s → 1 h cap, max 20 attempts, then `FAILED`); **Given** the same outbox row is processed twice because of a worker restart **Then** the backend's idempotency (REQ-EXP-020) yields one expense; **Given** Log out **Then** cache and outbox are wiped after confirming "You have N unsynced entries".

## UX states (UI stories only)
empty: N/A · loading: N/A · error: `FAILED` row state · offline: banner + pending icons · RTL: icons mirrored where directional · dark: theme.

## Contract impact
None (uses `Idempotency-Key` from STORY-020).

## Data / migration impact
Room schema v1 with exported schema JSON committed for migration tests (`android/app/schemas/`).

## Security / privacy / LLM-safety impact
Room DB is app-private; no raw SMS bodies stored yet (Phase 4 defines retention for the SMS outbox); cache wiped on logout. `allowBackup=false` keeps it out of cloud backups.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-034, STORY-035, STORY-036.

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
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
