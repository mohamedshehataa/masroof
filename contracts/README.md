# `contracts/` — the API source of truth

`openapi.yaml` (OpenAPI 3.1, currently `info.version: 0.1.0`) **is** the Masroof API. Nothing about
the HTTP surface exists until it is written here first.

## Contract-first, concretely

1. A story that changes the API starts by editing `contracts/openapi.yaml` (the `api-designer` /
   `architect` role does this, before any test or implementation).
2. The build generates from the contract:
   - Spring server interfaces for `backend/` (openapi-generator `spring` with `interfaceOnly=true`,
     `useSpringBoot3=true`, `useTags=true`). Controllers *implement* the generated interfaces.
   - The TypeScript client for `web/` (`typescript-fetch`), consumed by TanStack Query hooks.
   - The Kotlin client for `android/` (`kotlin` with `library=jvm-retrofit2`, `serializationLibrary=kotlinx_serialization`).
3. **Generated code is never hand-edited.** Generated sources live under `build/`/`generated/`
   directories that are git-ignored and regenerated on every build. If the generated output is wrong,
   fix the contract (or the generator template), never the output. `scripts/quality/protect-files.sh`
   blocks writes to generated directories.
4. `docs/API.md` is rendered from this file (`npx --yes @redocly/cli@2 build-docs`), not written by hand.
5. A PR may only merge if the contract **lints clean** and **`oasdiff` reports no breaking change**,
   or the breaking change is explicitly accepted (see below).

Rationale and alternatives considered: `docs/adr/0001-contract-first-openapi.md`.

## Conventions that generators and clients rely on

| Topic | Rule |
|---|---|
| Money | Always the `Money` schema: `{ amountMinor: int64, currency: ISO-4217 }`. No `number` anywhere a monetary value appears. FX rates and quantities are `DecimalString`. Costs are integer micro-USD. Percentages are integer basis points (`…Bps`). |
| Errors | Every 4xx/5xx body is `application/problem+json` shaped like `Problem` (RFC 9457). Reuse `#/components/responses/*`. |
| Pagination | `{ items: [...], nextCursor: string \| null }` with `cursor` + `limit` query params. |
| IDs / time | `format: uuid`; `date-time` is RFC 3339 UTC; `date` is a calendar day in the household timezone. |
| Nullability | OpenAPI 3.1 style: `type: [string, 'null']` (or `oneOf` with `type: 'null'` for `$ref`s). Never the 3.0 `nullable:` keyword. |
| Naming | JSON properties `camelCase`; `operationId` is a unique `camelCase` verb phrase; paths are `kebab-case`. |
| Security | Default `bearerJwt`. Device ingestion (`/api/ingest/*`) uses `deviceToken`. Public endpoints declare `security: []` explicitly. |
| Traceability | `x-req: [REQ-AREA-NNN]` on operations where the mapping is obvious (S11). |
| YAML gotchas | Plain scalars cannot *start* with a backtick and cannot contain `: ` — quote the string or use a `\|` block. |

## Lint

```bash
npx --yes @redocly/cli@2 lint --config contracts/redocly.yaml contracts/openapi.yaml
```

The ruleset is `recommended` plus the tightenings in `contracts/redocly.yaml`. The result must be
"valid" with **zero errors**. The only rule turned off is `no-server-example.com`, because the first
server is intentionally `http://localhost` (the `make up` stack) so generated clients default to
it in development. Node 24 is required; if the network blocks `npx`, install `@redocly/cli@2` once
into `web/node_modules` and run `web/node_modules/.bin/redocly lint --config contracts/redocly.yaml contracts/openapi.yaml`.

Also run `make check` which includes `contract` (lint + oasdiff) in CI.

## Diff / breaking-change gate

```bash
scripts/quality/oasdiff.sh <base-ref>          # e.g. origin/main
```

The script compares `git show <base-ref>:contracts/openapi.yaml` with the working copy and runs
[`oasdiff breaking`](https://github.com/oasdiff/oasdiff). It uses a local `oasdiff` binary when one is
on `PATH`, otherwise `docker run --rm -v … tufin/oasdiff breaking`. Exit codes:

| Exit | Meaning |
|---|---|
| `0` | No breaking changes (or the override below was accepted). |
| `1` | Breaking changes found and not accepted. |
| `2` | Usage error (bad ref, base file missing, neither oasdiff nor docker available). |

Accepting a breaking change requires **both**:

```bash
MASROOF_ALLOW_BREAKING=1 scripts/quality/oasdiff.sh origin/main docs/adr/00NN-<slug>.md
```

- the environment variable `MASROOF_ALLOW_BREAKING=1`, **and**
- the path of an existing ADR (`docs/adr/*.md`) that records why the break is acceptable and how
  clients migrate. In CI the PR must additionally carry the `breaking-change` label.

Removing a response field, narrowing an enum, adding a required request property, renaming an
`operationId` or changing a type are all breaking. Adding optional fields, new endpoints and new
enum values in *request* enums are not.

## Versioning

`info.version` follows SemVer. Patch for docs-only edits, minor for additive changes, major for
accepted breaking changes. The backend reports its build version in `GET /health`.
