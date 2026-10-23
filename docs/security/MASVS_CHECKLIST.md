# OWASP MASVS v2 — Android app checklist (L1 + selected L2)

Scope: the sideloaded Android app (Kotlin/Compose) that reads SMS and bank notifications and talks to the ingest API.
Status: `N/A` (reason), `TODO`, `DONE (STORY-xxx)` with evidence (test id, MobSF report, lint rule, manifest path).
Level: `L1` baseline for every item; `L2` items are the ones we opt into because the app handles financial PII and is sideloaded (no Play Protect review).
Owner: `security-reviewer`; MobSF static scan runs nightly (`nightly.yml` `mobsf` job) and on each release APK.

## MASVS-STORAGE

| Id | Level | Requirement (paraphrase) | REQ | Status | Evidence |
|---|---|---|---|---|---|
| STORAGE-1 | L1 | Sensitive data (device token, cached expenses, pending SMS in the outbox) stored only in app-private storage; token in `EncryptedSharedPreferences`/Keystore-backed DataStore | REQ-SEC-003 | TODO | Unit tests, MobSF |
| STORAGE-1 | L2 | Room database encrypted (SQLCipher) or payload fields encrypted with a Keystore key | REQ-SEC-007 | TODO | ADR + tests |
| STORAGE-2 | L1 | No sensitive data leaks: `android:allowBackup="false"`, no logs with SMS bodies (Timber tree strips in release), no sensitive data in notifications shown on the lock screen, screenshots blocked on amount-entry screens (`FLAG_SECURE`) | REQ-SEC-005, -017 | TODO | Manifest, Lint, semgrep |
| STORAGE-2 | L1 | Clipboard: amounts copied are fine; tokens never copied | REQ-SEC-003 | TODO | Code review |
| STORAGE-2 | L2 | Outbox entries purged after successful upload; OTP messages never written anywhere | REQ-SEC-018 | TODO | Unit tests, Room inspector |

## MASVS-CRYPTO

| Id | Level | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|---|
| CRYPTO-1 | L1 | Only platform crypto (Android Keystore, AES-GCM, SHA-256); no custom or deprecated algorithms (no MD5/SHA-1/ECB) | REQ-SEC-007 | TODO | MobSF, detekt rule |
| CRYPTO-2 | L1 | Keys generated and held in the Android Keystore (`StrongBox` when available); device token hashed on the server, never derived from device identifiers | REQ-SEC-003 | TODO | Unit tests |

## MASVS-AUTH

| Id | Level | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|---|
| AUTH-1 | L1 | Authentication enforced by the backend; the app never trusts local state for authorization | REQ-SEC-023 | TODO | Integration tests |
| AUTH-2 | L1 | Member session: JWT access + refresh with rotation; logout clears Keystore-backed storage and revokes the device token | REQ-SEC-015, -014 | TODO | Tests |
| AUTH-3 | L2 | Biometric/device-credential gate (`BiometricPrompt`) before showing balances/analytics, configurable in Settings | REQ-SEC-015 | TODO | Compose UI test |

## MASVS-NETWORK

| Id | Level | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|---|
| NETWORK-1 | L1 | HTTPS only: `network_security_config.xml` with `cleartextTrafficPermitted="false"`; no user-added CAs trusted in release | REQ-SEC-017 | TODO | Manifest/NSC, MobSF |
| NETWORK-2 | L2 | Certificate pinning to our domain with a backup pin and expiry handling (OkHttp `CertificatePinner`), pins rotated with the cert runbook | REQ-SEC-017 | TODO | Instrumented test against staging |
| NETWORK-1 | L1 | Ingestion uploads only to `APP_BASE_URL` configured at onboarding; URL validated as `https://` | REQ-SEC-017 | TODO | Unit tests |

## MASVS-PLATFORM

