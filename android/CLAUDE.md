# android/ - Kotlin + Compose app conventions

Kotlin, Jetpack Compose + Material 3 (custom brand palette, dynamic color off), Hilt, Room (the single source of
truth), WorkManager, DataStore, Tink, Compose/Canvas charts, receipts in app-private storage. The APK is sideloaded
(not Play Store), so SMS permissions are allowed.

## Local-only (ADR-0009, 2026-10-27): this overrides any server wording below
- The app is the whole product and no server is involved. Captures and manual entries write `expense` +
  `expense_source` in Room directly. There is no outbox and nothing is uploaded. Insights are computed on the phone in
  `core/analytics`, a Kotlin port with a parity file in `fixtures/analytics/`.
- The Retrofit/OkHttp generated client (`api-client`), `data/auth`, `data/remote` and `work/OutboxSync*` are frozen.
  They exist only for the one-time "Import from server" and are deleted when the server is retired. Add nothing new
  that calls the network.
- Local data is never wiped by a session event (logout, expiry, Keystore loss). Only Settings → "Erase all data" erases.
- Every Room version bump needs a hand-written `Migration(n, n+1)`, the exported schema JSON, and a `MigrationTestHelper`
  test from every shipped version. No new `fallbackToDestructiveMigration*`. Shipped migrations and schema JSONs are
  immutable.
- Receipts: type by magic bytes, images re-encoded (strips EXIF/GPS), size caps, in-app viewing only (ADR-0009 D4).
  Backup: Argon2id + Tink streaming AEAD file through SAF, and it excludes `review_item` text (ADR-0009 D5).
  CSV export escapes formula cells.

## Layout
```
app/src/main/java/eg/masroof/
  core/           Money, Period, Cairo time, digit formatting (no Double for money - detekt rule)
  data/           Room entities + DAOs (cache, outbox), repositories, generated API client wrapper, DataStore settings
  ingest/         SmsReceiver -> WorkManager; SmsBackfill (ContentResolver); NotificationListener; SenderAllowlist; OtpPromoFilter
  ui/             Compose screens per tab + onboarding; theme; components (AmountText, MemberChip, SourceIcon)
  widget/         Glance widget, Quick Settings tile, app shortcuts, share target
app/src/test/     unit (JUnit 5/Kotest) + Roborazzi screenshots (LTR/RTL x light/dark x font 1.0/1.5)
app/src/androidTest/  Compose UI tests; maestro/ flows (onboarding, quick add, SMS-to-entry via `adb emu sms send`, review inbox)
```

## Gradle entrypoints the SDLC calls
- `checkFast` = ktlintCheck + detekt (custom money rule) + lint (`warningsAsErrors`, `abortOnError`) + `testDebugUnitTest` with `allWarningsAsErrors`.
- `koverVerify` (>= 80% on ingest/filter/outbox/viewmodel), `verifyRoborazziDebug`, `assembleDebug`, `assembleRelease` (signing from env), `cyclonedxBom`.

## Hard rules
- No SMS or notification text ever leaves the phone: `ingest/parse/MessageParser` parses on the device and only a structured record (typed fields + a digit-free merchant label) is uploaded to `/api/ingest/parsed-transactions`. A parsed record is uploaded WITHOUT a human only if it is a proven family (`AutoRecordFamilies`: card purchase, HSBC account line, instant-transfer bank shapes, InstaPay app notifications) or matches a template the user confirmed on this phone (`TemplateLearning`: digit-free, name-free shape + sender, Room table `learned_template`, wiped on logout); everything else is a review item whose suggestion pre-fills a one-tap Save. `SecretDetector` keeps OTP/PIN/code messages from becoming records (they go to the local "Needs review" list masked, or are dropped); the app must never call `/api/ingest/sms` or `/api/ingest/notification` (unit-tested). Only allowlisted financial senders are read; unknown money-like senders go to "Suggested sources".
- Never parse in the BroadcastReceiver; hand off to WorkManager. No constant background services except the system-managed NotificationListenerService.
- Offline-first: every upload goes through the Room outbox with exponential backoff; idempotent on `(device_id, local_id)`.
- `android:allowBackup="false"`, network security config HTTPS-only with certificate pinning (+ backup pin), no exported components without a permission, biometric lock optional.
- Strings only in `res/values/strings.xml` + `values-ar/`; RTL mirroring via `supportsRtl`; amounts formatted by `core` with Western/Arabic-Indic digit setting.
- Tests carry REQ ids in names: fun `REQ-SMS-004 drops otp messages`().
