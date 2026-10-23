---
id: STORY-002
epic: EPIC-00
title: "Walking skeleton: health endpoint + web page showing backend version via make up"
phase: 0
status: READY
size: M
modules: [backend, web, infra, contracts]
depends_on: [STORY-001]
reqs: [REQ-OPS-020, REQ-OPS-021, REQ-OPS-022, REQ-OPS-023, REQ-OPS-024]
implements: ["PROMPT.md S15 item 7", "PROMPT.md §2 (stack, compose)", "PROMPT.md §9 (contract-first)", "PROMPT.md S9 (ArchUnit)"]
owner_agent: backend-engineer
---

# STORY-002 — Walking skeleton: health endpoint + web page showing backend version via make up

## Goal
One request flows end to end — browser → Caddy → web → backend → Postgres — so every later story lands on a running, gated stack (PROMPT S15 item 7); the story itself exercises the whole S5 pipeline once.

## Requirements and acceptance criteria
### REQ-OPS-020 — GET /health returns build information
**Given** the backend is running with a reachable database **When** `GET /health` is called without authentication **Then** the response is `200 application/json` matching schema `Health`: `status: "UP"`, `version` (semver from the Gradle build), `gitSha` (7–40 hex chars), `buildTime` and `time` (RFC 3339 UTC), and `checks.db: "UP"`.
Unhappy path: **Given** Postgres is stopped **When** `GET /health` is called **Then** the response is `503 application/problem+json` (`Problem` with `type`, `title`, `status: 503`, `detail` not containing connection strings or passwords) and the body also exposes no stack trace.
Unhappy path: **Given** 100 requests/s from one IP for 10 s **When** the limit is exceeded **Then** `429 application/problem+json` with `Retry-After`.

### REQ-OPS-021 — Web page renders the backend version
**Given** the web app is served at `http://localhost/` **When** the root page loads **Then** it fetches `/health` through the generated TypeScript client and renders `Backend <version> (<gitSha>)` with the UI language switchable between `ar` and `en` (strings from i18n resources, `dir="rtl"` for Arabic).
**Given** the fetch is in flight **Then** a loading skeleton is shown (not a blank area); **Given** `/health` returns 503 or the network fails **Then** an error state with a bilingual message and a Retry button is shown and the retry re-issues the request.
Unhappy path: **Given** the response is `200` but fails schema validation (missing `version`) **Then** the error state is shown, and the console has no uncaught exception.

### REQ-OPS-022 — make up brings up the whole stack and smoke passes
**Given** a machine with Docker and a `.env` copied from `.env.example` **When** `make up` runs **Then** within 120 s the compose project has healthy containers `postgres`, `minio`, `backend`, `web`, `caddy`, and `make smoke URL=http://localhost` exits 0 by asserting `GET http://localhost/health` → `200` with `status: UP` and `GET http://localhost/` → `200` containing the app root markup.
Unhappy path: **Given** `.env` is missing **When** `make up` runs **Then** it fails in < 5 s with `copy .env.example to .env` and no container starts.
Unhappy path: **Given** the backend container is unhealthy **When** `make smoke` runs **Then** it exits non-zero and prints the failing URL and status.

### REQ-OPS-023 — Module fast gates exist and are green
**Given** the repo **When** `./gradlew checkFast` runs in `backend/` **Then** it compiles with `-Werror`, runs Spotless check, Checkstyle, Error Prone + NullAway, unit tests and ArchUnit, and exits 0 in < 3 min; **When** `npm run check-fast` runs in `web/` **Then** it runs `tsc --noEmit` (strict, `noUncheckedIndexedAccess`), ESLint (`strict-type-checked`, `jsx-a11y`, `react-hooks`, i18n no-hardcoded-strings), Prettier check and Vitest, exiting 0.
**Given** both **When** `make check-fast` runs at the root **Then** it dispatches to each present module and exits 0.
Unhappy path: **Given** a hard-coded user-facing string `<h1>Hello</h1>` in a component **When** `npm run check-fast` runs **Then** ESLint fails naming the i18n rule.

### REQ-OPS-024 — Baseline migration and architecture rules
**Given** an empty Postgres database **When** the backend starts **Then** Flyway applies `V1__baseline.sql` (schema version table + `household` table with `id uuid`, `name`, `base_currency char(3)`, `timezone` default `Africa/Cairo`, `month_start_day int` 1–28, timestamps) and `flyway_schema_history` shows version 1 success.
**Given** the ArchUnit test suite **When** it runs **Then** rules exist and pass for: `domain` has no Spring/JPA/HTTP imports; `adapters.in` and `adapters.out` do not depend on each other; no `double`/`float` field, parameter or return type anywhere in `domain`/`application` (money rule); no `System.out`; no field injection.
Unhappy path: **Given** a test fixture class with `double amount` placed in the domain package **When** ArchUnit runs **Then** the money rule fails naming the class (this is the S15 self-test probe).

## UX states (UI stories only)
empty: N/A · loading: skeleton for the version line · error: bilingual message + Retry · offline: same as error with "offline" copy · RTL: Arabic flips layout (`dir="rtl"`) · dark: follows `prefers-color-scheme`.

## Contract impact
`contracts/openapi.yaml`: `GET /health` → `Health` schema and `Problem` (both already in v0). The `x-req` on `/health` currently reads `REQ-OPS-001`; the architect must change it to `REQ-OPS-020` (REQ-OPS-001 is the guard-bash requirement). Non-breaking.

## Data / migration impact
New migration `V1__baseline.sql` (expand only: household table). Later schema arrives in STORY-004/005 as new migrations; V1 becomes immutable once applied.

## Security / privacy / LLM-safety impact
`/health` is unauthenticated and rate-limited; it must not leak hostnames, credentials or stack traces (Problem `detail` is generic). Compose runs non-root images; `.env` is gitignored.

## Eval impact
None (no parsing).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-001 (hooks proven first, so this story is the first one run through the full S5 pipeline).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (scaffolding generated by tools is excluded from the count)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
