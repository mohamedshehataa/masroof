# STORY-185 verify report (loop 1 of 3)

- Verifier: qa-verifier (fresh context, did not write the code)
- Date: 2026-11-08
- Base: origin/main 39ed29ddf0b9df777a1a247cce80a34fdc98e4e4
- Head: 713f94bfbb52b6723587cbd15e20d9e7df8e6d03 (origin/story/STORY-185-csv-export, checked out detached)
- Loop: 1 of 3

## Environment
| command | result |
|---|---|
| `bash scripts/make/check-fast.sh android` (env.sh source was refused by the worktree guard; script run directly) | `check-fast GREEN [android] in 184s`, EXIT=0 |
| `cd android && ./gradlew :app:testDebugUnitTest --rerun` | BUILD SUCCESSFUL in 3m53s; XML: 138 suites, 1381 tests, 0 failures, 0 errors, 0 skipped. CsvExportTest 17/0/0/0, ExportCsvViewModelTest 10/0/0/0 |
| `bash scripts/quality/test-integrity-check.sh origin/main` | 2 added test files (63 + 36 asserts), 0 skips, 0 deleted; `TEST_INTEGRITY: OK` |
| Probes | two throwaway Robolectric tests (QaProbeCsvTest, QaProbeExportUiTest) run with `./gradlew :app:testDebugUnitTest --tests ...`, then deleted; `git status` clean |

## Criteria
| REQ | result | evidence |
|---|---|---|
| REQ-EXP-048 | PASS | Tests: 13 REQ-EXP-048 tests in CsvExportTest + 10 in ExportCsvViewModelTest, all green. Probe (run): real bytes of a 26-entry October fixture: first bytes `efbbbf`, 26 CRLF, the only bare LF is inside a quoted note; exact spec header; 24 records = every not-deleted row in range (soft-deleted `DELETED-ROW-SECRET` absent). Amounts 1450.00, 450.00 REFUND, 25000.00 INCOME, 12.99 USD, 0.05, 100000000000.00, 92233720368547758.07 (Long.MAX_VALUE), none negative. Types TRANSFER, TRANSFER_IN, CASH_WITHDRAWAL, INCOME, REFUND as stored; status PENDING_REVIEW kept. Cairo edges: 2026-09-30T20:59:59Z excluded, 21:00:00Z -> 2026-10-01 00:00; 2026-10-31T21:59:59Z -> 10-31 23:59 included, 22:00Z excluded. DST end (29/30 Oct 2026): 20:30Z -> 10-29 23:30, 21:30Z -> 10-29 23:30 (UTC+2), 22:30Z -> 10-30 00:30; custom range 30 Oct..30 Oct returned exactly the 2 rows of that Cairo day (inclusive both ends). timeKnown=false -> empty time_cairo. occurred_at_utc `2026-10-12T07:00:00Z` (millis truncated). Category English, `بنزين` fallback for blank nameEn, parent category, archived category and archived account still named, sources `SMS;PUSH`, receipts `1`. Reversed range at exporter level -> header only, 0. 10,000 entries (3 per timestamp): count 10000, 10000 distinct, in order, 237 ms, max single write 8192 bytes (buffered), pages of 200 via keyset. VM tests: This month with start day 1 and 25, Last month, This year, Custom, end-before-start disables Export and never opens a stream, empty range Done(0), provider failure -> Failed + discard + data unchanged, null stream -> Failed. UI probe (run): Arabic ar-rEG, RTL, font scale 1.5: all strings render, Export disabled with "تاريخ النهاية قبل تاريخ البداية", Export button, range chip and Dismiss each at least 48dp, done line "اتصدّرت ١٬٢٤٠ عملية". No manifest, gradle, schema or migration change in the diff; no network code. BOM written as `0xFEFF.toChar()` (no literal U+FEFF bytes in any new source file, so formatting cannot strip it). |
| REQ-SEC-069 | PASS | Tests: 3 REQ-SEC-069 tests green. Probe (run): `-Wallet` (archived account), member `=SUM(1)`, notes `-`, `-5`, tab `=1`, merchant CR `=1+1`, merchant U+FEFF `=1+1` all exported as `"'..."`; note with `a"b,c` CRLF `d` stays one quoted cell with the quote doubled. Grep of the produced file for review-item body/sender, a 16-digit account number in the body, source senderId/localSourceId, captured_tx label/templateId, parsed_outbox sender/institution/merchant, receipt file name/sha and expense.instrumentLast4 `9876`: 0 hits. Gaps beyond the listed starters are recorded as MINOR below. |

