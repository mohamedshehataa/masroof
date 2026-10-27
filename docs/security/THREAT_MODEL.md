# Threat model — Masroof

Owner: `security-reviewer` (upkeep at every story that touches auth, ingestion, PII or LLM prompts; S2 stage 3).
Method: STRIDE per trust boundary. Status values: `Planned` (REQ defined, no story yet), `In progress (STORY-xxx)`, `Done (STORY-xxx)`, `Accepted risk (ADR-xxxx)`.
Last reviewed: Phase 0 (skeleton). Checklists that reference these REQ IDs: `ASVS_CHECKLIST.md`, `MASVS_CHECKLIST.md`, `LLM_SECURITY.md`.

## 1. System and trust boundaries

```mermaid
flowchart LR
  subgraph Internet
    WA[WhatsApp users / Meta webhook]
    AND[Android app<br/>SMS + push ingestion]
    IOS[iOS Shortcut]
    PWA[PWA browser]
    ADMIN[Admin laptop]
  end
  subgraph VPS["VPS (docker compose)"]
    CADDY[Caddy TLS]
    BE[Backend Spring Boot]
    PG[(PostgreSQL)]
    MINIO[(MinIO media)]
  end
  subgraph External
    ANTH[Anthropic API]
    META[WhatsApp Cloud API send]
    STT[Speech-to-text]
    FX[FX rates]
  end
  WA -- B2 webhook --> CADDY
  AND -- B3 /api/ingest --> CADDY
  IOS -- B4 /api/ingest/sms --> CADDY
  PWA -- B5 /api --> CADDY
  CADDY -- B1 --> BE
  BE -- B6 --> ANTH
  BE -- B7 --> PG
  BE -- B7 --> MINIO
  BE -- B8 --> META
  BE -- B6b --> STT
  BE -- B6c --> FX
  ADMIN -- B9 ssh --> VPS
```

| Id | Boundary | Protocol / auth |
|---|---|---|
| B1 | Internet -> Caddy -> backend | HTTPS (Caddy ACME), HSTS; backend only reachable on the compose network |
| B2 | Meta webhook -> backend `/webhooks/whatsapp` | HTTPS; `X-Hub-Signature-256` HMAC with `WHATSAPP_APP_SECRET`; GET handshake with `WHATSAPP_VERIFY_TOKEN` |
| B3 | Android app -> `/api/ingest/*`, `/api/*` | HTTPS + certificate pinning; per-device ingestion token (hashed in DB) + member JWT |
| B4 | iOS Shortcut -> `/api/ingest/sms`, `/api/ingest/receipt` | HTTPS; per-device token header; no interactive auth possible |
| B5 | PWA -> `/api/*` | HTTPS; short-lived JWT access + rotating refresh token; CORS locked to `WEB_BASE_URL` |
| B6 | backend -> Anthropic API (and B6b STT, B6c FX) | HTTPS, API key from env; outbound only |
| B7 | backend -> PostgreSQL / MinIO | compose-internal network; DB user with least privilege; MinIO access key per bucket. JDBC to Postgres must use `sslmode=verify-full` with `channelBinding=require` (SCRAM-SHA-256) once the DB is not on a private socket/network; the Flyway migration user is a separate, DDL-capable role used only at deploy time, the runtime role has no DDL (STORY-002 uses one local dev user) |
| B8 | backend -> WhatsApp Cloud API (send) | HTTPS, permanent system-user token; only to the household group / member numbers |
| B9 | admin -> VPS | SSH key only; `/release` skill + `MASROOF_RELEASE_ACTIVE` flag; CI deploy user |

## 2. Assets

| Asset | Sensitivity | Where | Protection (REQ) |
|---|---|---|---|
| Raw bank SMS / WhatsApp texts / OCR text (`raw_message`) | High (financial PII, balances, last-4) | Postgres | REQ-SEC-005, -006, -007, -009 |
| Receipt images and voice notes | High | MinIO | REQ-SEC-008 |
| Expenses, budgets, analytics | Medium (household finances) | Postgres | REQ-SEC-023, -007 |
| Member phone numbers, device ids | Medium (PII) | Postgres | REQ-SEC-006, -025 |
| Secrets: `APP_ENCRYPTION_KEY`, `JWT_SECRET`, `WHATSAPP_*`, `ANTHROPIC_API_KEY`, DB/MinIO creds | Critical | VPS `.env`, GitHub secrets | REQ-SEC-021, -027, -013 (gitleaks) |
| Per-device ingestion tokens | High (lets anyone inject transactions) | hashed in Postgres; plaintext only on device | REQ-SEC-003, -014 |
| Android release keystore, cosign key | Critical (supply chain) | GitHub secrets + owner's password manager | REQ-SEC-028 |
| Backups | High | encrypted in `BACKUP_S3_BUCKET` | REQ-SEC-012 |
| LLM prompts and eval datasets | Low-Medium (anonymized) | repo | S10, anonymize-fixtures.py |

