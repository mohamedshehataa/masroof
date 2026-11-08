# STORY-207 review reports

## Security review (security-reviewer, fresh context) - head `6c0f228`
STATUS: APPROVE (no BLOCKER/MAJOR). Rule step only sets categoryId when none is set, after the existing status and
OTP/secret filters; never amount/date/type/account/currency/status. `merchant_rule` holds only the folded key and
category id; no message text; no new logging; all new SQL single literals with bound params; no manifest/network change.
`./gradlew --offline :app:testDebugUnitTest --tests eg.masroof.CategoriesAndRulesTest` -> 16 tests, 0 failures;
`gitleaks git --log-opts="origin/main..HEAD"` -> no leaks; semgrep not run locally (CI).
FINDINGS:
- [MINOR] `MerchantRules.kt:20-26` + `CaptureRows.kt:46` -- rules match the institution-name fallback label and ignore
  type: a rule on "CIB" re-files every CIB capture without a merchant (transfers, ATM, salary). Fix: key on the real
  merchant only and apply to EXPENSE only; test that an INCOME capture stays uncategorised and PENDING_REVIEW.
- [NIT] double tap on "Change them" loses the Undo; Undo should restore only rows still in the applied category
- [NIT] apply-to-past scans every merchant row in Kotlin under the erase lock (fine now; indexed key later)
- [NIT] "Add rule" merchant field has no length cap; [NIT] tests for the notification-queue and review paths
