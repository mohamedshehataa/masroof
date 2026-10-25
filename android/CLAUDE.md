# android/ - Kotlin + Compose app conventions

Kotlin, Jetpack Compose + Material 3 (custom brand palette, dynamic color off), Hilt, Room (cache + outbox),
Retrofit/OkHttp with the Kotlin client generated from `../contracts/openapi.yaml`, WorkManager, DataStore, Coil,
Vico charts, CameraX + ML Kit document scanner, Glance widget. Sideloaded APK (not Play Store) so SMS permissions are allowed.
Module arrives in Phase 2 (skeleton) and Phase 4 (SMS/notifications).

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
- No SMS or notification text ever leaves the phone: `ingest/parse/MessageParser` parses on the device and only a structured record (typed fields + a digit-free merchant label) is uploaded to `/api/ingest/parsed-transactions`. `SecretDetector` keeps OTP/PIN/code messages from becoming records (they go to the local "Needs review" list masked, or are dropped); the app must never call `/api/ingest/sms` or `/api/ingest/notification` (unit-tested). Only allowlisted financial senders are read; unknown money-like senders go to "Suggested sources".
- Never parse in the BroadcastReceiver; hand off to WorkManager. No constant background services except the system-managed NotificationListenerService.
- Offline-first: every upload goes through the Room outbox with exponential backoff; idempotent on `(device_id, local_id)`.
- `android:allowBackup="false"`, network security config HTTPS-only with certificate pinning (+ backup pin), no exported components without a permission, biometric lock optional.
- Strings only in `res/values/strings.xml` + `values-ar/`; RTL mirroring via `supportsRtl`; amounts formatted by `core` with Western/Arabic-Indic digit setting.
- Tests carry REQ ids in names: fun `REQ-SMS-004 drops otp messages`().