## 3. STRIDE per boundary

Legend: **S**poofing, **T**ampering, **R**epudiation, **I**nformation disclosure, **D**enial of service, **E**levation of privilege.

### B1 Internet -> Caddy -> backend

| STRIDE | Threat | Mitigation | REQ | Status |
|---|---|---|---|---|
| S | Phishing clone of the PWA domain | HSTS preload, only one canonical domain, PWA installed from it | REQ-SEC-001 | Planned |
| T | TLS downgrade / MITM | TLS 1.2+ only, Caddy defaults, HSTS `max-age>=1y` | REQ-SEC-001 | Planned |
| I | Verbose errors, stack traces, server headers | Problem+JSON errors without internals; Caddy strips `Server`; security headers (CSP, X-Content-Type-Options, Referrer-Policy) | REQ-SEC-001 | Planned |
| D | Request floods, slowloris, oversized bodies | Caddy rate limit per IP on `/api/ingest/*` and `/webhooks/*`; body size limits (text 64 KB, media 10 MB); timeouts | REQ-SEC-004 | Planned |
| E | Direct access to backend/Postgres/MinIO ports | Only Caddy publishes ports; `ufw` default deny; compose internal network | REQ-SEC-020, REQ-SEC-021 | Planned |
| S | Any container on the compose network can send `X-Forwarded-For` to the backend directly and pick the rate-limit key / audit identity | X-Forwarded-For is trusted only from Caddy's pinned /32 (`172.28.0.10/32`), so the internet cannot spoof it; the network's `ip_range` keeps dynamic container addresses off that pinned address. **Residual (accepted in v1):** an operator override of `MASROOF_TRUSTED_PROXIES` to a broad CIDR (now refused at startup under profile prod when broader than /16 IPv4 or /64 IPv6), and a compromised container on the same network, which can still spoof XFF (the network holds only our own images). IPv6 via docker-proxy shares the gateway key if an AAAA record is published, so none is (docs/HOSTING.md) | REQ-SEC-051 | Accepted risk |

### B1a compose network / loopback -> backend `GET /health` (STORY-143)

In STORY-143 the endpoint is reachable only from the compose network and, through the dev-only overlay, on
`127.0.0.1:8080`. The base compose publishes no port. Internet exposure through Caddy arrives with STORY-142.

| STRIDE | Threat | Mitigation | REQ | Status |
|---|---|---|---|---|
| S | Anyone calls `/health` | Unauthenticated by design; no state, no household data | — | Accepted (DECISIONS 2026-10-23) |
| T | Query parameters or headers change the response (reflection, content-type confusion) | Nothing is bound from the request; Content-Type is preset, so Accept is ignored; tests cover a 2,000-character query and XML/HTML Accept | REQ-SEC-001 | In progress (STORY-143) |
| I | `version`, `gitSha`, `buildTime` disclosed | Accepted for the owner's private deployment | — | Accepted (DECISIONS 2026-10-23) |
| I | DB-down response leaks a JDBC URL, host:port, credentials or a stack trace | Fixed Problem body (`Database unavailable`); the probe catches everything and logs only the exception class name; tests apply the leak regex to body and headers | REQ-SEC-001 | In progress (STORY-143) |
| D | `/health` floods turn into DB load, or hang threads while Postgres is unreachable | One `isValid` per call under a 2 s hard deadline; Hikari pool (10), `connection-timeout` 2 s, pgjdbc `connectTimeout` 2 s; per-IP rate limit (healthcheck exempt) | REQ-SEC-051 | In progress (STORY-143: bounds); Planned (STORY-010: rate limit) |
| E | Container compromise escalates, or actuator exposes internals | Non-root uid 10001, `read_only` root FS + `/tmp` tmpfs, `cap_drop: ALL`, `no-new-privileges`; actuator not on the classpath (STORY-003 adds it, internal only) | REQ-SEC-020 | In progress (STORY-143) |

### B2 Meta webhook -> backend

