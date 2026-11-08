# STORY-200 review reports

## Security review (security-reviewer, fresh context) - head `30cae43`, 2026-11-08
STATUS: APPROVE (no BLOCKER/MAJOR)

Clean: manifest adds only `RECORD_AUDIO` and a `<queries>` entry for `android.speech.RecognitionService` (nothing
exported, `allowBackup="false"` and network config unchanged, no dependency or scanner-config change); permission asked
only on mic tap; on-device recognizer on API 31+ else default with `EXTRA_PREFER_OFFLINE`; no audio handled or written;
recognizer destroyed on result/error/close/back/leave; transcripts only fill form fields, never logged, persisted or
sent; parser input capped at 400 chars, overflow-checked integer maths, 10-billion EGP ceiling on parse and Save;
type and currency on save come from fixed lists. Local runs: `./gradlew :app:testDebugUnitTest --tests
'eg.masroof.QuickEntry*'` -> 67 tests, 0 failures; `gitleaks git --log-opts="origin/main..HEAD"` -> no leaks; semgrep not
installed locally (CI covers it).

FINDINGS:
- [MINOR] `ui/add/VoiceInput.kt:70,87`, `strings_voice.xml:18` - without an on-device recognizer the default system
  recognizer may send speech to a cloud service; the rationale does not say so (ADR-0009 local-only promise). Fix: a
  one-time notice on the first mic tap when not on-device, reworded rationale, test.
- [MINOR] `ui/viewmodel/QuickFill.kt:81`, `ingest/quick/QuickEntryParser.kt:149-160` - parser merchant not capped at 80
  chars (the manual field is). Fix: `take(MAX_MERCHANT)` + test.
- [MINOR] `ui/add/AddExpenseScreen.kt:136,145` - listening continues after Home until silence timeout. Fix: cancel on
  `ON_STOP`.
- [MINOR] `data/ExpenseRepository.kt:50,146,149` - `NewExpense.type`/currency unchecked strings. Fix: require known
  type and `[A-Z]{3}` currency, negative test.
- [MINOR] `AddExpenseScreen.kt:145` - rotation restarts listening without a new tap.
- [NIT] `QuickEntryParser.kt:58` - raw line kept in the note may carry bidi override characters; strip them.
- [NIT] `ViewModels.kt:372`, `QuickFill.kt:40` - KWD has 3 ISO decimals but amounts are stored in hundredths; drop KWD
  from the list or document "minor = 1/100" (DECISIONS).
