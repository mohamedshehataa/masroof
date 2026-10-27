# ADR-0009: Local-only Android app (pivot away from the server, WhatsApp and the PWA)

- **Status:** accepted (owner decision in chat, 2026-10-27)
- **Date:** 2026-10-27
- **Story:** EPIC-17 (local-only Android MVP; story ids are assigned in `docs/backlog/EPIC-17-local-only-android/`)
- **Deciders:** owner; architect (+ security-reviewer on the stories that touch ingestion, backup crypto and exported components)
- **Numbering note:** ADR-0008 on `main` is "invite contract tightening" (STORY-011), so this is 0009.

## Context and problem statement
The owner asked to drop WhatsApp and the online server: "only local, captures only the messages and we can attach
receipts". Chosen: **Android only, fully local**. Member 2's iPhone does not use the app; Mohamed logs for the household.

Where things stand on `main` (2026-10-27, `ad21c63`): v0.1.x runs on a GCP e2-micro (backend + Postgres + MinIO + Caddy).
The Android app (Room v8) is a cache plus outboxes in front of that server: manual entries go through `outbox`, and on-device
SMS/notification capture (`ingest/parse/MessageParser`, PR #44) writes typed records to `parsed_outbox`. It keeps a local
`review_item` list (message text stays on the phone), `learned_template`, and `captured_tx`, which handles Undo and the
SMS-vs-notification pairing (`ChannelPairing`). Insights and budgets come from `/api/analytics` and `/api/budgets` and are
cached as JSON files. Sign-in, a Tink and Keystore session, and the "wipe local data on logout" path tie local data to the
server session.

The question: what is the target architecture, and what design rules do the EPIC-17 stories follow so they do not have
to guess?

## Decision drivers
- Zero running cost and zero server operations. The VM, DuckDNS, TLS, deploys and backups all go away.
- Message privacy. Since SMS v2, no SMS text leaves the phone. Local-only goes further: no expense data leaves the phone
  unless the user exports it.
- One user and one phone. There is no multi-user sync problem left to solve.
- Keep what already works on `main`: the on-device parser, review list, template learning, pairing, Compose UI, and
  the Insights charts.
- Room becomes the only copy of the data. That makes migrations, backup and restore as critical as Flyway and
  pg_dump were on the server.

## Considered options
1. **Local-only Android app with an encrypted export file for backup (chosen).**
2. **Tiny sync-only server.** Keep a minimal authenticated store-and-forward endpoint (or object storage) that holds
   encrypted blobs, so a second device or a phone swap can sync. It keeps a host, TLS, auth and an IPv4 bill (about
   USD 4/month on GCP), and it keeps a network attack surface for one user. The only gain over a backup file in Drive
   is automatic multi-device merge, which nobody needs (Member 2 does not use the app).
3. **File exchange between phones.** Two Android installs swap export files (Nearby Share or Drive) and merge them. That
   needs a merge model (vector clocks or last-writer-wins per row, delete tombstones) and conflict UI for a second phone
   that does not exist. Member 2 is on iPhone anyway.
4. **Status quo (server-backed).** Rejected by the owner. It also keeps WhatsApp onboarding (Meta business number,
   templates), the iOS Shortcut path, auth hardening and hosting work on the backlog for a household of two.

## Decision outcome
Chosen option: **1**. One Android app, with Room as the single source of truth. No backend, web/PWA, WhatsApp, iOS
Shortcut, login, sync/outbox, Claude API or FCM in the product. Capture stays on the device: rules-only SMS parsing
(the merged `MessageParser`), the bank-app notification listener, a 90-day SMS backfill, and manual entry. Local dedup
covers SMS vs notification, plus a suggestion when a manual entry looks like a capture. A local review list handles the
rest. Receipts are stored attachments with no automatic reading in v1. Insights are computed on the phone. Backup is one
passphrase-encrypted file written through the Storage Access Framework, with restore, an optional scheduled backup, and
CSV export. If the user is signed in, the app downloads the existing server expenses once and then forgets the server
credentials. Paid-by stays as an optional member label, with no splits or settlement.

Later work, not in the MVP: budgets (local), recurring detection, biometric lock, widget, CSV import, local Habits
(EPIC-16), and retiring the server.

### D1. Which parts of PROMPT.md Part I are superseded (PROMPT.md is protected and stays unedited; this ADR overrides it)
| PROMPT.md section | Status |
|---|---|
| §1 Product summary: channels | WhatsApp, iOS Shortcut, PWA and voice notes removed. Channels: SMS, bank-app notifications, SMS backfill, manual entry, receipt attachments. |
| §2 Architecture / stack / monorepo | Superseded. The product is `android/` only. `backend/`, `web/` and `infra/` are frozen (D7). |
| §3 WhatsApp integration | Superseded entirely. |
| §4.1 / §4.2 Android SMS and notifications | Kept, except the upload. Parsing, allowlist, OTP drop, promo filtering and backfill (`READ_SMS`) stay on the device. `POST /api/ingest/*` and the outbox are replaced by local writes. |
| §5 iPhone (Member 2) | Superseded entirely. Member 2 exists only as a paid-by label. |
| §6 Parsing pipeline (backend, LLM) | Superseded by rules-only on-device parsing. No LLM is used. Receipts are not read in v1 (ML Kit text recognition has no Arabic). |
| §7 Dedup and multi-source matching | Reduced to on-device rules (D3 `expense_source`): the same source id is dropped, SMS and notification are paired (existing `ChannelPairing` window), and a manual entry that looks like a capture gets a merge suggestion. No auto-merge of manual entries. Receipt perceptual hash and line items are deferred. |
| §8 Data model (PostgreSQL) | Replaced by the Room schema in D3. Enum values (`ExpenseType`, `ExpenseStatus`, `PaymentMethod`) stay the same as `backend/.../domain/expense` so imported rows map 1:1. |
| §9 Backend API | Frozen. It is used only by the one-time import (D6). |
| §10 Analytics | "Backend computes" becomes "the device computes" (D2). Item 3 settlement is dropped (label only). Item 8 budgets are later and local. Item 9 recurring is later. Item 12 AI monthly report is dropped (no LLM). WhatsApp alerts become local notifications. |
| §11 UI | PWA-specific parts superseded. Android-specific parts kept. |
| §12 Security and privacy | Server controls (HTTPS/HSTS/CORS, webhook signatures, device tokens, column encryption, nightly pg_dump) are superseded by the device controls in D4/D5 and the Threats section. SMS privacy rules are kept and tightened. |
| Part II S10 (LLM eval harness) | Dormant for the product: no LLM in the product. Parser accuracy is gated by the Kotlin tests over `fixtures/sms/*.jsonl`. |
| Hosting (`docs/HOSTING.md`, GCP e2-micro) | Applies only until the server is retired (D7). |

**Invariants that still hold, and where each is enforced on Android:**
- **Money** is `Long` minor units (piasters) plus an ISO-4217 code. Room columns are `amountMinor INTEGER` plus
  `currency TEXT`. Backup JSON and CSV carry integers or decimal strings built from minor units, never a float. Enforced by
  the detekt money rule, semgrep `money-no-float` (kotlin), and the analytics parity tests (D2).
- **Business dates** are computed in `Africa/Cairo` and stored as UTC epoch milliseconds (`occurredAtMillis INTEGER`, an
  instant). No Cairo date is materialised in a column. Periods, days and hours are derived in Kotlin with `CairoTime`
  and `java.time` (device tzdata, updated by the OS). A date-only manual entry is stored as Cairo noon (`CairoTime.noonOf`)
  with `timeKnown = 0`. `month_start_day` (1..28) is a local setting. Enforced by period tests ported from
  `AnalyticsPeriodTest`, including the DST boundaries.
- **SMS and notification bodies, account numbers and receipt content** are never logged (no `Log.*` with them at any
  level in release) and never appear in CI output, CSV or backup. Exceptions: review items are not in the backup (D5),
  and a last four appears only as a last four. Enforced by semgrep `no-log-body` (kotlin) and the backup-exclusion test.
- **OTPs are never stored.** `SecretDetector` and `StatusGate` drop plain secrets before WorkManager. A secret-looking
  message with an amount goes to `review_item` masked and is purged after 30 days. Enforced by the existing
  `SmsSafety*`/`SmsPipeline` tests, which stay mandatory.
- **SMS, notification and receipt content is untrusted.** Parser outputs are bounded: one amount, unambiguous EGP,
  digit-free length-capped label, income and amounts above the ceiling go to review. Text is rendered as plain `Text`
  (no autoLink, no intents built from content). CSV cells are escaped against formula injection. Receipts are validated
  by magic bytes (D4).
- **Shipped schema versions are immutable.** This is the Room equivalent of the Flyway rule: a shipped `Migration(n, n+1)`
  and its exported `android/app/schemas/.../<n>.json` are never edited (D3).
- **Generated code is never hand-edited.** This applies to `android/api-client` until it is removed (D7).

### D2. Analytics on the device: a Kotlin port, not a shared JVM module
I inspected the backend. `domain.analytics` is only `AnalyticsPeriod` (139 lines) and `Projections` (111 lines),
plus `domain.expense.BusinessCalendar` (29 lines). They are pure Java 21 (records, `java.time`, `Math.*Exact`). The
real semantics live elsewhere:
- in Postgres SQL (`adapters/out/persistence/JdbcAnalyticsQueries`: `at time zone 'Africa/Cairo'`,
  `mode() within group`, refund netting, category roll-up, merchant grouping);
- in a Spring `@Service` (`application/analytics/AnalyticsService`, 323 lines).

A shared module would therefore save about 280 lines and still require rewriting the aggregation. It would also tie the
product build to a backend that is about to be retired: it would need a third Gradle build or a composite `includeBuild`
across two builds with different toolchains (Error Prone, NullAway, Spotless, verification-metadata) and its own
lockfile and osv churn. And when `backend/` is deleted, the module would have to move anyway.

**Decision:** port to Kotlin in a pure package `eg.masroof.core.analytics` (no `android.*` and no Room imports, so it
can become a JVM `:core` module later):
- `AnalyticsPeriod`: line-for-line port (`current`/`previous`/`YYYY-MM`/`YYYY-Www`, month start day 1..28, ISO weeks,
  `start()`/`endExclusive()` via `CairoTime`, `elapsedDays`/`daysLeft`).
- `Projections`: line-for-line port (`divideRounded` half away from zero, `deltaBps`, `shareBps`, `savingsRateBps`,
  `dailyAverage`, `linear`, `weekdayAware`; `Math.addExact`/`multiplyExact`; `Long`/`Int` only).
- `AnalyticsEngine`: new code that replaces the SQL and `AnalyticsService`. Input is the rows of one instant range
  `[period.start, period.endExclusive)`, read through an indexed Room query on `occurredAtMillis` that excludes deleted
  rows. Grouping by Cairo day and hour happens in Kotlin. Household volume is a few hundred rows a month, so a 12-month
  trend is a few thousand rows.

  The filter is the backend filter, unchanged:
  - base currency EGP only (non-EGP rows are excluded, not converted);
  - status `CONFIRMED` or `PENDING_REVIEW`;
  - types `EXPENSE` and `REFUND`, with refunds netted;
  - `CASH_WITHDRAWAL` only with `includeCashWithdrawals`;
  - `TRANSFER` never;
  - income is a separate sum;
  - categories roll up to their parent, with an "Uncategorised / غير مصنف" bucket;
  - merchants group by `lower(trim(name))`, keep the positive net only, ordered by total descending then name.

  Outputs feed the existing `InsightsSnapshot`/`InsightsUiModel` so the Compose screens do not change.

**Parity tests:**
1. Port every case of `backend/src/test/.../AnalyticsPeriodTest` and `ProjectionsTest` into Kotlin unit tests.
2. Add a seeded randomized test (fixed seed, at least 1,000 cases) that checks `Projections` against a `BigInteger`
   reference.
3. Add a golden file `fixtures/analytics/parity-v1.json`. It holds seed expense rows (refunds, transfers, cash
   withdrawals, a foreign-currency row, pending, rejected and deleted rows, uncategorised rows, child categories, merchant
   case and whitespace variants, instants at Cairo midnight on both DST transitions) and the expected
   summary/forecast/by-category/merchants/heatmap/trend outputs for fixed `today` values and month start days 1 and 25.
   - The expected values are produced once by a backend integration test, tag `integration` (a test-only addition to
     the frozen backend), that loads the seed into Testcontainers Postgres, calls `AnalyticsService`, and asserts
     equality with the file.
   - The Android unit test reads the same file by relative path, as `HealthContractTest` already reads
     `contracts/openapi.yaml`, and asserts the same outputs.

After the server is retired, the golden file is the oracle.

### D3. Room schema evolution (from v8 on `main`)
v8 tables: `expense` (with `pending`, `failed`, `source`, `type`, `status`), `category`, `account`, `outbox`,
`parsed_outbox`, `review_item`, `learned_template`, `captured_tx`. Migrations `MIGRATION_2_3` through `MIGRATION_7_8` exist;
`fallbackToDestructiveMigrationFrom(true, 1)` covers only the pre-release v1.

**Expand, v8 to v9** (one `MIGRATION_8_9`, additive only, with defaults so existing rows stay valid):
- `expense`: add `createdAtMillis`, `updatedAtMillis` (backfilled from `occurredAtMillis`), `deletedAtMillis` (null; soft
  delete for Undo, purged after 30 days), `timeKnown` (default 1), `paymentMethod`, `instrumentLast4`, `paidByMemberId`
  (null), and `serverId` (null; a unique index makes the server import idempotent). Indexes: `(occurredAtMillis)` and
  `(categoryId)`.
- `category`: add `parentId`, `color`, `archived` (default 0), `system` (default 0). When the table is empty (a fresh
  install that never signed in), seed it from the same list as `backend/.../db/migration/R__seed_reference.sql`, with
  stable ids.
- `account` (kept as the payment-account table; renaming would force a table rebuild for no gain): add `kind`,
  `institution`, `last4`, `archived` (default 0).
- New `member` table (`id`, `displayName`, `sortOrder`). Seeded from the import, or with "Me" when there is no server.
- New `receipt` table (`id`, `expenseId` null for an unattached receipt, `fileName`, `mimeType`, `sizeBytes`, `sha256`,
  `pageCount`, `createdAtMillis`). Index on `expenseId`. Constraints: foreign key to `expense` `ON DELETE SET NULL`, unique
  `sha256`, so the same file attached twice is detected.
- New `expense_source` table: the dedup link. Columns: `id`, `expenseId` (foreign key, cascade), `channel`
  (`SMS | PUSH | MANUAL | BACKFILL | SERVER_IMPORT`), `localSourceId` (the existing HMAC local id, unique; backfill and
  live capture of the same SMS collide here and the second is dropped), `receivedAtMillis`, `senderId`,
  `instrumentLast4`.
  - An expense has one or more sources. A merge re-points a source to the surviving expense; "keep both" leaves them
    apart.
  - `captured_tx` keeps its Undo and chip role, and its `expenseId` now means the local expense id.

**Migrate (code, not SQL):**
- Captures and manual entries write `expense` and `expense_source` directly, in one transaction, with no outbox.
- Rows still waiting in `outbox` or `parsed_outbox` are user data and are never dropped. The one-time import (D6) turns
  them into local expenses. When there is no session, a startup job does it.
- An ambiguous match against an imported server row (same amount and currency, `occurredAt` within 2 minutes) becomes
  `PENDING_REVIEW` with a merge suggestion. It is never silently deduplicated.

**Contract, v9 to v10:** this ships only in a release after the import is confirmed on the owner's phone. It drops
`outbox` and `parsed_outbox`, and drops `expense.pending` and `expense.failed` by rebuilding the table (minSdk 26
ships SQLite 3.18, which has no `DROP COLUMN`).

**Migration rules (binding for every EPIC-17 story):**
- Every version bump ships a hand-written `Migration(n, n+1)` and the exported schema JSON.
- No new `fallbackToDestructiveMigration*`. Remove the v1 exception at v10: no shipped APK has a v1 database.
- Each migration is tested with `androidx.room:room-testing` `MigrationTestHelper` under Robolectric, from every shipped
  version (2..current) to the latest, with seeded rows asserted after the migration.
- Before a migration runs on an existing database, the app copies `masroof.db` (after a WAL checkpoint) to
  `noBackupFilesDir/pre-migration-v<N>.db` and keeps only the last one.

**Data never follows the session.** `RoomDataWiper` (logout, different-member sign-in, Keystore loss) and the
capture gate tied to "signed in" must be decoupled from local data in the first EPIC-17 story that writes local
expenses, in the same release. The capture gate becomes the "capture enabled" setting. A wipe can only be an explicit
user action: Settings, then "Erase all data" with confirmation.

### D4. Receipt storage
- **Location:** app-private `filesDir/receipts/<uuid>.<ext>`. The name is generated, never the user's display name
  (path traversal, PII). `allowBackup="false"` stays, so Android Auto Backup never copies receipts or Room; D5 replaces it.
- **Sources:**
  - Photo Picker (`PickVisualMedia`, no storage permission).
  - Camera capture through a non-exported `FileProvider` URI.
  - PDF through `OpenDocument`.
  - Share target: an exported activity, `SEND`/`SEND_MULTIPLE` for `image/*` and `application/pdf`. It accepts only
    `content://` URIs, copies immediately, and reads no other extras.
  - Document scanner (ML Kit `GmsDocumentScanning`, runs in Play services, no CAMERA permission): an optional add-on with
    its own DECISIONS line when added.
- **Type and size limits:**
  - Detect the type from magic bytes (JPEG, PNG, WebP, HEIF, PDF), never from the claimed MIME type.
  - Images: at most 20 MB input and 50 megapixels (checked with `inJustDecodeBounds` before decoding).
  - PDF: at most 10 MB and 30 pages (counted with `PdfRenderer`).
  - Streams are copied with a hard byte cap. Anything else is refused with a message.
- **Stripping location and EXIF:** every image is decoded, rotated per its EXIF orientation, and re-encoded as JPEG
  (quality 85, long edge at most 2560 px). That drops all EXIF, GPS included, and normalises HEIF. PDFs are stored
  unchanged; their metadata is accepted as is and documented.
- **Viewing:** inside the app (Compose image, `PdfRenderer`). No external viewer, so no URI grants leak receipts.
- **Deletion:**
  - Removing a receipt deletes its row and then its file.
  - Purging an expense deletes its receipts.
  - A startup sweeper deletes files with no row, and flags rows whose file is missing.
  - Settings shows the total receipt size.

### D5. Backup file format and crypto
- **KDF:** Argon2id (v1.3) over the passphrase, NFC-normalised UTF-8, at least 12 code points. Defaults: m = 64 MiB,
  t = 3, p = 1, 16-byte random salt, 32-byte output.
  - Tink has no password KDF, so this uses Bouncy Castle's lightweight `Argon2BytesGenerator`
    (`org.bouncycastle:bcprov-jdk18on`). It is pure Java, so it can be checked against the RFC 9106 test vectors in JVM
    unit tests.
  - Alternatives: PBKDF2-HMAC-SHA256 from the platform JCA has no dependency but is not memory-hard, so it is
    GPU-crackable. `argon2kt` and libsodium are native JNI libraries that do not run in JVM unit tests.
- **Cipher:** Tink streaming AEAD **`AES256_GCM_HKDF_1MB`** (`AesGcmHkdfStreaming`), already a dependency
  (`tink-android` 1.24.0). The Argon2id output is the raw key, built through Tink's parameters/key API.
  - The file is encrypted in 1 MiB authenticated segments, so receipts never have to fit in memory.
  - Truncation, reordering and bit flips are detected.
  - The cleartext header is the associated data, so header tampering fails decryption.
- **File layout (`.masroof`, big-endian):**
  - magic `MASROOFB` (8 bytes)
  - `formatVersion` u16 = 1
  - `kdfId` u8 = 1 (Argon2id)
  - `memoryKiB` u32, `iterations` u32, `parallelism` u8
  - `salt` (16 bytes)
  - `aeadId` u8 = 1
  - reserved u16 = 0
  - then the Tink stream.
- **Plaintext:** a ZIP. `manifest.json` comes first: format version, data schema version (independent of the Room
  version), app version, `createdAtUtc`, row counts, and the size and SHA-256 of each receipt. Then:
  - `data/expenses.jsonl`, `categories.jsonl`, `accounts.jsonl`, `members.jsonl`, `expense_sources.jsonl`,
    `receipts.jsonl`, `learned_templates.jsonl`;
  - `data/settings.json` (month start day, sender allowlist, capture toggles);
  - `keys/local-id.key` (the HMAC local-id key, so a backfill on a restored phone still dedups);
  - `receipts/<uuid>.<ext>`.

  Not included: `review_item` (raw message text stays only on the phone and expires), `captured_tx`, the outboxes,
  session or server data.
- **Restore validation** (in order, all in a staging directory; nothing changes until everything passes):
  1. Magic and known format version. A newer version gets "update the app first".
  2. KDF parameters within bounds: m 19..256 MiB, t 1..10, p 1..4. This blocks a crafted header from forcing an
     out-of-memory error or a very long run.
  3. Decryption. A wrong passphrase and a damaged file give the same message.
  4. ZIP entry names on an allowlist: no `..`, no absolute paths, no duplicates.
  5. Uncompressed size cap of 4 GiB and a compression-ratio cap.
  6. JSON validation: integer amounts, a currency code from the ISO-4217 list, timestamps in range, enums known,
     foreign keys resolvable.
  7. Each receipt's SHA-256 and size match the manifest.

  Then one Room transaction replaces all tables, the receipts directory is swapped by rename, and the HMAC key is
  re-sealed under the new Keystore. A marker file makes an interrupted swap finish or roll back on the next start.
  v1 restore replaces everything after a confirmation that shows the backup's date and counts. A merging restore is
  out of scope.
- **Destinations:**
  - Manual backup: SAF `CreateDocument` (Drive works through its DocumentsProvider; the app needs no INTERNET for this).
  - Scheduled backup (optional): a WorkManager periodic job writes to a persisted URI permission. It uses the cached
    Argon2id key and salt, sealed under a Keystore-wrapped Tink AEAD (as `EncryptedSessionStore` does), so the passphrase
    is never stored. Tink's per-stream random nonce prefix makes reusing the key safe.
  - If Keystore is lost or the URI permission is revoked, the job stops and notifies "Backup needs attention".
  - Validation item for the story: prove a background overwrite through Drive's provider on a real device. If Drive
    refuses, fall back to a local folder plus a reminder.
- **CSV export:**
  - UTF-8 with BOM (Excel shows Arabic).
  - Amounts as decimal strings from minor units.
  - Both the Cairo local time and the UTC instant.
  - Every cell starting with `= + - @`, tab or CR is prefixed with `'` (labels come from SMS).
  - No raw message text.
- **What the threat model covers:**
  - Covered: someone who obtains the file (Drive account takeover, a shared link, a lost copy) without the passphrase
    faces an Argon2id-hardened offline guess per passphrase. Tampering, truncation and header changes are detected.
  - Not covered: a compromised or unlocked phone, malware or keyloggers, a weak passphrase that clears the minimum,
    and metadata (file size, time).
  - **A lost passphrase means the backup cannot be recovered.** The UI says so before the first backup.

### D6. One-time import from the v0.1.x server
If a session exists, Settings offers "Import from server" once:
- It pages `GET /api/expenses` plus categories, accounts and members through the existing generated client. It upserts by
  `serverId`, so it can be re-run until it finishes.
- It converts pending `outbox` and `parsed_outbox` rows (D3 "Migrate").
- It best-effort calls logout to revoke the refresh token, then deletes the session blob, its Keystore key and the server
  URL.

From then on the app makes no network calls. Server responses stay untrusted: the existing generated validation, the
contract limits and money as integers apply. Receipts were never stored on the server in v0.1.x, so none are imported.

### D7. What happens to server-era code and modules
- **Now, frozen:**
  - `backend/`, `web/`, `infra/`, `contracts/openapi.yaml` and the backend EPICs get no new features. They get fixes only
    if production breaks before retirement.
  - Their CI jobs and make targets keep running, so `main` stays green and the v0.1.x server still serves the import.
  - Backend test-only additions for D2 parity are allowed.
  - In the app, `data/auth`, `data/remote`, `android/api-client`, `work/OutboxSync*`, the `/api/insights` and
    `/api/budgets` remotes, and `INTERNET`/`ACCESS_NETWORK_STATE` stay only as long as the import (D6) needs them.
  - Upload workers stop being scheduled once the local write path ships.
- **At "retire the server"** (owner checkpoint: final `pg_dump` downloaded and verified, then the GCP VM and its IPv4
  are shut down):
  1. Tag `server-final`.
  2. Delete `backend/`, `web/`, `infra/` and the server-only docs, their CI jobs and make targets, and `android/api-client`.
  3. In the app, delete auth/session/remote/outbox code and the Retrofit/OkHttp/openapi-generator dependencies.
  4. Remove the network security config, **`INTERNET` and `ACCESS_NETWORK_STATE`** from the manifest. An app without
     the network permission is the strongest privacy property this pivot can offer.
  5. Contract migration v10 (D3).

  This is a separate story. Until then nothing is deleted.

### Consequences
- Good:
  - Hosting cost and operations go to zero.
  - Expense data never leaves the phone except in an encrypted file the user writes.
  - Most of the current Android code (capture, review, pairing, charts) is kept.
  - Many backlog EPICs (WhatsApp, iPhone, server parsing/LLM, web) stop.
- Bad:
  - A lost or broken phone without a recent backup loses everything. Scheduled backup is the mitigation and should
    follow the MVP closely.
  - Only one device: Member 2 cannot log expenses.
  - Analytics semantics now live in two codebases until retirement (parity file).
  - New dependency: Bouncy Castle (APK size; minify is still off).
  - Room migrations become as critical as Flyway migrations were.
  - Device tzdata now decides Cairo dates (the OS updates it).

## Validation
- `make check-fast MODULE=android` green on every EPIC-17 PR. `MigrationTestHelper` tests from v2..v8 to the latest pass.
- `fixtures/analytics/parity-v1.json` passes in both the backend integration test and the Android unit test.
- Backup round trip:
  - export, then restore on a wiped install, gives identical rows and receipt hashes;
  - a wrong passphrase, a flipped byte, a truncated file and out-of-bound KDF parameters are each refused;
  - review text and OTP fixtures never appear in the decrypted ZIP (unit test greps the plaintext).
- Receipt tests: EXIF GPS is absent after import, an oversize file is refused, a type spoofed by a fake MIME is refused,
  and a share-target `file://` URI is refused.
- Before retirement: no outgoing network call after the import (a unit test over the DI graph, then the manifest check
  after retirement).

## Links
- `docs/ESCALATIONS.md` SMS-001 (on-device parsing was option a; merged as PR #44)
- ADR-0004 (Spring Boot 4.1), ADR-0008 (invite contract): both frozen with the backend
- `docs/DECISIONS.md` 2026-10-27 lines tagged ADR-0009
- `docs/security/THREAT_MODEL.md`: boundaries L1 to L5 in the Threats section below. The story PRs add them to the threat model.

## Threats (STRIDE, local-only boundaries)
- **L1 SMS/notification to app (existing, kept):**
  - Spoofing: a bank sender id spoofed by SMS creates a fake expense. Mitigation: allowlist, income and large amounts go
    to review, Undo.
  - Information disclosure: message text only in `review_item` (not backed up, 30-day purge, `SecureWindow`).
  - Denial of service: an SMS flood is handled by WorkManager handoff and bounded parsing.
  - The exported receiver and listener stay guarded by `BROADCAST_SMS` and `BIND_NOTIFICATION_LISTENER_SERVICE`.
- **L2 share target and pickers to receipt store:**
  - Tampering / elevation: a `file://` URI or path traversal through the display name; content-provider confusion.
    Mitigation: `content://` only, generated names, copy-then-validate.
  - Denial of service: decompression and pixel bombs, oversized PDFs (caps in D4).
  - Information disclosure: EXIF GPS (re-encode); external viewer grants (in-app viewer only).
  - The share activity is exported by necessity, with no other actions.
- **L3 app to backup file (SAF/Drive):**
  - Confidentiality and integrity: D5.
  - Denial of service: crafted KDF parameters and ZIP bombs (bounds).
  - Elevation: zip-slip (entry allowlist).
  - Repudiation / rollback: restoring an old file is a user choice made with the date shown.
  - The key cache is Keystore-sealed and never exported.
- **L4 one-time import (temporary):** existing HTTPS. Responses are validated by the generated client. Credentials are
  destroyed afterwards. No new endpoint.
- **L5 device at rest:**
  - Room and receipts are in app-private storage protected by file-based encryption when the phone is locked.
  - `allowBackup="false"`.
  - Accepted risk: on a rooted or unlocked phone the database is readable (SQLCipher and biometric lock are later
    stories).
- **CSV:** formula injection from SMS-derived labels (escaping in D5).
- **security-reviewer sign-off required** on the stories touching L1 (capture write path, session decoupling), L2
  (exported share activity), L3 (crypto, restore parser) and L4.

## Amendment 2026-10-27 (orchestrator, after EPIC-17 slicing)
- Budgets move into the MVP (STORY-180/181), because they had already shipped (STORY-092, v0.1.3) and leaving them out
  would take a working feature away. D3's v9 expand adds a `budget` table mirroring the server columns (category or
  total, period, amount as Long piasters, rollover, alert thresholds); D5's backup adds `data/budgets.jsonl`; D6's import
  also copies budgets, the month start day and the ATM-withdrawal rule.
