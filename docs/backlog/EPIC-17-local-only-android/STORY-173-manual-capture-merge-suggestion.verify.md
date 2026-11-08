# STORY-173 verify report (loop 1 of 3)

- Verifier: qa-verifier (fresh context)
- Date: 2026-11-08
- Base: origin/main 39ed29ddf0b9df777a1a247cce80a34fdc98e4e4
- Head: e493139b8dae6c0facacd812dd347d0e603cd493 (detached in agent worktree; probes deleted, git status clean)

## Environment
- `bash scripts/make/check-fast.sh android` -> "OK android: ./gradlew checkFast", "check-fast GREEN [android]", exit 0
- `cd android && ./gradlew :app:testDebugUnitTest --rerun` -> BUILD SUCCESSFUL; 142 suites, 1410 tests, 0 failures, 0 errors, 0 skipped (build/test-results XML); 56 Duplicate* test cases ran
- `bash scripts/quality/test-integrity-check.sh origin/main` -> TEST_INTEGRITY: OK (asserts 7 -> 190, 0 skips, ExpenseWritePathTest 7 -> 7)
- Generated test with other seeds (JAVA_TOOL_OPTIONS=-Dduplicate.seed=N, seed printed by a probe, --rerun): 7, 42, 2026 -> all pass

## Criteria
| REQ | Result | Evidence |
|---|---|---|
| REQ-DEDUP-063 | PASS | DuplicateMatcherTest (14), DuplicateMergeTest 063 cases, DuplicateCardScreenTest + Arabic, DuplicateReviewViewModelTest Settings count: all green in the --rerun. Probes (run): DST 29/30 Oct (21:30Z = 29 Oct EET pairs with an untimed 29 Oct manual, 22:30Z = 30 Oct does not); timed 23:30 and 00:30 across Cairo midnight -> card (within 2 h, per spec); timed manual + untimed capture -> none; USD vs EGP -> none; USD/USD -> card; Arabic font scale 1.5 at 320dp: Merge, Keep both and the amount in Arabic-Indic digits shown |
| REQ-DEDUP-064 | PASS | DuplicateMergeTest 064 cases green. Probes (run): merge/undo/re-merge/undo -> both Undo true, total 290000; receipt added to the survivor after the merge stays there on Undo (r1 -> m, r2 -> c); notes edit of the survivor -> Undo false; capture matched by two manuals -> merge 1 Merged, merge 2 OutOfDate, total 290000; PENDING_REVIEW capture merge -> total 290000 -> 145000 |
| REQ-DEDUP-065 | PASS | DuplicateMergeTest 065 cases incl. the real DataStoreAnswerPrefs file across instances and clear(); RoomDataWiper calls answerPrefs.clear() (traced); the only caller of DuplicateRepository.merge is ReviewViewModel.merge from the Merge button (grep); swipeRight in Arabic probe -> keepBoth called once |

## Findings
| Severity | Where | What | Repro | Mode |
|---|---|---|---|---|
| MINOR | android/app/build.gradle.kts:87-90 | -Dduplicate.seed is not forwarded to the test JVM (only answer.seed is), so the documented seed knob silently runs seed 1 | gradle test with a probe printing System.getProperty("duplicate.seed") and -Dduplicate.seed=9 prints null; JAVA_TOOL_OPTIONS works | run |
| MINOR | data/edit/DuplicateMerge.kt mergeLocked + ExpenseEdit.restore | A merged manual entry restored through ExpenseEditor.restore comes back live with 0 sources next to the survivor: total doubles (145000 -> 290000) and no card reappears. No UI reaches it today (no trash screen; UndoDeleteViewModel only restores ids from editor.deleted, which merge never emits). DuplicateMergeTest:374 asserts this double-count state | merge m/c, then w.editor.restore("m"): restored=true, total 290000, cards [] | run (latent) |
| MINOR | data/edit/DuplicateMerge.kt undoLocked | Undo compares only the two expense rows and sources; a refund/transfer link made on the survivor after the merge leaves those rows unchanged, so Undo still applies and rewrites the now-linked row (category back to that of the capture), then reconciles. Conflicts with the STORY-208 rule "Undo only in the state it was made in". Probe used a hand-built EntryLink (after Undo the survivor became INCOME, total 135000 instead of 280000); not reproduced with a real AnswerRepository link, so the money effect is unproven | merge m/c; prefs.edit add EntryLink(REFUND, c, r); repo.undo(token) -> true | run (synthetic link) + traced |
| NIT | DuplicateMerge.kt survivor copy | The merchant of the manual entry is dropped when the capture has none (survivor merchant null although the manual had Carrefour); spec lists only category, notes, paid-by | merge manual(merchant=Carrefour) with capture(merchant=null) | run |
| NIT | DuplicateMatcher.kt mergeableTypes | INCOME pairs with INCOME (spec example is EXPENSE only). Judged acceptable: same type required, income is outside the spending total, still needs a tap | manual INCOME + SMS INCOME -> card | run |
| NIT | DuplicateMatcher | A zero-amount pair gets a card | manual 0 + capture 0 -> card | run |
| NIT | DuplicateCardScreenTest.kt:136-137 | Comment says Arabic-Indic digits are checked but the assertion only checks the description contains the title; no font-scale 1.5 test and no DST case in the story tests (probes show both behave) | read | traced |
| NIT | DuplicateGeneratedTest | Model is independent (Cairo day strings, own ledger, merge only on its own tap) but narrow: manuals always untimed, one currency and type, no links, erase only clears prefs (never Room) | read | traced |

