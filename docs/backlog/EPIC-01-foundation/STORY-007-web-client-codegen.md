---
id: STORY-007
epic: EPIC-01
title: TypeScript API client generated from the contract
phase: 1
status: IN_PROGRESS
size: S
modules: [contracts, web]
depends_on: [STORY-141]
reqs: [REQ-OPS-053]
implements: ["PROMPT.md §9 (generated TypeScript client)", "PROMPT.md S9 (contract-first)"]
risk_tier: low
owner_agent: web-engineer
---

# STORY-007 — TypeScript API client generated from the contract

## Goal
The web app talks to the backend only through a typed client generated from `contracts/openapi.yaml`, so contract changes surface as compile errors (PROMPT §9).

## Requirements and acceptance criteria
### REQ-OPS-053 — Generated TypeScript client
**Given** `npm run generate` (also run by `npm run check-fast` and `npm run build`) **When** it runs **Then** `web/src/api/generated/` contains a fetch-based client and types for every operation and schema in the contract, `Money.amountMinor` is typed `number` with a lint rule forbidding arithmetic on it outside `src/lib/money.ts`, and `import` of `fetch` elsewhere in `src/` for `/api/*` URLs fails ESLint (`no-restricted-imports`/custom rule).
**Given** the version page from STORY-141 **Then** the hand-written `web/src/api/health.ts` is deleted, the page imports `getHealth()` from the generated client (keeping runtime validation of the response shape), and STORY-141's REQ-OPS-021 tests — including the schema-invalid `200` case — pass unchanged.
Unhappy path: **Given** a hand edit in `web/src/api/generated/` **Then** `protect-files.sh` blocks it and `npm run generate` reproduces the files byte-for-byte (CI asserts `git diff --exit-code`).
Unhappy path: **Given** the contract has a schema the generator cannot express (e.g. unsupported `type: [string, 'null']`) **Then** `npm run generate` fails with the schema name instead of emitting `any`.

## UX states (UI stories only)
N/A.

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None (build tooling). The client attaches no credentials by itself; auth is added in STORY-018.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-141 (web module and the hand-written `getHealth()` it replaces; STORY-141 depends on STORY-143).

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
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — depends_on re-pointed to STORY-141; switch of the hand-written getHealth() made explicit |
