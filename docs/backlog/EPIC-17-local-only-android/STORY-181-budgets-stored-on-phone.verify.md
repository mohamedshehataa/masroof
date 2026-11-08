# STORY-181 verify report - loop 1 of 3

- Verifier: qa-verifier (fresh context; did not see the author’s summary)
- Date: 2026-11-08
- Base: origin/main 22b2146f50fc7cf74df91a513b6cd3997b4ed62f
- Head: b769c8836c8240345a99d74433e8e48a4760924c (origin/story/STORY-181-budgets-stored-on-phone, detached in agent worktree)
- REQs: REQ-AND-136, REQ-AND-137, REQ-AND-138 (+ REQ-AN-153..155 wiring from STORY-180)

## Environment
| command | result |
|---|---|
| `bash scripts/make/check-fast.sh android` | `OK android: ./gradlew checkFast` / `check-fast GREEN [android] in 42s`, exit 0 |
| `cd android && ./gradlew :app:testDebugUnitTest --rerun` | BUILD SUCCESSFUL in 3m 17s; 138 suites, 1429 tests, 0 failures, 0 errors, 0 skipped. BudgetsLocalTest 28, BudgetsScreenTest 28, BudgetsLogicTest 22, BudgetsLocalCategoryTest 5, RefreshDeadlinesTest 3, BudgetCalculatorTest 29 (all pass) |
| `bash scripts/quality/test-integrity-check.sh origin/main` | `TEST_INTEGRITY: OK`; assertions 232 -> 375 (net +143); BudgetsLogicTest 88 -> 72 "assertions decreased"; WARN BudgetsScreenTest assertDoesNotExist 1->0, assertDoesNotExistCompat 2->0 |
| Probe `ZzQaProbe181Test` (7 tests; real in-memory Room + RoomAnalyticsEngine + BudgetsViewModel), run with `./gradlew :app:testDebugUnitTest --tests eg.masroof.ZzQaProbe181Test --rerun -i` | EXIT 0, outputs below; probe file deleted afterwards, worktree clean |

