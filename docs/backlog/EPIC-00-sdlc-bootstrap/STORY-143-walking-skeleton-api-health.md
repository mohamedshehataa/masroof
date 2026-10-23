---
id: STORY-143
epic: EPIC-00
title: "Walking skeleton (API): GET /health + backend Dockerfile + compose postgres+backend"
phase: 0
status: READY
size: M
modules: [backend, infra, contracts]
depends_on: [STORY-002]
reqs: [REQ-OPS-020]
implements: ["PROMPT.md S15 item 7", "PROMPT.md §2 (stack, compose)", "PROMPT.md §9 (contract-first)", "PROMPT.md §12 (no leakage, non-root)", "PROMPT.md S9 (image hardening)"]
owner_agent: backend-engineer
---

# STORY-143 — Walking skeleton (API): GET /health + backend Dockerfile + compose postgres+backend

## Goal
The first HTTP slice of the S15 item 7 walking skeleton: the gated backend module from STORY-002 answers `GET /health` from a container talking to Postgres, so the web page (STORY-141) and the full `make up` stack (STORY-142) complete the path **browser → Caddy → (web bundle | backend → Postgres)**.

Split decision (spec review 2026-10-23, orchestrator): the original STORY-002 became STORY-002 (module, gate, V1, ArchUnit), STORY-143 (this story), STORY-141 (web) and STORY-142 (stack). Rate limiting of `/health` moved to STORY-010 (REQ-SEC-051).

## Requirements and acceptance criteria
### REQ-OPS-020 — GET /health returns build information
**Given** the backend and Postgres are running (`GIT_SHA=$(git rev-parse --short=7 HEAD) docker compose --env-file .env -f infra/docker-compose.yml <dev overlay> up -d --build --wait postgres backend`, backend published on `127.0.0.1:8080` by a dev-only overlay; the architect names it) **When** `GET http://127.0.0.1:8080/health` is called with no `Authorization` header **Then** the response is `200` with `Content-Type: application/json` and a body valid against the contract schema `Health` where:
- `status == "UP"`;
- `version` equals the `version` property of `backend/build.gradle.kts` (initially `0.1.0`) and matches `^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$`;
- `gitSha` equals the output of `git rev-parse --short=7 HEAD` at image build time (passed as the docker build arg `GIT_SHA`) and matches `^[0-9a-f]{7}$`;
- `buildTime` and `time` are RFC 3339 timestamps ending in `Z`; `time` is within ±5 s of the test's clock; `buildTime <= time`;
- `checks` is an object with exactly one key, `db`, whose value is `"UP"`.

**Given** the same running backend **When** `GET /health` is sent with `Accept: application/xml` (or `Accept: text/html`) **Then** the response is still `200 application/json` with the same body shape (the `Accept` header is ignored; never `406`).
**Given** `docker compose ps` **Then** `postgres` and `backend` both define a `healthcheck` and report `healthy`; the backend container runs as a non-root user (`docker inspect` `Config.User` set and neither `root` nor `0`).

Unhappy path (build provenance): **Given** the docker build arg `GIT_SHA` is missing or empty **When** the backend image is built **Then** the build fails with a message naming `GIT_SHA` and no image is produced (the value is never `"unknown"` or empty).

Unhappy path (DB down): **Given** the backend is running and Postgres is then stopped (`docker compose stop postgres`, or the Testcontainers container is stopped) **When** `GET /health` is called **Then** the response arrives within 3 s and is `503` with `Content-Type: application/problem+json` and a body equal to (member order irrelevant):
```json
{"type":"about:blank","title":"Service Unavailable","status":503,"detail":"Database unavailable","instance":"/health"}
```
(STORY-010 may later add an optional `traceId` member; no other members are allowed in this story.) Neither the body nor any response header matches the case-insensitive pattern `jdbc:|postgres(ql)?://|password|exception|hikari|:5432|\.java:|\tat ` (no connection strings, credentials, hostnames-with-ports or stack traces).

Unhappy path (recovery): **Given** the DB-down state above **When** Postgres is started again **Then** within 30 s `GET /health` returns `200` with `checks.db == "UP"`, without the backend being restarted (container `RestartCount` and the JVM start time are unchanged).

Unhappy path (DB unreachable at startup): **Given** Postgres is unreachable when the backend container starts **When** the backend boots **Then** the process exits with a non-zero code within 60 s, compose restarts it (`restart` policy), and once Postgres is healthy the backend reports `healthy` within 90 s with no manual action.

Unhappy path (input): **Given** `GET /health?verbose=true&x=<2,000-character string>` **Then** the response is the same `200` body as without the query (unknown parameters are ignored, nothing is echoed).

## UX states (UI stories only)
N/A — no UI in this story (the version page is STORY-141).

## Contract impact
`GET /health`, `Health` and `Problem` already exist in `contracts/openapi.yaml` v0 and `x-req` is already `REQ-OPS-020` — no change needed. The architect rewrites the `200` description to "Service and database are up." (drop "or degraded, reported as status DOWN": a down database is the `503` Problem) and may note in `Health.checks` that only `db` exists until later stories add keys — both description-only, non-breaking (oasdiff clean). No `406` response is added (Accept is ignored). The existing `429` response stays; it is implemented by STORY-010 (REQ-SEC-051).
Client code: the backend controller is **hand-written** in this story; STORY-006 switches it to implement the generated `HealthApi` interface without behaviour change.

## Data / migration impact
None (uses the V1 baseline from STORY-002; the health check runs a read-only probe, no writes).

## Security / privacy / LLM-safety impact
Trust boundary: **internet → Caddy → backend `/health`** (Caddy and its routing arrive in STORY-142; in this story the backend is reached only on `127.0.0.1:8080` through the dev overlay).
- `/health` is unauthenticated by design. Disclosure of `version`, `gitSha` and `buildTime` is **accepted** (owner's private two-person deployment; orchestrator decision 12); no hostnames, connection strings, credentials or stack traces are disclosed (DB-down pattern check above).
- Actuator endpoints are internal only: Caddy never routes `/actuator/*` (asserted in STORY-142 REQ-OPS-022 and STORY-003 REQ-OPS-031).
- Rate limiting of `/health` and every other unauthenticated endpoint is owned by STORY-010 (REQ-SEC-051), not this story.
- Non-root backend image; `.env` stays gitignored; `GIT_SHA` is a build arg, not a secret.
- No household data, so no IDOR surface; no LLM involvement.

## Eval impact
None (no parsing).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-002 (gated backend module, V1 baseline). Unblocks STORY-141 (its Vite dev proxy needs a running `/health` on `localhost:8080`), STORY-142 (full stack), STORY-006 (switches this controller to the generated interface).

## Size estimate (orchestrator decision 13 — honest count)
Hand-written lines, generated scaffolding excluded: production + config ≈ 230 (health use case + DB probe + controller + 503 Problem ≈ 150, Gradle build-info/`GIT_SHA` wiring ≈ 15, Dockerfile ≈ 25, compose `postgres`+`backend` + dev overlay ≈ 40); tests ≈ 170 (controller tests incl. Accept/leakage/query ≈ 70, Testcontainers DB-down + recovery ≈ 60, build-info/provenance ≈ 20, compose startup-resilience check ≈ 20). **Total ≈ 400, at the limit** — if the architect's design exceeds it, the compose startup-resilience criterion moves to STORY-142 (architect reports BLOCKED with the split).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 400 estimated, at the limit; generated scaffolding excluded)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — split out of STORY-002 (orchestrator size decision, option b); REQ-OPS-020 moved here |
