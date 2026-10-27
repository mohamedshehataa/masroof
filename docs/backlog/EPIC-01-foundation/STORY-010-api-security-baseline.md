---
id: STORY-010
epic: EPIC-01
title: API security baseline — JWT filter, household scoping, CORS, headers, Problem+JSON errors
phase: 1
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-009]
reqs: [REQ-SEC-010, REQ-SEC-011, REQ-SEC-012, REQ-SEC-013, REQ-SEC-051]
implements: ["PROMPT.md §9 (everything scoped to the household)", "PROMPT.md §12 (HTTPS, HSTS, CORS, rate limiting)"]
owner_agent: backend-engineer
---

# STORY-010 — API security baseline

## Goal
Every `/api/*` call is authenticated, scoped to the caller's household, answered with RFC 9457 problems on error, and protected by CORS and transport headers (PROMPT §9, §12), so CRUD stories inherit these guarantees instead of re-implementing them.

## Requirements and acceptance criteria
### REQ-SEC-010 — CORS locked to configured origins
**Given** `CORS_ALLOWED_ORIGINS=https://app.example.com,http://localhost:5173` **When** a preflight `OPTIONS /api/expenses` arrives with `Origin: https://evil.example` **Then** no `Access-Control-Allow-Origin` header is returned and the browser call fails; **When** the origin is `http://localhost:5173` **Then** the header echoes it with `Vary: Origin`, allowed methods `GET,POST,PATCH,DELETE` and `Authorization` header.
Unhappy path: **Given** `CORS_ALLOWED_ORIGINS` contains `*` with `SPRING_PROFILES_ACTIVE=prod` **Then** the application refuses to start with a clear message.

### REQ-SEC-011 — Security headers
**Given** any response from the backend **Then** it carries `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` on `/api/*` and `/auth/*`, and `Content-Security-Policy: default-src 'none'` on JSON responses; **Given** the Caddy config **Then** HTTPS redirect and `Strict-Transport-Security: max-age=31536000; includeSubDomains` are set for the public domain (compose integration test over `http://localhost` asserts the header is absent on plain HTTP and present when `DOMAIN` is set).
Unhappy path: **Given** a request with `X-Forwarded-Proto: https` from a non-trusted proxy **Then** it is not treated as HTTPS (only Caddy's network is trusted).

### REQ-SEC-012 — Household scoping everywhere
**Given** a valid access token for member M of household H **When** any `/api/*` request references a resource id of household H2 (path or body) **Then** `404 application/problem+json` (not 403; no existence oracle); **Given** no/invalid/expired token **Then** `401` with `WWW-Authenticate: Bearer`; **Given** `/health` **Then** no token is required.
**Given** the ArchUnit suite **Then** a rule asserts every repository query method in `adapters/out/persistence` that returns household data takes `householdId` as a parameter (or is annotated `@HouseholdScoped`), failing the build otherwise.
Unhappy path: **Given** a token whose `hh` claim does not match the member's current household (member removed) **Then** `401`.

### REQ-SEC-013 — Problem+JSON for every error
**Given** any 4xx/5xx **Then** the body is `application/problem+json` `Problem {type, title, status, detail, instance, traceId, errors[]?}`; bean-validation failures return `422` with `errors[{field, message, rejectedValue?}]` where `rejectedValue` is omitted for fields named `password`, `token`, `body`, `text`; unexpected exceptions return `500` with a generic `detail` and the `traceId` that appears in the logs.
Unhappy path: **Given** malformed JSON in a request body **Then** `400` Problem `title: "Malformed request"` without echoing the raw body.

### REQ-SEC-051 — Rate limiting of unauthenticated endpoints (incl. /health)
**Given** the backend behind Caddy **When** one client IP sends 60 requests to `GET /health` within 60 s **Then** all 60 are answered normally; **when** the 61st arrives inside the same 60 s window **Then** it gets `429 application/problem+json` (`Problem` with `status: 429`, `title: "Too Many Requests"`) and a `Retry-After` header holding an integer number of seconds >= 1 that does not exceed the time left in the window; a request from a second client IP in the same window still gets `200`; after the window ends the first IP gets `200` again. The same per-IP limit (60 per 60 s, configurable via `application.yml`/`.env`) applies to every other unauthenticated endpoint that has no stricter limit of its own (`/auth/login` keeps STORY-009's lockout, invite acceptance keeps STORY-011's 20/min).
**Given** a request **Then** the client IP is taken from `X-Forwarded-For` only when the request comes from the trusted Caddy network; otherwise the socket address is used.
Unhappy path: **Given** a client on an untrusted network that sends a different `X-Forwarded-For` value on each request **When** it sends 61 requests in 60 s **Then** the 61st still gets `429` (spoofed headers are ignored).
Unhappy path: **Given** the container's own healthcheck calls `/health` from loopback every few seconds **Then** those calls are not counted and never get `429`, so compose never marks the backend unhealthy because of the limiter.
This takes over the rate-limit criterion removed from the walking skeleton (originally STORY-002, now STORY-143's `/health`; spec review 2026-10-23); the `429` response on `/health` already exists in the contract.

## UX states (UI stories only)
N/A.

## Contract impact
`Problem` schema and `Unauthorized/Forbidden/NotFound/UnprocessableEntity` responses already in v0; add `traceId` property to `Problem` (non-breaking, optional). `429` (`TooManyRequests`) already exists on `/health`; add it to any other unauthenticated operation that lacks it (non-breaking).

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
This story *is* the baseline (ASVS V4 access control, V13 API, V14 configuration), including rate limiting of every unauthenticated endpoint such as `/health` (trust boundary internet -> Caddy -> backend; REQ-SEC-051). Triggers `security-reviewer`. Threat notes go in `docs/security/THREAT_MODEL.md`.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-009.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (REQ-SEC-051 adds ≈ 60 production + ≈ 60 test lines; if the design exceeds 400, the architect splits REQ-SEC-051 into its own story)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — REQ-SEC-051 (rate limiting incl. /health) moved here from STORY-002 |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
