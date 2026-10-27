---
id: STORY-003
epic: EPIC-01
title: Observability stack in compose with redacted structured logs
phase: 1
status: DESCOPED
size: M
modules: [infra, backend]
depends_on: [STORY-142]
reqs: [REQ-OPS-030, REQ-OPS-031, REQ-OPS-032]
implements: ["PROMPT.md §14 Phase 1 (observability stack)", "PROMPT.md S13", "PROMPT.md §12 (never log bodies)"]
owner_agent: devops-engineer
---

# STORY-003 — Observability stack in compose with redacted structured logs

## Goal
`make up` also starts Prometheus, Grafana and an OpenTelemetry collector, and the backend emits structured, redacted logs, metrics and traces so every later story is observable (PROMPT §14 Phase 1, S13).

## Requirements and acceptance criteria
### REQ-OPS-030 — Structured JSON logs with redaction
**Given** the backend runs with the default profile **When** any request is handled **Then** each log line is one JSON object with `timestamp`, `level`, `logger`, `message`, `traceId`, `spanId` and, when authenticated, `householdId`; no line contains a field named `body`, `text`, `ocrText`, `password`, `token` or `authorization`.
**Given** a log statement passes a map containing key `body` or a string matching `Bearer [A-Za-z0-9._-]+` **When** it is written at INFO or above **Then** the value is replaced by `[REDACTED]`.
Unhappy path: **Given** a logger call at DEBUG with a message body in a test **When** the log level is INFO (default in compose) **Then** the line is not emitted at all; semgrep rule `no-log-body` fails `make check` if source logs a `body`/`text`/`ocr` field above DEBUG.

### REQ-OPS-031 — Metrics endpoint and dashboards
**Given** `make up` **When** the stack is healthy **Then** `GET http://backend:8080/actuator/prometheus` on the internal compose network (the host port 8080 is not published under `make up`, STORY-142; not routed by Caddy) exposes Micrometer metrics including `http_server_requests_seconds` and `jvm_memory_used_bytes`, Prometheus scrapes it every 15 s, and Grafana (provisioned from `infra/grafana/`) shows a "Masroof API" dashboard with request rate, p95 latency and error rate panels.
Unhappy path: **Given** a request to `http://localhost/actuator/prometheus` through Caddy (plain HTTP while `DOMAIN` is empty, STORY-142) **Then** it is not answered by the backend (`404`/`403`, or the web container's `text/html`; never Prometheus text) — actuator is never public.

### REQ-OPS-032 — Traces exported
**Given** OpenTelemetry is configured **When** `GET /health` is called **Then** a trace with a server span `GET /health` and a child DB span reaches the collector (verified via the collector's debug exporter in the integration test / compose logs), and the same `traceId` appears in the request's log lines.
Unhappy path: **Given** the collector is down **When** requests are served **Then** the backend still answers `200` and logs one WARN about export failure at most every 60 s.

## UX states (UI stories only)
N/A.

## Contract impact
None (actuator is not part of `openapi.yaml`).

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Redaction is a privacy control for §12 ("never log full message bodies"); actuator endpoints stay on the internal compose network; Grafana default admin password comes from `.env`.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-142 (the `make up` stack with Caddy; it depends on STORY-143 and STORY-141).

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
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — depends_on re-pointed to STORY-142 after the STORY-002 split; Caddy URL is plain HTTP locally |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
