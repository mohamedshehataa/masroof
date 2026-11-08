# STORY-202 review reports

## Security review (security-reviewer, fresh context) - head `7b19377`
STATUS: APPROVE (no BLOCKER/MAJOR). Route extra fully matched against `bills/[A-Za-z0-9-]{1,64}` (no `/ ? # % .`),
launch intent replaced before navigation; PendingIntent explicit + FLAG_IMMUTABLE; notifications VISIBILITY_PRIVATE with
a generic public version, provider/title/amount only; Erase all data cancels notifications; one unique daily job, no exact
alarms or network; all new SQL bound literals; no logging; no manifest/Gradle change. `BillsReminderTest` 13/13;
`gitleaks git --log-opts="origin/main..HEAD"` -> no leaks; semgrep checked by hand (CI runs it).
FINDINGS:
- [MINOR] `di/BillsModule.kt:40-42`, `data/auth/RoomDataWiper.kt:100-110` -- Erase all data leaves the
  `masroof_reminders` prefs (payment ids + timestamps) and does not cancel the daily job (narrow race)
- [MINOR] `MainActivity.kt:102` -- `OPEN_ROUTE=bills/add` passes the allow-list from any app; tighten to `bills/<uuid>`
- [MINOR] `AndroidManifest.xml:38-40` -- the `nosemgrep exported_activity` reason ("reads no intent data") is now false
- [NIT] notification id and request code from `key.hashCode()` can collide; tests do not assert FLAG_IMMUTABLE and
  VISIBILITY_PRIVATE