## Criteria
| REQ | verdict | evidence |
|---|---|---|
| REQ-AND-136 create | PASS | Tests ran green: BudgetsLocalTest "saving Groceries monthly 4000 stores one EGP row with 50-80-100 thresholds" (400000, EGP, MONTHLY, thresholds, createdAt=now), "Arabic-Indic digits give the same amount", duplicate refused/nothing changes, WEEKLY next to MONTHLY allowed, second All spending refused, empty/0/negative/unreadable and >1e12 refused, Add first-free / disabled; BudgetsScreenTest Add-disabled message en+ar. Probe p03 (run): max 1e12 Ok; 1e12+1/0/-5/archived/unknown Invalid; total Ok then Duplicate; total WEEKLY Ok; 8 concurrent creates for one scope -> `[Ok, Duplicate x7]`, carRows=1. Probe p05 (run): `٤٠٠٠٫٥٠`->400050, `١٬٢٥٠`->125000, `10000000000`->1e12 stored, `10000000000.01`/`١٢٣٤٥٦٧٨٩٠١`->AMOUNT_TOO_LARGE, `5k`/`2.5 الف`/`1e3`/`4000.505`/`+5`/`--5`/NaN -> INVALID_AMOUNT, nothing stored. No network: grep `ApiBudgetsRemote|budgetsApi|BudgetsApi|BudgetsRemote|getAnalyticsBudgets` in android/app/src/main/java -> 0 matches. |
| REQ-AND-137 edit/delete | PASS | Tests green: "editing changes only the amount and rollover and progress follows at once", "deleting removes the budget and Home’s remaining-budget figure goes away", "already gone counts as deleted", archived -> "Deleted category" and deletable, missing category -> Deleted category; BudgetsScreenTest Cancel on delete confirm (deleted == null, dialog gone, edit form still shown), confirm delete reports id, Deleted category row deletable; BudgetsLocalCategoryTest delete InUse + archive keeps budget. Probe p03 (run): update 0 / 1e12+1 Invalid; update ok changes amount+rollover only (`budget-1:cat-groceries:MONTHLY:1:true`); update/delete missing -> NotFound. |
| REQ-AND-138 progress offline | PASS | Tests green: 500 EGP entry moves bar + Home; excluded/soft-deleted/transfer/USD/ATM never move; exclusion/delete moves back; weekly counts this week; 0% On track; failing engine -> "Couldn’t compute progress" + Retry; no offline/last-updated text. Probe p02 (run): Groceries spent 43700 = 50000 - 10000 refund + 3000 subcategory + 700 PENDING_REVIEW; TRANSFER_IN, TRANSFER, INCOME, USD, excludedFromTotals, deleted, REJECTED, failed all ignored; ATM 20000 counted only with includeCash on (63700); total 44800/64800 incl. uncategorised; `compute("current")` remainingBudget 35200 and `toSnapshot` gives Money(35200, EGP). Probe p01 (run, monthStartDay 25, today 29 Oct): monthly 2026-10-25..2026-11-24 spent 61000 (25 Oct 00:30 Cairo in, 24 Oct 23:30 out, 25 Nov 00:00 out); weekly 2026-10-26..2026-11-01 spent 12000 (29 Oct 23:30+02:00 after DST end in, Sun 1 Nov 23:59 in, Mon 2 Nov 00:00 out). Probe p06 (run, real moving clock 1.5 s before Cairo midnight 31 Oct->1 Nov): emission 1 monthly Oct spent 7000; emission 2 after midnight monthly Nov spent 0, weekly still 26 Oct spent 7000, total rollover 993000 - no refresh needed. |
| REQ-AN-153..155 wiring | PASS (data) | LocalInsightsRepository.toSnapshot passes remainingBudget (probe p02; test "REQ-AN-155 a stored total budget reaches the Insights and Wallet snapshot"); restored duplicate shown with marker and the oldest feeds remainingBudget (BudgetsLocalTest REQ-AN-155 + BudgetsScreenTest duplicate marker). Thresholds (probe p04): `0,50,50,250,abc`->[50], empty->[50,80,100], ` 80 , 50 `->[50,80], `200,201,1`->[1,200]. See F1: no UI renders remainingBudget. |