## Findings
| severity | where | defect | repro | run/traced |
|---|---|---|---|---|
| MINOR | android/app/src/main/java/eg/masroof/data/export/CsvFormat.kt:30-37 | The formula guard checks only the first character. A cell starting with a space, a no-break space (U+00A0), a right-to-left mark (U+200F) or the full-width equals sign (U+FF1D) is exported raw and unquoted: observed ` =1+1` (merchant and category), `U+00A0=2+2` (notes), `U+200F=1+1` (merchant), `U+FF1DHYPERLINK("y")` (parent category, quoted only because of the quote char). Some importers trim leading whitespace (OWASP guidance covers this case). The literal spec list is met, so this is a defense-in-depth gap. | Insert an expense with merchant ` =1+1` and export: the row contains `, =1+1,` | run |
| MINOR | android/app/src/main/java/eg/masroof/ui/settings/ExportCsvViewModel.kt:541-542 | On cancellation the CancellationException is rethrown before `discard` runs. Settings is a pushed route (MainActivity.kt:303 `nav.navigate(SETTINGS_ROUTE)`), so pressing Back during a long export pops the entry, clears the VM and cancels the job. A truncated CSV is left in the user-chosen place with no message. App data is unchanged. | Start a large export, press Back before it finishes, then open the file: it is truncated | traced |
| NIT | ExportCsvViewModel.kt:515-519 | `choose`, `setCustomFrom` and `setCustomTo` reset status to Idle while an export is running. The Export button is enabled again, the "Exporting…" label disappears, and a second concurrent export can be started. | Tap a range chip while "Exporting…" shows | traced |
| NIT | android/app/src/main/res/values-ar/strings_export.xml:611 | The Arabic `zero` form hard-codes a Latin "0", while every other form formats with `%1$,d` (Arabic-Indic digits, observed `١٬٢٤٠`). The digits are mixed, and the app's digit preference (LocalMoneyStyle.digits) is not used. | Export an empty range in Arabic | run (other forms) / traced (zero) |
| NIT | CsvFormat.kt:30 | MINOR_SCALE = 2 for every currency. This is consistent with the app's storage today (MessageParser.kt:1091 moves every currency's decimal point by 2, including KWD/JPY), so no number is wrong now. It becomes wrong if per-currency exponents are introduced. | n/a | traced |

## Exploratory (13 cases, 2 problems)
1. Merchant ` =1+1` (leading space): exported raw. PROBLEM (MINOR 1)
2. Parent category `U+FF1DHYPERLINK("y")` (full-width equals): not prefixed. PROBLEM (same MINOR 1)
3. Merchant `U+200F=1+1` (RLM first) and note `U+00A0=2+2` (NBSP first): not prefixed (same MINOR 1)
4. Merchant `U+FEFF=1+1`: exported `"'=1+1"` (guarded)
5. Note `-` only and note `-5`: `"'-"`, `"'-5"`
6. Note LF then `=1+1`: quoted, not prefixed (LF is not in the list; the cell does not start a formula)
7. Note `|calc` (pipe, no equals): raw (not a formula starter)
8. Member display name `=SUM(1)` and archived account `-Wallet`: guarded
9. Amount Long.MAX_VALUE and 10,000,000,000,000 minor: `92233720368547758.07`, `100000000000.00`
10. DST-end instants 2026-10-29T20:30Z / 21:30Z / 22:30Z: 23:30, 23:30, 30 Oct 00:30
11. Cairo-midnight edges 30 Sep 20:59:59Z / 21:00Z and 31 Oct 21:59:59Z / 22:00Z: correct in/out
12. Reversed range passed directly to the exporter: header only, count 0 (no crash)
13. 10,000 entries, 3 per timestamp, across 50 pages: no duplicates or gaps, ordered, 237 ms

## Test integrity
`bash scripts/quality/test-integrity-check.sh origin/main`: assertions 0 -> 99 in 2 new files, 0 skips, 0 tolerance changes, no deleted or loosened tests. TEST_INTEGRITY: OK.

## MINOR fixes - head `3e46c37` (diff reviewed by the orchestrator)
Formula guard at every place a cell can start (the beginning, and after `;`, `,`, tab, CR, LF), skipping spaces, NBSP,
U+200B-U+200F and U+FEFF, with full-width ＝＋－＠ treated as formula characters and a leading tab/CR always guarded;
a cancelled export still discards the half-written file (NonCancellable + IO) before the cancel goes on; range changes
and a second export are ignored while exporting (the Exporting status is claimed before launch); the output stream
opens on the IO dispatcher with the application context; the Arabic zero plural uses the formatted number; the leak
test also checks the source sender id and the instrument last four. Builder gate: `check-fast GREEN [android] in 271s`;
TEST_INTEGRITY OK (0 -> 121).
