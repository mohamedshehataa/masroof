# ADR-0001: Contract-first API with OpenAPI 3.1, an oasdiff gate and integer Money

- **Status:** accepted
- **Date:** 2026-10-23
- **Deciders:** architect, backend-engineer, web-engineer, android-engineer
- **Technical story:** Phase 0 step 4 (`docs/PROMPT.md` S15), requirements REQ-EXP-*, REQ-SEC-*, S9 "Contract-first API"

## Context and Problem Statement

Masroof has one backend (Spring Boot) and three independently built consumers: the React PWA, the
Kotlin Android app and the iOS Shortcut/`curl`-style integrations. Two people with different phones
rely on the same ledger, so a field that silently disappears or changes type breaks a device that is
not in front of the developer. Money is the core domain and Egyptian amounts are routinely written
with Arabic-Indic digits, mixed separators and foreign currencies, so any float on the wire would
leak rounding into splits, budgets and settlement.

How do we keep the three clients and the server in agreement, catch breaking changes before they are
merged, and make monetary values unambiguous on the wire?

## Decision Drivers

- Three codebases in three languages consume one API; hand-written DTOs drift.
- S9 requires the API contract to be the source of truth and oasdiff to fail CI on breaking changes.
- S9 ArchUnit/detekt rules forbid `double`/`float` in money types; the wire format must match.
- Reviewers (human and agent) need one file to read to understand the API surface.
- `docs/API.md` must be generated, not maintained by hand.

## Considered Options

1. **Contract-first OpenAPI 3.1** in `contracts/openapi.yaml`; generate server interfaces and clients; gate with oasdiff.
2. **Code-first** with springdoc generating the spec from annotated controllers; clients generated from the runtime spec.
3. **gRPC/Protobuf** with grpc-web and a JSON gateway.
4. Hand-written DTOs in each client, documented in Markdown.

## Decision Outcome

Chosen option: **1. Contract-first OpenAPI 3.1**, because it is the only option where the contract
exists *before* code, can be reviewed as a diff, and feeds all three generators plus the docs.

Concretely:

- `contracts/openapi.yaml` is the single source of truth (`info.version` follows SemVer).
- The Gradle build generates Spring interfaces (`interfaceOnly`), the Vite build generates the
  TypeScript client, and the Android build generates the Kotlin/Retrofit client. **Generated code is
  never hand-edited**; it lives in ignored build directories and `protect-files.sh` blocks writes.
- `npx --yes @redocly/cli@2 lint --config contracts/redocly.yaml contracts/openapi.yaml` must pass with zero errors
  (config in `contracts/redocly.yaml`).
- `scripts/quality/oasdiff.sh <base-ref>` runs `oasdiff breaking` against the base branch and fails on
  any breaking change. The override needs **both** `MASROOF_ALLOW_BREAKING=1` and the path of an ADR
  that records the break and the client migration; in CI the PR must also carry the `breaking-change`
  label.
- **Money is a minor-unit integer**: every monetary value is the `Money` schema
  `{ amountMinor: int64, currency: ISO-4217 }`. There is no `number`-typed money anywhere in the
  contract. FX rates and quantities are exact decimal strings (`DecimalString`), LLM cost is integer
  micro-USD, percentages are integer basis points. This mirrors the domain `Money` value object and
  the ArchUnit/detekt rules, so no float appears in domain, DB, or wire.
- Every 4xx/5xx is RFC 9457 `application/problem+json` (`Problem`), so clients have one error path.
- Lists are cursor-paginated `{ items, nextCursor }`; IDs are UUIDs; timestamps RFC 3339 UTC.

### Consequences

- Good: API changes are reviewable text diffs; breaking changes are impossible to merge by accident;
  the three clients cannot disagree on a field name or type; `docs/API.md` is always current.
- Good: integer money removes a whole class of rounding bugs and makes property tests (shares sum to
  total) trivial to express.
- Bad: a story that touches the API has an extra step (edit the contract first, regenerate), and the
  spec is large (~4k lines) so editing discipline matters (reusable components, `$ref`s).
- Bad: clients must format `amountMinor` for display themselves (a shared `formatMoney` helper in each
  client); amounts in free text (WhatsApp, SMS) still need the normaliser to turn `1,250.50` into
  `125050`.
- Neutral: springdoc may still serve a Swagger UI, but it serves the contract file, not an
  annotation-derived spec.

### Confirmation

- `make check` runs `contract` = redocly lint + `scripts/quality/oasdiff.sh origin/main`.
- `/sdlc-selftest` removes a response field on a throwaway branch and proves oasdiff fails (S15 step 6).
- ArchUnit test `MoneyTypesRule` and detekt `NoFloatMoney` fail on any `double`/`float` in money types.
- Generated-code directories are listed in `scripts/quality/protect-files.sh`.

## Pros and Cons of the Options

### 1. Contract-first OpenAPI 3.1 (chosen)

- Good: reviewable before implementation; one generator ecosystem for Java, TS and Kotlin; oasdiff
  and Redocly tooling are mature; 3.1 supports `type: [string, 'null']` and JSON Schema 2020-12.
- Good: the eval harness's `http` adapter can target a contract-defined `POST /api/parse/dry-run`.
- Bad: generator quirks (e.g. `oneOf` with `null`) occasionally need template tweaks.

### 2. Code-first with springdoc

- Good: no separate file to maintain; annotations sit next to code.
- Bad: the contract only exists after the backend compiles, so the web and Android stories cannot
  start from it; breaking changes are discovered by diffing generated output; annotation noise in
  controllers; spec quality depends on annotation discipline.

### 3. gRPC/Protobuf

- Good: strong typing, `int64` money natively, generated clients.
- Bad: WhatsApp webhooks, the iOS Shortcut and the PWA are JSON/HTTP by nature; grpc-web needs a proxy;
  multipart receipt upload and SSE do not fit; poor fit for a `curl`-friendly self-hosted API.

### 4. Hand-written DTOs + Markdown docs

- Good: zero tooling.
- Bad: three copies of every model drift immediately; no automated breaking-change detection;
  docs rot. Rejected outright.

## More Information

- `contracts/README.md` — how to lint, diff and version the contract.
- `docs/PROMPT.md` §9 (API), S9 (gates), S15 step 4 and 6.
- Breaking-change acceptance procedure: see "Decision Outcome"; the accepting ADR must link the
  migration steps for the PWA, Android and iOS Shortcut.
