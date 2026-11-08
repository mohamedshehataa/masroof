# STORY-202 verify report (qa-verifier, fresh context)

## Loop 1 - base origin/main `cce91cb`, head `7b19377`
Gate: `bash scripts/make/check-fast.sh android` -> GREEN in 88s; `--rerun` 736 tests, 0 failures. TEST_INTEGRITY: OK
(0 -> 279). Two throwaway probe classes, ~95 inputs.
- REQ-EXP-053: PASS (story schedule exact; end-date form; day 29-31 clamping without drift; leap years; validation)
- REQ-EXP-054: PASS (link candidates exact; never link and create; exact Undo; complete; delete keeps entries; FK SET NULL)
- REQ-EXP-055: PASS (payout 2,000,000 on 2027-02-01; TRANSFER entries; analytics total excludes them; turn validation)
- REQ-AN-166: PASS (monthly 6,800, drops to 5,850 in 2 months; coming up incl. incoming collection; non-EGP excluded)
- REQ-AND-148: PASS (one reminder per payment at the Cairo day boundary; none when paid/denied; unique daily job)
- Deep link: only `bills/<token>` accepted; path tricks rejected
FINDINGS:
- [MINOR] gam3eya link candidates are EXPENSE only (real transfer never offered; a linked EXPENSE keeps counting)
- [MINOR] "just mark as paid" beyond the spec without a DECISIONS line
- [MINOR] overdue unpaid payments disappear from the overview
- [MINOR] `settle(LinkExisting)` does not re-check the window and throws inside viewModelScope (race -> crash)
- [NIT] down payment above price accepted; end-date error flags a hidden field; ISO text dates; stale manifest comment;
  reminder prefs not cleared by erase; complete/delete outside the erase lock
STATUS: PASS -> MINORs (with the security review's) fixed before integration

## MINOR fixes - head `aeea486` (diff reviewed by the orchestrator)
Gam3eya payments link TRANSFER or EXPENSE (an EXPENSE becomes TRANSFER/GAM3EYA_PAYMENT in the same transaction, Undo
restores); Overdue section; `recordPayment` returns Done/NotFound/NotLinkable instead of throwing; "just mark as paid"
hint + DECISIONS; Erase all data clears the reminder prefs; route allow-list `bills/<uuid>`; manifest reason updated;
down payment vs price, end-date error flag, collision-free notification tags, FLAG_IMMUTABLE/VISIBILITY_PRIVATE asserted,
complete/delete under the erase lock. Gate: `check-fast GREEN [android] in 112s` on the tree merged with main.
