# STORY-207 verify report (qa-verifier, fresh context)

## Loop 1 - base origin/main `cce91cb`, head `6c0f228`
Gate: `bash scripts/make/check-fast.sh android` -> EXIT 0; `--rerun` 684 tests, 0 failures. TEST_INTEGRITY: OK (0 -> 156).
- REQ-EXP-060: FAIL; REQ-EXP-061: FAIL. Emoji seeding idempotent and never overwrites; duplicate detection incl. Arabic
  forms; rules never change amount/type/currency/account; 20 concurrent addRule -> 1 rule
FINDINGS:
- [BLOCKER] `ExpenseRepository.kt:123` -- `observeCategories()` now offers local ids to the server-backed Budgets form;
  Save throws in `ApiBudgetsRemote.kt:56` (UUID.fromString), uncaught -> app crash
- [MAJOR] `oneChipPerName` hides a custom category whose name exists under another parent
- [MAJOR] server "Groceries" UUID deduped away -> server budgets shown as "All spending"
- [MINOR] Add sheet sends no merchant (rules never apply to manual entries in the app); bank-name rule re-files
  INCOME/TRANSFER on apply-to-past; Undo overwrites later edits and resets updatedAt; archived top-level categories
  show as Uncategorised in Insights (pre-existing); rule key differs from Insights grouping
- [NIT] dropping digits ("3M" -> "m"); no name length cap; reorder by buttons not drag
STATUS: FAIL -> fix round 1 sent (with the security review's MINOR)