| Id | Level | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|---|
| PLATFORM-1 | L1 | Minimal permissions: `RECEIVE_SMS`, `READ_SMS`, `POST_NOTIFICATIONS`, `CAMERA`, `INTERNET`; notification access optional; each explained in onboarding (Section 4.1) | REQ-SEC-018 | TODO | Manifest review, Maestro onboarding flow |
| PLATFORM-1 | L1 | No exported components without a permission except the launcher activity; `SmsReceiver` is exported only for `android.provider.Telephony.SMS_RECEIVED` with the `BROADCAST_SMS` permission requirement; `NotificationListenerService` bound with `BIND_NOTIFICATION_LISTENER_SERVICE` | REQ-SEC-017 | TODO (rule DONE) | semgrep `masroof-android-exported-without-permission`, Lint `ExportedReceiver` |
| PLATFORM-2 | L1 | WebViews not used; deep links (`masroof://review/<id>`) validated and never carry tokens | REQ-SEC-017 | TODO | Unit tests |
| PLATFORM-3 | L1 | UI: amounts hidden in the recents screenshot (`FLAG_SECURE`), no sensitive data in `Intent` extras to other apps, share-sheet only exports what the user chose | REQ-SEC-005 | TODO | Compose UI tests |
| PLATFORM-1 | L2 | Sideload update: APK downloaded over HTTPS, SHA-256 compared with `/api/app/android/latest` before `PackageInstaller`; signature must match the installed app | REQ-SEC-028 | TODO | Instrumented test |

## MASVS-CODE

| Id | Level | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|---|
| CODE-1 | L1 | App signed with the release keystore held in CI secrets; `v2`+`v3` signing; debuggable=false in release | REQ-SEC-028 | DONE (release.yml pipeline; keystore TODO(owner)) | `.github/workflows/release.yml` |
| CODE-2 | L1 | Up-to-date dependencies: Dependabot gradle; `osv-scanner` on `gradle.lockfile` (dependency locking enabled) | REQ-SEC-013 | DONE (config) | `.github/dependabot.yml`, pr.yml |
| CODE-3 | L1 | Static analysis: Android Lint `warningsAsErrors`, detekt, semgrep; MobSF nightly | REQ-SEC-013 | TODO (CI DONE) | nightly.yml `mobsf` |
| CODE-4 | L1 | Debug symbols stripped, R8 enabled, mapping file kept for crash reports | — | TODO | Build config |
| CODE-4 | L1 | Input from SMS/notifications treated as untrusted before parsing (length caps, no regex catastrophic backtracking) | REQ-SEC-026 | TODO | Property tests |

## MASVS-RESILIENCE (selected L2 only)

| Id | Level | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|---|
| RESILIENCE-1 | L2 | Root/compromised-device detection informs the user (no hard block; household app) and disables biometric unlock | REQ-SEC-015 | TODO | ADR + tests |
| RESILIENCE-2 | L2 | Anti-tampering: signature self-check on start; mismatch disables ingestion and prompts reinstall from the official release | REQ-SEC-028 | TODO | Instrumented test |
| RESILIENCE-3 | L2 | Obfuscation via R8; no secrets embedded in the APK (verified by MobSF "hardcoded secrets" and gitleaks on the build output) | REQ-SEC-027 | TODO | MobSF report |
| RESILIENCE-4 | — | Advanced anti-debugging / emulator detection | — | N/A | Not proportionate for a two-member household app; revisit if distributed widely (ADR) |

## MASVS-PRIVACY

| Id | Level | Requirement | REQ | Status | Evidence |
|---|---|---|---|---|---|
| PRIVACY-1 | L1 | Data minimisation: only allow-listed senders read; OTPs and promos dropped on device; backfill range user-chosen | REQ-SEC-018 | TODO | Unit tests, Maestro |
| PRIVACY-2 | L1 | Transparency: onboarding explains what is read, uploaded and why; Settings -> Sources shows the allow-list and last uploads | REQ-SEC-018 | TODO | UX review |
| PRIVACY-3 | L1 | User control: pause ingestion, revoke device, delete local cache, export data | REQ-SEC-014, -025 | TODO | e2e |
| PRIVACY-4 | L1 | No third-party analytics/ads SDKs; crash reporting with PII scrubbing only (S13) | REQ-SEC-005 | TODO | Dependency review |

---

Summary (Phase 0): DONE 2 (CI/config only), TODO 29, N/A 1. Update at each phase gate; attach the latest MobSF scorecard to the gate report.
