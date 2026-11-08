# STORY-208 verify report (qa-verifier, fresh context each loop)

## Loop 1 - head `d2c66c9` (on STORY-206 `643415e`) - FAIL
Gate GREEN in 59s; `--rerun` 1277 tests, 0 failures; TEST_INTEGRITY OK (141 -> 425). 20 cases, 8 problems.
- REQ-EXP-064 PASS, REQ-EXP-066 PASS, REQ-EXP-065 FAIL, REQ-EXP-067 FAIL
- [MAJOR] captured Undo of one side of a pair soft-deleted the other bank's real entry (CIB balance 5,000 too high)
- [MAJOR] any unattributed INSTAPAY income of the same amount within 24 h became the TRANSFER_IN (income 5,000 -> 0)
- [MAJOR] with Track it remembered, the "Count ATM withdrawals as spending" switch had no effect (spending 0)
- [MINOR] no "yes, a refund" answer; one-sided transfer moved net worth; ignored-card check before duplicate check;
  millisecond windows instead of Cairo days; "Pick another" below the refund; Room commit before the DataStore write
STATUS: FAIL

## Loop 2 - head `a99b664` - FAIL
Gate green; 1302 tests, 0 failures; TEST_INTEGRITY OK. All loop-1 findings fixed (traced against the builder's tests;
the verifier's bash guard blocked probe files).
- REQ-EXP-064 PASS, REQ-EXP-066 PASS, REQ-EXP-067 PASS, REQ-EXP-065 FAIL
- [MAJOR] Undo of a delete did not put a converted pair back (income +5,000)
- [MAJOR] after "Keep the refund as income", Undo left the refund as INCOME (spending 750 -> 1,200 net)
- [MAJOR] a repeated answer added a second TRANSFER_IN (net worth +5,000)
- [MINOR] linked purchase editable below its refund; answer Undo lost later edits; capture Undo kept stale links
STATUS: FAIL

## Loop 3 - head `6f85dbb` (main incl. STORY-206) - FAIL
Gate green; 1317 tests, 0 failures. All loop-2 findings fixed (5 parallel answers -> 1). Probes with a moving clock.
- REQ-EXP-066 PASS, REQ-EXP-067 PASS, REQ-EXP-064 FAIL, REQ-EXP-065 FAIL
- [MAJOR] delete-Undo rebuilt a pair only if the other row was byte-identical (updatedAt included): delete both and
  restore, or a notes edit in between, left +5,000 income for good
- [MAJOR] recategorizing a purchase left its linked refund netting in the old category
- [MINOR] currency of a linked purchase could change (EGP spending negative)
STATUS: FAIL -> loop 4 allowed past the limit (DECISIONS), redesign: reconcile from the stored link after every write

## Loop 4 - head `d2b9496` - FAIL
Gate GREEN in 37s; 1326 tests, 0 failures; generated test 2,500 x 12 steps. Its model was not independent.
- REQ-EXP-066 PASS, REQ-EXP-067 PASS, REQ-EXP-064 FAIL, REQ-EXP-065 FAIL
- [MAJOR] amount/type/date edits of a pair's source never reached the inserted row (net worth +1,000)
- [MAJOR] merchant-rule applyToPast moved a linked purchase but not its refund
- [MAJOR] answering again after a lost side, then restoring the old side, gave a phantom TRANSFER_IN (+5,000)
- [MINOR] Undo of a replacing answer; type edit silently undone; lost DataStore write -> second TRANSFER_IN; gam3eya
STATUS: FAIL -> loop 5 allowed as the final loop (DECISIONS): money fields locked on linked rows (Unlink to change),
one expense write path guarded by a source-scan test, replaced links settled, independent money ledger

## Loop 5 - head `4177e71` - PASS
Gate GREEN in 38s; `./gradlew :app:testDebugUnitTest --rerun` -> 134 suites, 1341 tests, 0 failures, 0 skipped;
TEST_INTEGRITY OK. Generated test (AnswerLedger, 2,000 x 20 steps) passes on seeds 1 and 123456.
- REQ-EXP-064 PASS, REQ-EXP-065 PASS, REQ-EXP-066 PASS, REQ-EXP-067 PASS; loop-4 p1/p2/p4/p6/p7/p10 fixed
FINDINGS:
- [MINOR] Undo of an older Track token dropped a later Count (generator failed on seed 8277; UI cannot reach it)
- [MINOR] type edit on the survivor of a broken pair saved, then reverted by reconcile
- [MINOR] Undo of Unlink after Erase all data put link ids back into the emptied store
- [MINOR] "Don't count in totals" / gold claim on a purchase with a linked refund made the category negative
- [NIT] write-path scan missed claimExcluded/deleteBatch/aliased DAOs; SmsWork hard delete; purge left links
STATUS: PASS

## Loop-5 follow-ups - head `6abaaca` (diff reviewed by the orchestrator)
Undo tokens carry a link nonce and an erase epoch and apply only to the state they were made in; a new Track/Count
settles the withdrawal's older link; type edits on a converted survivor and "Don't count"/gold claims on linked rows are
refused until Unlink (offered for any link); the write-path scan covers claimExcluded/deleteBatch and DAO aliases; the
frozen SmsUploader hard delete skips linked rows; a purge drops links of purged rows. Generator passes on base seeds
1, 7001, 123456, 424242 and 99991 (seed 424242 found the nonce bug first). Builder gate: `check-fast GREEN [android] in
174s`; TEST_INTEGRITY OK.

## Security MINOR fix - head `1532496` (diff reviewed by the orchestrator)
Undo of Track it (capture or answer) removes the created Cash account only when nothing of the user's depends on it (no
entry on it live or in the trash, no balance check besides the fixed-id zero seed); otherwise it is archived. The frozen
upload path skips and clears the Undo request of a linked row; the ignored-card twin check compares currency.
Tests `AnswerCashTest` cash-1..7 (cash-1, cash-3, cash-4, cash-6 failed before the fix).