No BLOCKER or MAJOR found.

## Exploratory (temporary Robolectric probes against real Room, deleted afterwards)
1. Manual untimed 2026-10-29; captures at 2026-10-29T21:30Z and 2026-10-29T22:30Z (DST end) -> cards [m/c1] only. OK
2. Card shown, then REFUND link on the manual, then Merge -> OutOfDate, card gone. OK
3. Merge, link the survivor to a refund, Undo -> Undo applied (finding 3)
4. Receipt r1 on manual, merge, receipt r2 added to survivor, Undo -> r1 back on m, r2 stays on c. OK
5. Merge, then editor.restore(manual) -> total doubles, no card (finding 2)
6. INCOME/INCOME -> card (NIT)
7. Timed 23:30 Nov 5 vs timed 00:30 Nov 6 -> card (within 2 h, spec-compliant)
8. Capture PENDING_REVIEW + manual CONFIRMED -> merge counts once (290000 -> 145000). OK
9. USD manual vs EGP capture -> none; USD vs USD -> card. OK
10. Manual merchant Carrefour, capture merchant null -> survivor merchant null (NIT)
11. Merge, Undo, Merge again, Undo again -> all succeed, total 290000. OK
12. Amount 0 vs 0 -> card (NIT)
13. Timed manual 14:00 + untimed capture same day -> none (spec: both timed). OK
14. Survivor notes edited, Undo -> refused, total 145000. OK
15. One capture, two manuals -> two cards; merge one -> other card gone; second merge OutOfDate; total 290000. OK
16. Link then unlink after merge, Undo -> true, total 280000. OK
17. Arabic, fontScale 1.5, 320dp, long Arabic/English merchants -> Merge, Keep both and the amount in Arabic-Indic digits displayed. OK
18. Arabic swipeRight on the card -> Keep both called once. OK
19. Generated test seeds 7, 42, 2026 -> pass
Total: 19 cases, 3 problems (all MINOR) plus 4 NITs.

## Test integrity
`bash scripts/quality/test-integrity-check.sh origin/main` -> TEST_INTEGRITY: OK. Six new test files (190 assertions); ExpenseWritePathTest 7 -> 7 assertions; it now allows data/edit/DuplicateMerge.kt 4 expense writes (update survivor, softDelete manual, two updates on Undo) and requires reconcile in that file. Judged sound: merge refuses linked/referenced rows (rechecked inside the transaction under EraseLock) and Undo reconciles the links of the survivor.

## Claims checked
1 pair rule: PASS. 2 one card per pair: PASS. 3 merge/undo/out of date: PASS (finding 3 on undo-after-link). 4 Keep both persistence, erase, tap-only: PASS. 5 links: PASS for link-before-merge and link-after-card; undo-after-link is finding 3. 6 write path: acceptable. 7 restore of merged manual: no UI path found; latent double count (finding 2). 8 generated test: independent model, narrow; seed knob broken via gradle -D (finding 1). 9 UI: PASS (Arabic RTL mirror test, font 1.5 probe, strings en + Egyptian ar, Settings count includes cards).

STATUS: PASS

## MINOR fixes - head `98503c5` (diff reviewed by the orchestrator)
A merged-away typed entry is marked in the answers store (`merged_away`) before the merge writes and unmarked if it
fails; `ExpenseEditor.restore` refuses it (`RestoreOutcome.MergedAway`), so a purchase is never counted twice (the test
that accepted the double count was replaced). Merge Undo is refused once either row is in a refund/transfer link or is
referenced by a gold lot or installment payment (tests with the real AnswerRepository). `-Dduplicate.seed` is forwarded
to the test JVM. The typed merchant carries over; zero amounts never pair; Arabic digits asserted, font scale 1.5 and
DST-end tests added. The generator (150 x 24 and 120 x 24 steps) now covers timed entries, EGP/USD, INCOME pairs, a link
between merge and Undo, a restore of a merged-away entry and the real Room wipe; its first run found a failed second
Merge clearing the merged-away mark, fixed. Builder gate: `check-fast GREEN [android] in 343s`; TEST_INTEGRITY OK
(7 -> 238).