| STRIDE | Threat | Mitigation | REQ | Status |
|---|---|---|---|---|
| S | Forged webhook posts fake expenses or commands | Verify `X-Hub-Signature-256` (HMAC-SHA256 over raw body with app secret, constant-time compare) before parsing; reject unsigned | REQ-SEC-002 | Planned |
| S | Bogus GET handshake re-points the subscription | Compare `hub.verify_token` constant-time; log attempts | REQ-SEC-002 | Planned |
| T | Replayed or duplicated messages | Idempotency on WhatsApp message id (`raw_message` unique); `expense_source` dedup (Section 7) | REQ-SEC-002 | Planned |
| R | "I never sent that" disputes | Immutable `raw_message` audit trail with sender id + timestamps; edit history on expenses. Audit-log client addresses are stored as a keyed HMAC (`MASROOF_AUDIT_KEY`, generated by bootstrap.sh independently of the JWT secret) of the /24 (IPv4) or /48 (IPv6) prefix | REQ-SEC-024 | Planned |
| I | Messages from strangers who join the group | Only household member numbers are processed; others get no reply and are logged as `IGNORED_SENDER` | REQ-SEC-030 | Planned |
| D | Webhook storm delays ack beyond Meta's timeout | Ack `200` in <1 s (S9), enqueue to the DB job table, process async; rate limit | REQ-SEC-004 | Planned |
| E | Chat command ("edit last", "delete") run on another member's entry | Commands are authorised per sender number; destructive commands need confirmation reply | REQ-SEC-023 | Planned |

### B3 Android app -> ingest API

| STRIDE | Threat | Mitigation | REQ | Status |
|---|---|---|---|---|
| S | Stolen device token injects fake SMS | Token bound to device id, hashed (SHA-256 + pepper) in DB, revocable from Settings and by admin instantly | REQ-SEC-003, REQ-SEC-014 | Planned |
| S | Rogue Wi-Fi MITM | Certificate pinning to our domain with a backup pin; network security config HTTPS-only | REQ-SEC-017 | Planned |
| T | Modified APK (sideloaded) | APK signed with the release keystore; in-app update verifies SHA-256 from `/api/app/android/latest` | REQ-SEC-028 | Planned |
| I | OTPs or non-financial SMS uploaded | Sender allowlist on device; OTP/verification patterns dropped on device and never persisted | REQ-SEC-018 | Planned |
| I | SMS bodies in backend logs | Structured logging with redaction; semgrep rule `masroof-no-log-message-content-*`; never at INFO | REQ-SEC-005 | Planned (semgrep rule: Done) |
| I | Account numbers stored/shown in full | Mask to last-4 at parse time; raw text encrypted (AES-GCM) at rest | REQ-SEC-006, REQ-SEC-007 | Planned |
| D | Backfill of 90 days floods the API | Batch endpoint with per-device rate limit; outbox retry with backoff | REQ-SEC-004 | Planned |
| E | Device token used for non-ingest endpoints | Ingest tokens scoped to `/api/ingest/*` only; member JWT needed elsewhere | REQ-SEC-003 | Planned |

### B4 iOS Shortcut -> ingest API

| STRIDE | Threat | Mitigation | REQ | Status |
|---|---|---|---|---|
| S | Token leaks via screenshot of the Shortcut | Token shown once; revocable; per-device; rotate from Settings | REQ-SEC-003, REQ-SEC-014 | Planned |
| T | Shortcut forwards edited text | Treated like any message: parsed, bounded, `PENDING_REVIEW` when low confidence | REQ-SEC-011 | Planned |
| I | Shortcut matches too broadly and forwards OTPs | Setup doc configures sender + keyword filters; server-side OTP classifier drops and never stores body | REQ-SEC-018 | Planned |
| D | Automation loop re-sends | Idempotency on `(device_id, sms_id/hash)` | REQ-SEC-002 | Planned |

### B5 PWA -> API

