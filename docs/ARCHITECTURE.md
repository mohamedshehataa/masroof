# Architecture

Living document. Phase 0 version: boundaries, flows and privacy posture that every story must respect. Module
internals are added by the `architect` agent as stories land (each significant choice has an ADR in `docs/adr/`).

## 1. System context

```mermaid
flowchart LR
  subgraph Members
    M1[Mohamed - Android app]
    M2[Member 2 - iPhone: PWA + WhatsApp + Shortcut]
  end
  WA[(WhatsApp Cloud API)]
  subgraph VPS["Single VPS - docker compose"]
    Caddy[Caddy - HTTPS]
    BE[Backend - Spring Boot]
    PG[(PostgreSQL 16)]
    S3[(MinIO / S3 - receipts, media)]
    OBS[Prometheus / Grafana / OTel collector]
  end
  ANT[(Anthropic API - Claude)]
  STT[(Speech-to-text)]
  FX[(FX rates)]
  FCM[(FCM / Web Push)]

  M1 -- SMS, push notifications, receipts, manual (per-device token) --> Caddy
  M2 -- PWA (JWT) / iOS Shortcut (device token) --> Caddy
  M1 & M2 -- text, photo, PDF, voice --> WA
  WA -- webhook (signed) --> Caddy
  Caddy --> BE
  BE <--> PG
  BE <--> S3
  BE -- structured extraction only, no tools --> ANT
  BE --> STT
  BE --> FX
  BE -- replies, digests, alerts --> WA
  BE -- review needed / budget alerts --> FCM
  BE --> OBS
```

## 2. Ingestion pipeline (one path for every channel)

```mermaid
flowchart TD
  IN[Any input: WhatsApp message, SMS, push, iOS Shortcut, upload, manual] --> RAW[raw_message - immutable audit row, payload encrypted at rest]
  RAW --> JOB[job table - SKIP LOCKED worker]
  JOB --> N[1 Normalize - Unicode, Arabic-Indic digits, separators, multipliers, currency words]
  N --> C[2 Classify - EXPENSE INCOME REFUND TRANSFER CASH_WITHDRAWAL COMMAND OTP PROMO CHITCHAT UNKNOWN]
  C -->|COMMAND| CMD[Command handler - reply]
  C -->|OTP PROMO CHITCHAT| DROP[Stored, ignored]
  C -->|money| X[3 Extract - sms_template rules first, Claude structured output otherwise]
  X --> E[4 Enrich - merchant normalization, merchant_rule memory, FX to EGP]
  E --> D[5 Match / dedup - candidate window, score, merge or POSSIBLE_DUPLICATE]
  D --> P[6 Persist expense + expense_source links - CONFIRMED or PENDING_REVIEW]
  P --> NOTIFY[7 Notify - WhatsApp reply / push / SSE to open apps]
```

Rules that never change: rules before LLM; the LLM receives only delimited data blocks and returns schema-validated
JSON (one repair retry, then `NEEDS_REVIEW`); server-side bounds force `PENDING_REVIEW` for amounts above the ceiling,
INCOME from WhatsApp text, or unknown categories; every call is logged in `ai_call_log` with prompt version and cost.

## 3. Backend layout (hexagonal)

```mermaid
flowchart LR
  subgraph adapters_in[adapters/in]
    REST[REST controllers - generated OpenAPI interfaces]
    WH[WhatsApp webhook]
    SCH[Scheduler - summaries, recurring, FX, backups]
    WRK[Job workers]
  end
  subgraph application
    UC[Use cases]
    PORTS[Ports - interfaces]
  end
  subgraph domain
    MONEY[Money - long minor units]
    EXP[Expense, Period, Member, Category ...]
    RULES[Normalization, classification, dedup scoring]
  end
  subgraph adapters_out[adapters/out]
    JPA[JPA + Flyway]
    ST[S3 storage]
    AI[Anthropic client]
    WAC[WhatsApp Cloud API client]
    FXC[FX client]
    STTC[STT client]
    PUSH[FCM / Web Push]
  end
  adapters_in --> application --> domain
  application --> PORTS
  adapters_out -. implement .-> PORTS
```

Dependency rule (ArchUnit-enforced): domain imports nothing from Spring, JPA, Jackson or HTTP; adapters depend inward
only and never on each other; no `double`/`float` in money, parsing, dedup or analytics types.

## 4. Data flows and privacy

| data | where it lives | protection |
|---|---|---|
| SMS / WhatsApp text, OCR text | `raw_message.text`, `media.ocr_text` (Postgres) | AES-GCM encrypted at rest (`APP_ENCRYPTION_KEY`); never logged above DEBUG; only the parsing-relevant text goes to the LLM, with balances and account numbers stripped where not needed |
| receipt images, PDFs, voice notes | MinIO/S3 bucket | server-side encryption; keys referenced from `media`; perceptual hash for dedup |
| phone numbers, WhatsApp ids | `member`, `raw_message.sender` | masked in logs; anonymized range in fixtures |
| card / account numbers | only last-4 in `payment_account.last4` | full numbers are never stored; masked in prompts |
| OTPs | never leave the phone | dropped on-device by the Android filter (hard test gate) |
| secrets | `.env` on the VPS, GitHub Actions secrets | never in git (gitleaks, scan-secrets hook) |
| backups | encrypted nightly dumps + media sync | weekly automated restore test |

Trust boundaries and STRIDE analysis: `docs/security/THREAT_MODEL.md`.

## 5. Clients

- **Web PWA** (iPhone home screen + desktop): generated TypeScript client, TanStack Query cache, SSE for live updates, IndexedDB outbox for offline manual entries, web push.
- **Android**: generated Kotlin client, Room cache + outbox with backoff, WorkManager for SMS/notification processing, Glance widget, share target, biometric lock.
- Both render analytics computed by the backend (Cairo timezone, household month start day).

## 6. The SDLC around the system

```mermaid
flowchart LR
  S[spec - product-analyst] --> SR[spec-reviewer] --> D[design - architect +security-reviewer] --> T[failing tests - test-designer] --> I[implementer] --> V[qa-verifier - fresh context] --> R[code-reviewer + specialists] --> M[make check, trace, squash-merge]
  V -. FAIL, max 3 loops .-> I
  R -. CHANGES_REQUESTED .-> I
```

Deterministic guardrails: hooks in `.claude/hooks` (tested in `.claude/hooks/tests`), lefthook git hooks, CI workflows in `.github/workflows`, eval harness in `eval/`, traceability in `docs/TRACEABILITY.md`.