## Findings
| id | sev | where | defect | repro | run/traced |
|---|---|---|---|---|---|
| F1 | MINOR | android/app/src/main/java/eg/masroof/ui (no reader); ui/wallet/WalletTiles.kt:186 | Claim 3 (a stored total budget shows its remaining amount in Insights and Wallet) is true only at the data level. remainingBudget now reaches InsightsSnapshot, but no composable reads it. The Wallet budget card and tile show the percent of the budget closest to its limit, and Insights has only a Budgets link. The spec wording "Home budget figure" is met through the budget card and tile, which do update. | `grep -rn remainingBudget android/app/src/main/java/eg/masroof/ui` -> 0 matches | run (grep) |
| F2 | MINOR | data/db/MoneyLifeEntities.kt:35 (FK onDelete SET_NULL) | Any hard delete of a category row silently turns its budget into an unmarked extra All spending budget (categoryId null). This cannot happen today: the only caller of CategoryRulesDao.deleteCustom (DefaultCategoryRepository.delete) checks usageCount in the same transaction, upsertCategories never REPLACEs, and no category merge exists. It is a latent wrong number for STORY-183 restore or a future merge. Accepted in DECISIONS 2026-11-08 STORY-181. | probe p07: budget on custom-x, `categoryRules().deleteCustom("custom-x")` -> `budget.categoryId=null`; `lookups().upsertCategories(groceries copy)` keeps categoryId | run |
| F3 | MINOR | data/budgets/BudgetsRepository.kt:55 (create/update/delete) | RoomBudgetsRepository writes do not take EraseLock, although every other local writer does (CategoryRepository, GoalRepository, InstallmentRepository, MetalRepository, ExpenseRepository ...). A create that commits right after the DataEraser clearAllTables leaves an All spending budget behind after Erase all data. The window is narrow. | grep `eraseLock.mutex` under android/app/src/main/java -> no budgets file | traced |
| F4 | NIT | core/Money.kt:85 (MoneyFormat.parseEntry, shared) | A bidi mark after Arabic digits is refused: `٤٠٠٠` followed by U+200F gives INVALID_AMOUNT, because trim() does not strip U+200E/U+200F. Pasted Arabic text often carries these marks. | probe p05 input `٤٠٠٠` + U+200F -> error=INVALID_AMOUNT | run |
| F5 | NIT | ui/budgets/BudgetsUiModel.kt:64; BudgetsRepository.kt toSpec | A stored row whose period is not exactly MONTHLY/WEEKLY (`DAILY`, `monthly`) never appears in the list and cannot be deleted. A stored non-EGP budget shows in its own currency with spend always 0. The app never writes either; this matters for STORY-183 restore validation. | probe p04: rows daily/usd/huge inserted -> VM rows `huge, usd` only; usd limit Money(5000, USD), spent 0 | run |
| F6 | NIT | ui/viewmodel/BudgetsViewModel.kt:152 | Save has no in-flight guard. On a double-tap before recomposition disables Save, the first create closes the dialog and the second returns Duplicate, leaving error=DUPLICATE in state, so the next Add dialog opens showing the already-exists message. No duplicate row is written (probe p03: concurrent creates -> one row). | traced: `form.value = Form(error = outcome.toError())` runs after the dialog is closed | traced |
| F7 | NIT | res/values-ar/strings_budgets.xml:40-47 | The Arabic days-left plurals are MSA (يومان متبقيان, لا أيام متبقية), while the rest of the file is Egyptian colloquial. | read file | traced |
| F8 | NIT | res/values/strings_budgets.xml:35 (+ar) | budgets_error_invalid says "Check the amount", but it is shown when the category is archived or unknown (Invalid from liveCategoryCount == 0). | BudgetsLocalCategoryTest unknown category -> INVALID | traced |
| F9 | NIT | data/insights/LocalInsightsRepository.kt:150 | The comment "budgets keep theirs" is stale. insights-budgets.json (the old server snapshot) is never read now and stays until Erase all data clears InsightsCache. This is acceptable: the file is app-private and erase removes it. It could be deleted in deleteLegacyInsightsCache. | read code | traced |
| F10 | NIT | BudgetsLogicTest.kt:83; RefreshDeadlinesTest.kt:93 | The assertion daysLeft == 6 became daysLeft in 4..5, even though the clock is fixed and the value is deterministic. The retargeted RefreshDeadlines budgets test asserts the Ok returned by its own fake repository, which makes it close to a tautology. | read tests | traced |

## Exploratory (inputs verbatim; probe ZzQaProbe181Test)
1. `١٬٢٥٠` -> 125000 stored
2. `1,250` -> 125000
3. `1,250.5` -> 125050
4. `5k` -> INVALID_AMOUNT
5. `2.5 الف` -> INVALID_AMOUNT
6. `٤٠٠٠٫٥٠` -> 400050
7. `4000.505` -> INVALID_AMOUNT
8. `  12  ` -> 1200
9. `1e3` -> INVALID_AMOUNT
10. `١٢٣٤٥٦٧٨٩٠١` -> AMOUNT_TOO_LARGE
11. `10000000000` -> 1000000000000 stored; `10000000000.01` -> AMOUNT_TOO_LARGE
12. `0.01` -> 1; `0,5` -> 50
13. `--5`, `+5`, `Infinity`, `NaN`, `1 000` -> INVALID_AMOUNT
14. `٤٠٠٠` + U+200F -> INVALID_AMOUNT (F4)
15. Fullwidth `４０００` and Persian `۴۰۰۰` -> 400000
16. 8 concurrent creates of Car MONTHLY -> exactly 1 row
17. Repository create on archived / unknown / system category -> Invalid / Invalid / Ok
18. Month start day 25: entries at 24 Oct 23:30, 25 Oct 00:30, 24 Nov 23:59, 25 Nov 00:00 Cairo -> correct inclusion
19. DST end: 2026-10-29T21:30Z = 29 Oct 23:30+02:00, counted in week 26 Oct-1 Nov; Sun 1 Nov 23:59 in, Mon 2 Nov 00:00 out
20. Moving clock across Cairo midnight at month end -> re-emits with the November period and the rollover recomputed
21. D2 mix: REFUND nets; subcategory and PENDING_REVIEW count; TRANSFER_IN, TRANSFER, INCOME, USD, excluded, deleted, REJECTED and failed never count; ATM only with the switch
22. Thresholds `0,50,50,250,abc` / empty / ` 80 , 50 ` / `200,201,1` / `-5` -> [50] / [50,80,100] / [50,80] / [1,200] / [50,80,100]
23. Long.MAX_VALUE budget with 3 x Long.MAX_VALUE/2 spend -> remaining 0, bps 10000, no crash
24. Stored rows with period `DAILY` and currency USD -> hidden / USD-labelled (F5)
25. Raw category delete -> FK SET_NULL turns the budget into a total; upsertCategories keeps the link (F2)
Total 25 cases, 3 problems (14, 24, 25).

