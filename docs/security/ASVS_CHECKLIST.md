# OWASP ASVS v5.0 — Level 2 checklist (backend + PWA)

Scope: Spring Boot backend, REST API, PWA, Caddy. Android is covered by `MASVS_CHECKLIST.md`; LLM pipeline by `LLM_SECURITY.md`.
Status: `N/A` (with reason), `TODO` (story not started), `DONE (STORY-xxx)` with evidence (test id, scanner report, config path).
Owner: `security-reviewer`; reviewed at each phase gate. Chapter numbers follow ASVS 5.0; items are paraphrased, not copied.
Items marked `(L1)` are Level 1 requirements that L2 includes.

## V1 Encoding and Sanitization

| Id | Requirement (paraphrase) | REQ | Status | Evidence |
|---|---|---|---|---|
| V1.2.1 | Output encoding is context-aware (HTML/JS/URL) for data rendered in the PWA | REQ-SEC-001 | TODO | React escaping + CSP; DOMPurify for any rich text |
| V1.2.3 | Parameterised queries / ORM for all database access (L1) | REQ-SEC-023 | TODO | semgrep `masroof-no-string-concat-sql` (rule in place), SpotBugs |
| V1.2.4 | No OS command injection surface | — | N/A | Backend spawns no shell; STT via HTTP API |
| V1.3.x | Unicode normalisation of Arabic/Arabic-Indic input before validation and matching | REQ-SEC-011 | TODO | Normalizer property tests (jqwik) |
| V1.5.x | Deserialisation: Jackson with `FAIL_ON_UNKNOWN_PROPERTIES` on ingest DTOs, no polymorphic typing | REQ-SEC-026 | TODO | Contract tests |

## V2 Validation and Business Logic

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V2.1.1 | All inputs validated against the OpenAPI schema (positive allow-list) | REQ-SEC-011, -026 | TODO | Generated Spring interfaces + Bean Validation; contract tests |
| V2.2.x | Business-logic limits: amount ceiling, date sanity (not >1 day in the future), split shares sum to total | REQ-SEC-011, -019 | TODO | Property tests |
| V2.3.x | Anti-automation on ingest and chat commands (rate limits, idempotency keys) | REQ-SEC-004, -002 | TODO | k6 + integration |
| V2.4.x | Money and dates handled with exact types (minor units, `Africa/Cairo`) | REQ-SEC-019 | TODO | ArchUnit `NoFloatingMoney`, semgrep |

