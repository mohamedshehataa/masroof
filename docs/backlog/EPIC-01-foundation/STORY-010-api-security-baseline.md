---
id: STORY-010
epic: EPIC-01
title: API security baseline — JWT filter, household scoping, CORS, headers, Problem+JSON errors
phase: 1
status: READY
size: M
modules: [backend]
depends_on: [STORY-009]
reqs: [REQ-SEC-010, REQ-SEC-011, REQ-SEC-012, REQ-SEC-013]
implements: ["PROMPT.md §9 (everything scoped to the household)", "PROMPT.md §12 (HTTPS, HSTS, CORS)"]
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
**Given** any response from the backend **Then** it carries `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` on `/api/*` and `/auth/*`, and `Content-Security-Policy: default-src 'none'` on JSON responses; **Given** the Caddy config **Then** HTTPS redirect and `Strict-Transport-Security: max-age=31536000; includeSubDomains` are set for the public domain (compose integration test over `http://localhost` asserts the header is absent on plain HTTP and present when `MASROOF_DOMAIN` is set).
Unhappy path: **Given** a request with `X-Forwarded-Proto: https` from a non-trusted proxy **Then** it is not treated as HTTPS (only Caddy's network is trusted).

### REQ-SEC-012 — Household scoping everywhere
**Given** a valid access token for member M of household H **When** any `/api/*` request references a resource id of household H2 (path or body) **Then** `404 application/problem+json` (not 403; no existence oracle); **Given** no/invalid/expired token **Then** `401` with `WWW-Authenticate: Bearer`; **Given** `/health` **Then** no token is required.
**Given** the ArchUnit suite **Then** a rule asserts every repository query method in `adapters/out/persistence` that returns household data takes `householdId` as a parameter (or is annotated `@HouseholdScoped`), failing the build otherwise.
Unhappy path: **Given** a token whose `hh` claim does not match the member's current household (member removed) **Then** `401`.

### REQ-SEC-013 — Problem+JSON for every error
**Given** any 4xx/5xx **Then** the body is `application/problem+json` `Problem {type, title, status, detail, instance, traceId, errors[]?}`; bean-validation failures return `422` with `errors[{field, message, rejectedValue?}]` where `rejectedValue` is omitted for fields named `password`, `token`, `body`, `text`; unexpected exceptions return `500` with a generic `detail` and the `traceId` that appears in the logs.
Unhappy path: **Given** malformed JSON in a request body **Then** `400` Problem `title: "Malformed request"` without echoing the raw body.

## UX states (UI stories only)
N/A.

## Contract impact
`Problem` schema and `Unauthorized/Forbidden/NotFound/UnprocessableEntity` responses already in v0; add `traceId` property to `Problem` (non-breaking, optional).

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
This story *is* the baseline (ASVS V4 access control, V13 API, V14 configuration). Triggers `security-reviewer`. Threat notes go in `docs/security/THREAT_MODEL.md`.

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
- [x] Size ≤ 400 changed lines
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