## Test integrity
- Script: `TEST_INTEGRITY: OK` (net +143 assertions, no new skips, no tolerance changes).
- BudgetsLogicTest 88 -> 72. The dropped assertions covered server-only behaviour that ADR-0009 makes obsolete: 5 adopt-after-timeout tests, the offline cache and OFFLINE status, refreshes == 1, HTTP 400/401/404/409/422 mapping, the BudgetInput wire period, and the USD household currency. Each still-relevant behaviour moved to a real Room/engine test:
  - Sort, levels and names: stored budgets become rows (same 12000/8250/5000 bps; daysLeft loosened, F10).
  - Rollover: spent-of uses the effective limit, and limit used up by rollover, now on a real engine.
  - Weekly edit stays weekly: asserted on the stored row.
  - Amount validation: BudgetsLocalTest "empty zero negative and unreadable". It adds empty, 0.00, -5 and ١٢ab, and checks clearError -> null.
  - onSaved only on success: BudgetsLocalCategoryTest closed.awaitReal() and BudgetsLogicTest assertFalse(closed.isCompleted).
  - Duplicate keeps the form open: a refused save keeps the form open.
  - Delete closes on success and when the budget is already gone: kept.
  - Repository outcomes: maps every stored-data case to an outcome, plus repository enforces the amount range.
  - The old edit with ٥٠٠ sending 50000 is now covered only for create with Arabic digits and for edit with Western digits. This is a minor gap.
- BudgetsLocalCategoryTest: the server-UUID-only picker rule was deliberately inverted to offer phone categories (local-only). Two new tests cover the category delete refusal and archiving.
- BudgetsScreenTest WARN: assertDoesNotExist() was replaced by onAllNodesWithText(..).assertCountEquals(0). This is equivalent, and stricter when several nodes match, so it is not a weakening. Assertions went 41 -> 80.
- RefreshDeadlinesTest: the server-timeout budgets test was retargeted to "never waits on a server". The original behaviour is obsolete; the new test is weak (F10).
- Verdict: no weakening of behaviour that still applies to a local-only app, beyond NIT F10 and the minor Arabic-digit edit gap.

STATUS: PASS

## MINOR fixes - head `650d8d1` (diff reviewed by the orchestrator)
The loosened `daysLeft in 4..5` is an exact `4` again (fixed clock 2026-10-27, 31-day month; the server's 6 came from a
25 Oct fixture); RefreshDeadlinesTest runs the real RoomBudgetsRepository and engine; editing with Arabic-Indic digits
is covered again. The Wallet budgets tile shows the monthly total budget as "1,000 EGP left of 4,000 EGP" / "Over by
500 EGP" (Arabic digits per setting) and follows a new entry and the deletion of the budget. Budget writes take the
erase lock. Bidi marks are stripped before amounts are parsed. Stored budgets with an unknown period or a non-EGP
currency show as "Unsupported budget" and can be deleted (DECISIONS). Save and delete ignore a second tap. Egyptian
"days left" plurals; an unknown/archived category has its own message. Builder gate: `check-fast GREEN [android] in
214s`; TEST_INTEGRITY OK (232 -> 409, no flags).
