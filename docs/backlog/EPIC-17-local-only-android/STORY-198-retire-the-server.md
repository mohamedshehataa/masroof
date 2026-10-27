---
id: STORY-198
epic: EPIC-17
title: Retire the server - delete server code, drop the network permission, contract the schema
phase: 10
status: DRAFT
risk_tier: high
size: M
modules: [android, backend, web, infra, contracts, sdlc]
depends_on: [STORY-182, STORY-184]
reqs: [REQ-OPS-182, REQ-OPS-183, REQ-SEC-071]
implements: ["ADR-0009 D7 (retirement steps 1-5)", "ADR-0009 D3 (contract v9 -> v10)"]
owner_agent: devops-engineer
---

# STORY-198 - Retire the server - delete server code, drop the network permission, contract the schema

> **DRAFT stub** - starts only after the owner checkpoint OWNER-015 in docs/ESCALATIONS.md (final `pg_dump` downloaded
> and verified, one-time import used on the owner's phone, VM decision taken). Expected to split into an Android story
> and a repo-cleanup story at /spec. REQ ids reserved.

## Goal
The app has no network permission at all and the repo has no server code to maintain (ADR-0009 D7).

## Requirements and acceptance criteria
### REQ-OPS-182 - Server modules, CI jobs and make targets are removed after tag `server-final`
Given OWNER-015 is resolved, when this story merges, then tag `server-final` points at the last commit with `backend/`,
`web/`, `infra/`, the server docs and `android/api-client`; those paths, their CI jobs and make targets are gone; `make
check` and the PR gate are green with only android + sdlc.

### REQ-OPS-183 - Room v10 contract migration drops the outbox tables and the pending/failed columns
Given a v9 database, when migrated, then `outbox`, `parsed_outbox`, `expense.pending` and `expense.failed` are gone (table
rebuild, SQLite 3.18 has no DROP COLUMN), every other row and value is identical (MigrationTestHelper from v2..v9), and
the v1 destructive fallback is removed; ships only after the import was confirmed on the owner's phone.

### REQ-SEC-071 - The app declares no INTERNET or ACCESS_NETWORK_STATE permission
Given the release APK, when its merged manifest is inspected in CI, then `android.permission.INTERNET` and
`ACCESS_NETWORK_STATE` are absent, no network security config remains, and the auth/session/remote/outbox code and the
Retrofit/OkHttp/openapi-generator dependencies are deleted.

## UX states (UI stories only)
Settings loses the import card and server URL; nothing else visible.

## Contract impact
`contracts/openapi.yaml` is deleted with the server (tagged in `server-final`).

## Data / migration impact
Room v10 contract step (table rebuild). Final production `pg_dump` kept by the owner.

## Security / privacy / LLM-safety impact
Strongest privacy property of the pivot: no network permission. Server secrets (`PROD_*`) removed from GitHub.

## Eval impact
The LLM eval harness and `eval/` become dormant or are removed (decision at /spec).

## Design notes (architect)
ADR-0009 D7.

## Dependencies
STORY-182, STORY-184; owner checkpoint OWNER-015

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (headlines only)
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [ ] Eval impact (dormant vs removed open)
- [ ] Size ≤ 400 changed lines (deletions dominate; split android / repo at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | DRAFT stub - EPIC-17 local-only pivot (ADR-0009 D7), waits on OWNER-015 |