## V3 Web Frontend Security

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V3.1.x | Security headers: CSP (no `unsafe-inline`), `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, HSTS | REQ-SEC-001 | TODO | Caddyfile + ZAP baseline |
| V3.2.x | Cookies: `Secure`, `HttpOnly`, `SameSite=Strict`, `__Host-` prefix for the refresh cookie | REQ-SEC-015 | TODO | Integration tests |
| V3.3.x | Browser caching: `Cache-Control: no-store` on API responses with financial data | REQ-SEC-008 | TODO | Integration tests |
| V3.4.x | CORS allow-list limited to `WEB_BASE_URL`; no wildcard with credentials | REQ-SEC-016 | TODO | Integration tests |
| V3.5.x | PWA service worker caches only the app shell, never API responses with PII | REQ-SEC-008 | TODO | Playwright test |
| V3.6.x | Clickjacking: `frame-ancestors 'none'` | REQ-SEC-001 | TODO | Caddyfile |

## V4 API and Web Service

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V4.1.x | Only JSON accepted on `/api/*` (`Content-Type` enforced); multipart only on receipt upload | REQ-SEC-026 | TODO | Contract tests |
| V4.2.x | HTTP method and route allow-list; unknown routes 404 without hints | REQ-SEC-001 | TODO | Spring Security config |
| V4.3.x | API versioned through `/contracts/openapi.yaml`; breaking changes gated by oasdiff | — | DONE (Phase 0) | pr.yml `contract` job |
| V4.4.x | Webhook endpoint verifies signature before reading the body as data | REQ-SEC-002 | TODO | Integration tests with recorded payloads |

## V5 File Handling

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V5.1.x | Upload size limits (10 MB images, 25 MB voice), MIME sniffing, extension allow-list | REQ-SEC-026 | TODO | Integration tests |
| V5.2.x | Images re-encoded server-side (strip EXIF/GPS) before storage and before sending to the vision model | REQ-SEC-009, -026 | TODO | Unit tests |
| V5.3.x | Files served via presigned URLs with short expiry, `Content-Disposition` set; no path traversal | REQ-SEC-008 | TODO | Integration tests |
| V5.4.x | Stored outside the web root (MinIO), SSE enabled | REQ-SEC-008 | TODO | compose config |

## V6 Authentication

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V6.1.x | Passwords (if used for the admin) >= 12 chars, breached-password check; otherwise magic-link/WhatsApp-OTP login decided in ADR | REQ-SEC-015 | TODO | ADR + tests |
| V6.2.x | Credential storage: Argon2id for any password; device tokens hashed with SHA-256 + server pepper | REQ-SEC-003 | TODO | Unit tests |
| V6.3.x | Generic failure messages; login rate limiting; lockout with backoff | REQ-SEC-004 | TODO | Integration tests |
| V6.4.x | Secrets (`JWT_SECRET`, `APP_ENCRYPTION_KEY`) from env, >= 256 bit, rotation runbook | REQ-SEC-021 | TODO | DEPLOY.md section 5 |
| V6.5.x | Service-to-service (webhook, device tokens) are per-client, revocable, not reused as user sessions | REQ-SEC-003, -014 | TODO | Integration tests |

## V7 Session Management

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V7.1.x | Access token 15 min, refresh 30 days with rotation and reuse detection | REQ-SEC-015 | TODO | Integration tests |
| V7.2.x | Logout invalidates refresh token server-side; "log out all devices" | REQ-SEC-014 | TODO | Integration tests |
| V7.3.x | Tokens never in URLs; never logged | REQ-SEC-005 | TODO | semgrep + log tests |
| V7.4.x | Re-authentication for sensitive actions (remove member, export, delete household) | REQ-SEC-023 | TODO | e2e |

## V8 Authorization

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V8.1.x | Every data access scoped by `household_id` from the token (no IDOR) | REQ-SEC-023 | TODO | Two-household integration suite |
| V8.2.x | Roles: `ADMIN`, `MEMBER`, `DEVICE` (ingest only); checks server-side on every endpoint | REQ-SEC-023, -003 | TODO | Method security tests |
| V8.3.x | Admin actions audited (member/device removal, rule changes) | REQ-SEC-024 | TODO | Integration tests |
| V8.4.x | Deny by default in Spring Security; explicit allow for public routes (`/health`, webhook GET) | REQ-SEC-001 | TODO | Config review |

## V9 Self-contained Tokens

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V9.1.x | JWT: algorithm allow-list (HS256 or EdDSA, decided in ADR), `alg=none` rejected | REQ-SEC-015 | TODO | Unit tests |
| V9.2.x | `exp`, `iat`, `aud`, `iss` validated; clock skew <= 60 s | REQ-SEC-015 | TODO | Unit tests |
| V9.3.x | Claims minimal: `sub`, `household_id`, `role`; no PII in tokens | REQ-SEC-015 | TODO | Unit tests |

## V10 OAuth and OIDC

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V10.x | Not used in v1 (no third-party login) | — | N/A | Revisit if Google/Apple login is added (ADR) |

## V11 Cryptography

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V11.1.x | Approved algorithms only: AES-256-GCM (columns), SHA-256 (hashes), Argon2id (passwords), TLS 1.2+ | REQ-SEC-007 | TODO | Unit tests, Trivy misconfig |
| V11.2.x | Keys from env, never in code or DB; key id stored with ciphertext to allow rotation | REQ-SEC-007, -021 | TODO | Integration tests |
| V11.3.x | Random values from `SecureRandom` (tokens, nonces) | REQ-SEC-003 | TODO | Unit tests |
| V11.4.x | Webhook HMAC compared in constant time | REQ-SEC-002 | TODO | Unit test |

## V12 Secure Communication

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V12.1.x | TLS everywhere externally (Caddy ACME); HSTS | REQ-SEC-001 | TODO | ZAP, `testssl.sh` in nightly (TODO) |
| V12.2.x | Outbound calls (Anthropic, Meta, STT, FX) over TLS with certificate validation; no insecure overrides | REQ-SEC-027 | TODO | Code review, semgrep (p/default) |
| V12.3.x | Internal compose network isolated; Postgres/MinIO not published | REQ-SEC-020 | TODO | compose review |

## V13 Configuration

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V13.1.x | Build pipeline: dependency scanning (osv-scanner, Dependabot), SAST (semgrep, CodeQL), secrets (gitleaks), images (Trivy), SBOM | REQ-SEC-013 | DONE (Phase 0 workflows) | `.github/workflows/*.yml`, `.github/dependabot.yml` |
| V13.2.x | Debug features, Swagger UI, actuator endpoints disabled or internal-only in prod | REQ-SEC-001 | TODO | Profile config + ZAP |
| V13.3.x | Secrets never in repo; `.env` in `.gitignore`; hook `scan-secrets.sh` | REQ-SEC-021 | DONE (Phase 0) | `.gitignore`, `.gitleaks.toml`, lefthook |
| V13.4.x | Containers non-root, read-only FS, pinned digests | REQ-SEC-020 | TODO | Trivy misconfig in main.yml |
| V13.5.x | Backups encrypted, tested weekly | REQ-SEC-012 | TODO | restore-test report |

## V14 Data Protection

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V14.1.x | Sensitive data inventory kept (THREAT_MODEL.md "Assets") | — | DONE (Phase 0) | `docs/security/THREAT_MODEL.md` |
| V14.2.x | Raw message text and OCR encrypted at rest; media SSE | REQ-SEC-007, -008 | TODO | Integration tests |
| V14.3.x | Logs never contain message bodies, tokens, account numbers | REQ-SEC-005, -006 | TODO (rules DONE) | semgrep rules, log redaction tests |
| V14.4.x | Data minimisation towards third parties (LLM, STT) | REQ-SEC-009 | TODO | Prompt builder tests |
| V14.5.x | Household can export and delete all its data | REQ-SEC-025 | TODO | e2e |
| V14.6.x | Retention: raw media and raw texts purgeable after N days (configurable) | REQ-SEC-022 | TODO | Scheduler tests |

## V15 Secure Coding and Architecture (ASVS 5.0 additions)

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V15.1.x | Threat model exists and is updated per story touching trust boundaries | — | DONE (Phase 0 skeleton) | `THREAT_MODEL.md` |
| V15.2.x | Hexagonal architecture enforced (ArchUnit): domain has no framework imports; adapters isolated | — | TODO | ArchUnit tests |
| V15.3.x | Third-party components reviewed for licence and maintenance (no AGPL/GPL without ADR) | REQ-SEC-013 | TODO | licence check in `make security` |

## V16 Security Logging and Error Handling

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V16.1.x | Structured JSON logs with trace id, household id; redaction filter | REQ-SEC-005 | TODO | Log tests |
| V16.2.x | Security events logged: auth failures, signature failures, token revocations, admin actions | REQ-SEC-024, -029 | TODO | Integration tests |
| V16.3.x | Alerts on signature-failure spikes, parse failure > 5 %, job lag, LLM error rate | REQ-SEC-029 | TODO | Alert rules |
| V16.4.x | Generic error responses (Problem+JSON), no stack traces | REQ-SEC-001 | TODO | Contract tests |

## V17 WebRTC

| Id | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|
| V17.x | Not used | — | N/A | — |

---

Summary (Phase 0): DONE 6, TODO 64, N/A 4. Update the counts at every phase gate.