| STRIDE | Threat | Mitigation | REQ | Status |
|---|---|---|---|---|
| S | Session theft (XSS, token in localStorage) | Access JWT 15 min in memory; refresh token in `HttpOnly; Secure; SameSite=Strict` cookie with rotation + reuse detection | REQ-SEC-015 | Planned |
| S | CSRF on cookie-based refresh | SameSite=Strict + Origin check; CORS allowlist = `WEB_BASE_URL` only | REQ-SEC-016 | Planned |
| T | IDOR: member A edits household B's expense | Every repository query scoped by `household_id` from the token; ArchUnit rule + integration tests with two households | REQ-SEC-023 | Planned |
| I | XSS via merchant names / notes from WhatsApp | React escaping, strict CSP (no inline scripts), DOMPurify for any rich text | REQ-SEC-001 | Planned |
| I | Receipt images via guessable URLs | Presigned MinIO URLs, 5-minute expiry, scoped per object | REQ-SEC-008 | Planned |
| D | Export endpoint abuse | Rate limit + async job for full export | REQ-SEC-004, REQ-SEC-025 | Planned |
| E | Member escalates to admin | Role claims in JWT verified server-side; admin-only endpoints under `/api/admin/**` with method security | REQ-SEC-023 | Planned |
| S | Refresh token stolen from the JSON body or JS memory (XSS) | Accepted residual risk for v1: the token is in the response body and kept in memory by the clients; strict CSP, the 15 min access TTL and reuse detection limit the damage (STORY-009) | REQ-SEC-005 | Accepted |
| E | Access token still valid up to 15 min after logout | Accepted residual risk for v1: logout revokes refresh tokens only; the JWT is stateless (STORY-009) | REQ-SEC-006 | Accepted |
| D | Per-email lockout lets an attacker lock a known user out for 15 min | Accepted residual risk for v1: 10 failures per 15 min per e-mail and per IP, in memory; a correct guess does not bypass it (STORY-009) | REQ-SEC-007 | Accepted |
| T | Concurrent refresh with the same token looks like reuse and revokes the family | Accepted residual risk for v1: both clients must run single-flight refresh; a lost race forces a new login (STORY-009) | REQ-SEC-005 | Accepted |
| D | Floods against unauthenticated routes (`/health`, `/auth/*`) | 60 requests per 60 s per client IP in the backend, 429 Problem+JSON with Retry-After; the client IP comes from X-Forwarded-For only when the peer is in `MASROOF_TRUSTED_PROXIES` (default: Docker's 172.16.0.0/12 pool) (STORY-010) | REQ-SEC-051 | Implemented |

### B6 backend -> Anthropic API (and STT / FX)

| STRIDE | Threat | Mitigation | REQ | Status |
|---|---|---|---|---|
| S | Prompt injection: message text instructs the model | Data wrapped in delimited blocks; system prompt states content is data; no side-effect tools; adversarial eval suite gates prompt changes (S10.5) | REQ-SEC-010 | Planned |
| T | Model returns out-of-range amounts, INCOME from WhatsApp text, unknown categories | JSON schema validation; server-side bounds (`AI_AMOUNT_CEILING_EGP`), forced `PENDING_REVIEW` | REQ-SEC-011 | Planned |
| I | Balances, account numbers, phone numbers sent to the LLM | Minimization: strip balances/account numbers/phone numbers before prompting; only fields parsing needs | REQ-SEC-009 | Planned |
| I | API key leak | Key only in backend env; never in clients; gitleaks + hook `scan-secrets.sh` | REQ-SEC-027, REQ-SEC-013 | Planned (scanners: Done) |
| D | Cost blow-up (long voice notes, image spam, retries) | Per-household daily token budget, max image size, 1 retry, `AI_MONTHLY_BUDGET_USD` alert | REQ-SEC-022 | Planned |
| R | Which prompt version produced an entry | Store prompt version + model + token counts per `raw_message` | REQ-SEC-024 | Planned |

### B7 backend -> PostgreSQL / MinIO

| STRIDE | Threat | Mitigation | REQ | Status |
|---|---|---|---|---|
| T | SQL injection | JPA/parameterised queries only; semgrep `masroof-no-string-concat-sql`; SpotBugs | REQ-SEC-023 | Planned (semgrep rule: Done) |
| I | DB dump or disk theft exposes texts | `raw_message.text`, OCR text encrypted with `APP_ENCRYPTION_KEY` (AES-GCM, key outside DB); MinIO SSE | REQ-SEC-007, REQ-SEC-008 | Planned |
| I | Backups readable | `age`-encrypted dumps; bucket private | REQ-SEC-012 | Planned |
| D | Disk full, runaway media | Disk alert at 80 %, media size caps, retention for raw media (configurable) | REQ-SEC-022 | Planned |
| D | DB outage or hang blocks request threads indefinitely | Hikari `connection-timeout` 2000 ms, `validation-timeout` 1000 ms, pgjdbc `connectTimeout` 2 s; the health probe has a hard 2 s deadline | REQ-SEC-022 | In progress (STORY-143) |
| E | App DB user owns everything | Separate migration role (Flyway) and runtime role without DDL | REQ-SEC-020 | Planned |
| S | Network peer impersonates Postgres or downgrades auth (no channel binding) | `sslmode=verify-full` + `channelBinding=require` in the production JDBC URL; SCRAM-SHA-256 only; prod profile requires `POSTGRES_PASSWORD` (no empty default) | REQ-SEC-020 | Planned (prod password guard: Done STORY-002) |

### B8 backend -> WhatsApp send

| STRIDE | Threat | Mitigation | REQ | Status |
|---|---|---|---|---|
| S | Backend sends to arbitrary numbers (bug or injection) | Recipient allowlist = household group id + member numbers; template-based replies | REQ-SEC-030 | Planned |
| I | Reply leaks another member's data in the group | Replies contain only the entry being confirmed; summaries are household-level by design (shared finances) | REQ-SEC-030 | Planned |
| D | Token revoked / rate limited by Meta | Outbox with retry + alert; alerts also by email (S13) | REQ-SEC-029 | Planned |
| I | Access token leak | Env only; rotation runbook; gitleaks | REQ-SEC-027 | Planned |

### B9 admin -> VPS

| STRIDE | Threat | Mitigation | REQ | Status |
|---|---|---|---|---|
| S | SSH brute force | Key-only auth, root login disabled, fail2ban, non-standard user | REQ-SEC-021 | Planned |
| T | Compromised CI deploys a malicious image | Images built in CI from `main`/tags only, cosign-signed, Trivy-scanned; deploy pulls by tag from GHCR; branch protection + required `gate` check | REQ-SEC-013, REQ-SEC-020 | Planned (workflows: Done) |
| T | Agent runs a destructive command against prod | `guard-bash.sh` blocks `PROD_HOST` commands unless `MASROOF_RELEASE_ACTIVE`; `permissions.deny` rules | REQ-SEC-021 | Planned (hooks: Phase 0) |
| E | An agent sets `MASROOF_RELEASE_ACTIVE` or `SSH_OPTS` inline to get past `deploy-guard.sh`/`guard-bash.sh` (security review of PR #67) | `deploy-guard.sh` trusts only `state.json` `release_active` outside CI; guard-bash blocks inline assignment of `MASROOF_RELEASE_ACTIVE`/`SSH_OPTS`; read-only diagnostics get their own flag. `bootstrap-host.sh` already allows only `-i`/`-p` in `SSH_OPTS` and `accept-new`/`yes` host-key checking | REQ-OPS-173 (STORY-146) | Planned |
| I | Operator diagnostics (`make bootstrap-host STEP=logs|db-recent`) send prod log lines and DB rows into the agent transcript (off-host, persisted) | WARN/ERROR lines only, through `infra/scripts/mask_log.py` (UTF-8 I/O forced; e-mail, phone, card/account digits incl. Arabic-Indic and Persian with space/dash/dot/slash/no-break-space separators, JWT, long tokens; tested through the real stdin path under a cp1252 console); `db-recent` prints state columns only (no amounts, bodies, senders, merchant text, notes, token or IP hashes) in a read-only transaction. Planned: a column-granted read-only `masroof_diag` role | REQ-SEC-021 | Partial |
| R | Who deployed what | GitHub environment `production` with deployment history; `infra/.deployed/*.env` with timestamps | REQ-SEC-024 | Planned |
| I | `.env` readable by other users | `chmod 600`, owner `masroof`; no secrets in images | REQ-SEC-021 | Planned |
| D | Failed deploy leaves prod down | Pre-deploy backup, smoke test, automatic `make rollback` | REQ-SEC-012 | Done (release.yml; scripts stub) |

### Build supply chain (backend, STORY-002)

Not a runtime boundary; it protects the artifact that crosses B1-B9.

| STRIDE | Threat | Mitigation | REQ | Status |
|---|---|---|---|---|
| T | Vulnerable transitive dependency ships in the runtime image (CWE-1395) | Spring Boot 4.1.2 BOM (OSS-supported, ADR-0004) replaces the STORY-002 overrides above the 3.5.16 BOM; an override comes back only for a finding, with a reason comment; `osv-scanner scan source -r --no-ignore backend` reports 0 findings; Dependabot | REQ-SEC-013 | Done (STORY-002); re-validated in STORY-143 |
| T | Unpinned or drifting base image in the backend image or compose | `eclipse-temurin` JDK/JRE and `postgres:16.15-alpine3.24` pinned by exact tag + multi-arch digest; Dependabot `docker` for `/backend` (`docker-compose` ecosystem for `/infra`: follow-up); Trivy image scan in main.yml | REQ-SEC-013, REQ-SEC-020 | In progress (STORY-143) |
| T | Image built from the wrong or unknown source revision | Docker build fails unless build arg `GIT_SHA` matches `^[0-9a-f]{7,40}$`; `/health` reports it; never `unknown` | REQ-SEC-013 | In progress (STORY-143) |
| I | Secrets (`.env`) or `.git` copied into the build context or image | Root `.dockerignore` is a whitelist (`backend/` only); compose passes an explicit env list, never `env_file: .env` | REQ-SEC-021, REQ-SEC-027 | In progress (STORY-143) |
| T | Tampered artifact fetched during the image build | The Docker build runs the same Gradle wrapper (`distributionSha256Sum`) and `verification-metadata.xml`, regenerated to cover `bootJar`/`bootBuildInfo` | REQ-SEC-013 | In progress (STORY-143) |
| T | Dependency graph invisible to SCA or silently changing (CWE-1104) | `dependencyLocking { lockAllConfigurations() }` -> `backend/gradle.lockfile` and `buildscript-gradle.lockfile` committed | REQ-SEC-013 | Done (STORY-002) |
| T | Tampered artifact or wrapper fetched at build time | `gradle/verification-metadata.xml` (sha256) and `gradle/actions/wrapper-validation@v4` in the backend CI jobs | REQ-SEC-013 | Done (STORY-002) |
| T | Testcontainers image tag drifts under tests | Test database image pinned to `postgres:16.15-alpine3.24@sha256:f461…` (same pin as compose) | REQ-SEC-013 | Done (STORY-002, tag); digest In progress (STORY-143) |

### B10 agents / CI -> git + GitHub (delivery gates, STORY-145)

Actors: the orchestrator and implementer agents (local shell, `gh` signed in as the owner's account), the `pr.yml` /
`pr-meta.yml` / `main.yml` jobs (`GITHUB_TOKEN` per job), the Claude Code Action (`ANTHROPIC_API_KEY`), the
`revert-on-red` job (`GATE_BOT_TOKEN`, OWNER-013) and Dependabot. Assets: `main`, the approval (G5), the required
checks, the PR body marker, repository secrets. Design: ADR-0005, ADR-0006, story STORY-145 Design notes §E.
`pr_flow_active` and guard-bash are advisory. The boundary is GitHub branch protection (STORY-016: required checks
from `delivery-gates.yaml`, one approval from a listed non-author, stale-approval dismissal, linear history, admins
included, push protection).

| STRIDE | Threat | Mitigation | REQ | Status |
|---|---|---|---|---|
| S | The PR author approves their own PR (G5) | GitHub refuses author approvals. `approvers` lists only `github-actions[bot]` and `mohamedshehataa` as a non-author. guard-bash B15 blocks `gh pr review --approve`, `gh api …/reviews` with `event=APPROVE` and GraphQL review mutations, with every flag on | REQ-OPS-159, REQ-OPS-163 | In progress (STORY-145) |
| S | A PR edits `pr.yml` (head-defined for `pull_request`) to fake green `gate/*` jobs or make the Action approve unconditionally | Accepted for now: `.github/workflows/**`, `scripts/quality/delivery-gate*`, `scripts/quality/gates/**` and the threshold files trigger security-reviewer locally and label `security-review` for the AI review. Follow-ups: code-owner review on those paths (STORY-016) and moving the approval to a `workflow_run` workflow that runs from main | REQ-OPS-161 | Accepted risk (ADR-0005) until STORY-016 |
| T | A push to main bypasses the PR (`git push origin main`, `HEAD:main`, `--all`, `--mirror`, glob refspecs, `remote.<r>.push` config) | pre-push `[G1]` rejects any update of `refs/heads/main` or `refs/heads/master`. guard-bash B1b covers the listed forms and blocks a destination built from a variable or command substitution (fail closed, like B5). Branch protection is the boundary | REQ-OPS-160, REQ-OPS-163 | In progress (STORY-145) |
| T | Known guard-bash bypass surface for B1b/B15: `curl`/`python`/`Invoke-RestMethod` POSTs to `api.github.com/…/pulls/*/reviews` or `…/merge`, `gh api --input <file>`, `-F query=@file`, editing `~/.config/gh/config.yml` aliases by hand, `git send-pack`, pushing to a URL instead of the remote name | B15 also blocks `gh api` writes to `…/pulls…` whose body comes from `--input`/`@file` outside a gate flow, and `curl`/`wget`/`Invoke-*` with a write method to `api.github.com` paths under `/pulls/`. The remaining forms are documented non-goals (REQ-OPS-001 style); GitHub enforces | REQ-OPS-163 | In progress (STORY-145) |
| T | A hand-edited PR body (claims, coverage, DECISIONS) | `gate:v1` marker = HEAD sha + sha256 of the LF-normalised body; `pr-body-lint` is required. Inserted text is neutralised (`<!--` → `&lt;!--`). The digest is **not a MAC**: anyone who reruns the algorithm can forge it, so it detects hand edits, and the gate values are recomputed by the gate jobs, never read from the body | REQ-OPS-158 | In progress (STORY-145) |
| T | The CI gate tool reads loosened thresholds from the PR's own `delivery-gates.yaml` | Same exposure as editing `pr.yml` (row 2). The agreement test plus the security-reviewer trigger on `scripts/quality/*.yaml` and `eval/thresholds.yaml` make every change visible; the AI review prompt flags threshold loosening | REQ-OPS-150 | In progress (STORY-145) |
| R | Who approved or merged what | Approval is a GitHub review on the head sha. The squash body carries `Story:`, `Reqs:`, `Verify: <path>@<sha>`, `Review: <path>@<sha>`. Owner merges on the interim route are logged as `owner merge (G5 manual, OWNER-011)` | REQ-OPS-164 | In progress (STORY-145) |
| I | `ANTHROPIC_API_KEY` exfiltrated by PR code (gate jobs run `make`, Gradle and npm on PR code) | The key is set only in the `gate/M11 ai-review` job, as step env of the Claude action. That job runs no repo code (tools: Read, Grep, Glob, `git diff/log/show`, inline/PR comment). `gate/M9 eval` on pull_request uses the reference adapter (no key). No `pull_request_target`. `persist-credentials: false` on every checkout | REQ-OPS-161, REQ-SEC-027 | In progress (STORY-145) |
| I | The key exposed on Dependabot runs (it must be a Dependabot secret, OWNER-012) | Same job isolation: Dependabot-triggered runs expose the key only to the ai-review step, which runs no dependency code (no install, no build) | REQ-OPS-161 | In progress (STORY-145) |
| I | Gate output leaks bodies, OCR text or secrets into CI logs | Gate rows hold shas, paths, counts and durations only. Command logs go to `.claude/state/gate-logs/` locally and the job log in CI. Only the tail of a failing command is printed. `make gate` never echoes environment variables | REQ-SEC-005 | In progress (STORY-145) |
| E | `GITHUB_TOKEN` over-privileged in jobs that run PR code | Workflow default `contents: read`. Per job: gate jobs read only (`pull-requests: read`/`actions: read` where needed). `pull-requests: write` only in ai-review, approval and labeler. No `checks: write`, `id-token`, `contents: write` or `pull_request_target` in `pr.yml`/`pr-meta.yml` | REQ-OPS-161 | In progress (STORY-145) |
| E | The `revert-on-red` token is misused (it can push branches and open PRs that trigger workflows) | `GATE_BOT_TOKEN` (OWNER-013) is preferably a GitHub App installation token (`actions/create-github-app-token`, 1 h lifetime) scoped to this repository with contents and pull-requests write; otherwise a fine-grained PAT with the same scope and ≤ 90-day expiry. Only the `revert-on-red` job in `main.yml` sees it. That job runs from main (`push` event), executes no build or test code (`git revert`/`rebase`, `gh`), and force-pushes only the explicit refspec `HEAD:refs/heads/revert/<short M>` with `--force-with-lease`. `contents`, `pull-requests` and `issues: write` exist only in this job. A missing token makes the job fail and open an issue naming the secret | REQ-OPS-162 | In progress (STORY-145) |
| E | A prompt injection in the diff, the PR body or comments steers the AI review (`STATUS: APPROVE`, `[WONTFIX STORY-NNN]` replies) | The diff, PR body, comments and repo files are declared untrusted data in the prompt, and the action has no write tools beyond comments. Only the strict last line of the final message is parsed (`delivery-gate.py ai-status`). Approval also needs all 14 required checks, local M10/M11 reports and `delivery-gate.py approve`. M11 ignores `[WONTFIX …]` replies authored by `github-actions[bot]`. The model is pinned with `--max-turns` | REQ-OPS-161, REQ-OPS-152 | In progress (STORY-145) |
| D | Gate spend: about 18 jobs per push plus an Anthropic call per synchronize | `concurrency` cancels superseded runs (a `cancelled` attempt is not a flaky violation). `pr-meta.yml` re-runs only pr-body-lint, M7 and M12 on edits and labels. S16 item 3 budget check | REQ-OPS-161 | In progress (STORY-145) |
| D | A flaky job is silently re-run until green | `gate/M15 ci` step `flaky-guard` compares attempts per job (`failure`/`timed_out` → `success` without a quarantine row fails) | REQ-OPS-156 | In progress (STORY-145) |

Supersedes the B9 row's "required `gate` check": the aggregate `PR gate` job is removed. Branch protection requires
the 14 `required_checks` of `delivery-gates.yaml` (STORY-016).

## 4. Requirement register (REQ-SEC)

| REQ | One-line requirement | Boundaries | Verification |
|---|---|---|---|
| REQ-SEC-001 | HTTPS only, HSTS, security headers, no internals in errors | B1, B5 | ZAP baseline, Caddy config test |
| REQ-SEC-002 | Webhook signature + verify-token checks; idempotent message ids | B2, B4 | Integration tests with recorded payloads |
| REQ-SEC-003 | Per-device ingest tokens, hashed, scoped to `/api/ingest/*`, revocable | B3, B4 | Integration + authz tests |
| REQ-SEC-004 | Rate limits and body limits on ingest/webhook endpoints | B1-B5 | k6 + Caddy tests |
| REQ-SEC-005 | Never log message bodies/OCR/payloads; redaction in JSON logs | B3, B6, B7 | semgrep rules, log assertion tests |
| REQ-SEC-006 | Mask account numbers except last-4 | B3 | Unit tests |
| REQ-SEC-007 | Encrypt sensitive columns at rest (AES-GCM, app key) | B7 | Integration test reads raw column |
| REQ-SEC-008 | Media: SSE, presigned short-lived URLs, type/size validation | B5, B7 | Integration tests |
| REQ-SEC-009 | LLM data minimization | B6 | Unit tests on prompt builder |
| REQ-SEC-010 | Prompt-injection defenses (delimited data, no side-effect tools) | B6 | Adversarial eval suite |
| REQ-SEC-011 | Server-side bounds on LLM output | B6 | Unit + eval |
| REQ-SEC-012 | Encrypted nightly backups + weekly restore test; pre-deploy backup | B7, B9 | Cron logs, restore-test report |
| REQ-SEC-013 | SAST/SCA/secret/image scanning in CI | B9 | pr.yml, main.yml, nightly.yml |
| REQ-SEC-014 | Admin removes member/device instantly (token revocation) | B3, B4 | Integration tests |
| REQ-SEC-015 | JWT 15 min + rotating refresh with reuse detection | B5 | Integration tests |
| REQ-SEC-016 | CORS locked to our origins | B5 | Integration tests |
| REQ-SEC-017 | Android: allowBackup=false, HTTPS-only NSC, pinning, no unprotected exported components | B3 | semgrep rule, MobSF, Android Lint |
| REQ-SEC-018 | SMS allowlist on device; OTPs dropped on device | B3, B4 | Unit tests, Maestro |
| REQ-SEC-019 | Money never floating point | all | ArchUnit, detekt, semgrep, ESLint |
| REQ-SEC-020 | Non-root, read-only containers; least-privilege DB roles | B1, B7, B9 | Trivy misconfig, compose review |
| REQ-SEC-021 | VPS hardening; secrets `600`; prod commands only via release flow | B9 | Checklist in DEPLOY.md, hook tests |
| REQ-SEC-022 | LLM/STT budgets, size caps, disk alerts | B6, B7 | Unit tests, alert rules |
| REQ-SEC-023 | Household-scoped authorization everywhere; parameterised SQL | B2, B5, B7 | ArchUnit, two-household integration tests |
| REQ-SEC-024 | Audit trail: raw messages immutable, edit history, prompt version per entry | B2, B6, B9 | Integration tests |
| REQ-SEC-025 | Full data export and deletion for the household | B5 | e2e |
| REQ-SEC-026 | Upload validation: MIME sniffing, size caps, image re-encode | B3, B5 | Integration tests |
| REQ-SEC-027 | Third-party API keys only on the backend | B6, B8 | gitleaks, code review |
| REQ-SEC-028 | Signed APK; in-app update verified by SHA-256 | B3 | release.yml, Maestro |
| REQ-SEC-029 | Alerts on signature failures, parse failures, job lag, LLM errors | B2, B6 | Alert rule tests |
| REQ-SEC-030 | WhatsApp send only to allowlisted recipients | B8 | Unit tests |
| REQ-SEC-060 | Household invites: admin-only create/list/revoke, single-use 256-bit code stored only as SHA-256, 7-day expiry, max 3 pending, household-scoped, audited (`INVITE_*`), code travels in the URL fragment | B5, B7 | Integration tests (403, IDOR, revoke) |
| REQ-SEC-061 | Invite acceptance: one generic 404 for every unusable code, atomic single-use claim, per-IP and per-code rate limits, member created in the inviter's household as MEMBER only | B1, B5 | Integration tests (races, rate limit, generic errors) |
| REQ-SEC-062 | New passwords: >= 12 characters, <= 72 UTF-8 bytes (BCrypt), not in the top-10k common list | B5 | Unit + integration tests (400, never 500) |

## 5. Out of scope / accepted for now
- Local guardrail self-integrity (owner decision 2026-10-24): the Claude Code hooks can be disabled by a shell write to `.claude/hooks/**` (STORY-001 F-01), a write between/across calls can survive (F-02), interpreter writes to `docs/PROMPT*.md` and command-substitution delete targets are not caught (F-03, F-04). Accepted risk until STORY-146; mitigations: Claude Code permission prompts, git history, and review of every `.claude/hooks/**` diff.

- Native iOS app: not built (Section 2); the PWA inherits browser sandboxing.
- Multi-tenant SaaS threats: one household per deployment is the target; `household_id` scoping is still enforced to keep the option open.
- Hardware wallet / banking credential storage: Masroof never holds bank credentials.
- Build supply chain: artifact signature verification (`verify-signatures`) is off in `verification-metadata.xml` (sha256 only), and no SBOM is generated for the backend yet; revisit at the first release story. Image digests are pinned from STORY-143 on.
- Framework support window: Spring Boot 3.5.16 (OSS support ended 2026-06-30) was accepted for STORY-002 only, which had no network surface. STORY-143 replaces it with 4.1.2 before the first endpoint ships (ADR-0004). If ADR-0004's fallback triggers, this becomes "Accepted risk (ADR-0003)" with manual tracking of Spring advisories.

TODO(owner): confirm the VPS provider's disk-encryption and snapshot policy; decide whether raw media retention is 90 days or indefinite.
