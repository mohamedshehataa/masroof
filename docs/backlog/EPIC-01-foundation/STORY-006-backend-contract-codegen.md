---
id: STORY-006
epic: EPIC-01
title: Backend server interfaces generated from the OpenAPI contract with lint and breaking-change gates
phase: 1
status: DESCOPED
size: M
modules: [contracts, backend]
depends_on: [STORY-143]
reqs: [REQ-OPS-050, REQ-OPS-051, REQ-OPS-052]
implements: ["PROMPT.md §9 (contract-first)", "PROMPT.md S9 (oasdiff gate)", "PROMPT.md §15 (docs/API.md)"]
risk_tier: low
owner_agent: backend-engineer
---

# STORY-006 — Backend server interfaces generated from the contract

## Goal
`contracts/openapi.yaml` drives the backend: Spring interfaces are generated at build, the contract is linted, and breaking changes fail the gate (PROMPT §9, S9).

## Requirements and acceptance criteria
### REQ-OPS-050 — Spring interfaces generated at build
**Given** `./gradlew checkFast` **When** it runs **Then** `openapi-generator` produces interfaces and models under `backend/build/generated/openapi` (or `src/main/generated`, listed under `**/generated/**`) for every operation in the contract, with `Money` mapped to the domain `Money` type (no `Double`), `date-time` to `Instant`, and the `HealthController` — hand-written in STORY-143 — is switched to implement the generated `HealthApi` with no behaviour change (STORY-143's REQ-OPS-020 tests pass unchanged; compile error if the contract and controller drift).
Unhappy path: **Given** a hand edit inside the generated directory **When** the Edit tool is used **Then** `protect-files.sh` blocks it; **When** the build runs **Then** the directory is regenerated from scratch.

### REQ-OPS-051 — Contract lint and breaking-change gate
**Given** `make contract-lint` **When** it runs **Then** `redocly lint` passes with zero errors against `contracts/redocly.yaml`; **Given** `make contract-diff BASE=main` on a branch that removed `Health.version` **When** it runs **Then** `oasdiff` reports a breaking change and the target exits non-zero; **Given** a branch that only added an optional property **Then** exit 0.
Unhappy path: **Given** the PR carries label `breaking-change` and a new `docs/adr/NNNN-*.md` referencing the path **Then** `make contract-diff` exits 0 with a warning (S9 exception).

### REQ-OPS-052 — docs/API.md generated
**Given** `make docs-api` (invoked by `make check`) **When** it runs **Then** `docs/API.md` is regenerated from the contract (endpoints grouped by tag, request/response schemas, `x-req` ids) and `make check` fails if the committed file is stale (`git diff --exit-code docs/API.md`).
Unhappy path: **Given** an operation without `operationId` **Then** `make contract-lint` fails (rule `operation-operationId`).

## UX states (UI stories only)
N/A.

## Contract impact
Tooling only; `x-req` on `/health` is already `REQ-OPS-020`. No path/schema change.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None (build tooling); generated code is excluded from coverage but included in SAST.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-143 (the hand-written `HealthController` this story switches; it depends on STORY-002).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (generated output excluded)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — HealthController switch from hand-written (STORY-002) made explicit; stale x-req note fixed |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — depends_on re-pointed to STORY-143, which now owns the hand-written HealthController |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
