# STORY-208 security review (security-reviewer, fresh context)

## Round 1 - head `d2c66c9` (on STORY-206 `643415e`) - APPROVE
Commands: `bash scripts/make/check-fast.sh android` -> `check-fast GREEN [android] in 65s`; `bash scripts/make/security.sh`
-> osv-scanner OK, trivy fs/config OK, semgrep not installed locally (CI runs it), gitleaks full history `leaks found: 2`
(not attributed; `gitleaks dir` over the story's paths -> no leaks; CI gitleaks must be green before merge).
Semgrep custom rules checked by grep over changed main sources (no-log-message-content, no-string-concat-sql,
no-template-sql-kotlin, money-not-floating-kotlin) -> 0 matches.
Holds: secret/OTP/promo filter runs before any recorded transaction (MessageParser.kt:588-602, :687), so ignored-card,
linked-card and Track-it paths only see filtered records; ignored-card review text built from typed fields with `**1234`
(SmsWork.kt:256-262); "answers" DataStore holds ids, 4-digit tails (validated on read and write) and the ATM choice only;
app data excluded from device backup; RoomDataWiper clears the answers store; UI shows `•••• NNNN` only; no manifest,
permission, network or dependency change.
FINDINGS:
- [MINOR] SmsWork.kt:239-241, AnswerWrites.kt:201-205 -- remembered "Track it" saves the cash side as a confirmed manual
  entry even when the withdrawal is PENDING_REVIEW (over the review ceiling); a spoofed ATM SMS inflates Cash with no review
  flag -- fix: cash side copies the withdrawal's status; test over-ceiling + TRACK -> two PENDING_REVIEW rows
- [NIT] answers store written after the erase lock is released; setAtmChoice/restoreIgnored take no lock (CWE-459)
- [NIT] ignored-card check runs before the duplicate check (re-delivered message -> extra review item, no money)
- [NIT] AnswerCardsUi.kt:62 `@Suppress("LongParameterList")` without a reason
- [UNVERIFIED] gitleaks history hits; D5 backup exclusion (no backup writer yet); no test that erase clears answers
STATUS: APPROVE

## Round 2 - `d2c66c9..6abaaca` (android/app/src/main) - APPROVE
Commands: `bash scripts/make/check-fast.sh android` -> GREEN in 20s; `bash scripts/make/security.sh` -> osv-scanner OK, trivy
fs/config OK, semgrep not installed locally (custom-rule patterns grepped on the + lines: 0 hits), gitleaks full history
`leaks found: 2`; `git log -p aeb34cb..6abaaca | gitleaks stdin` -> no leaks found (the 2 hits predate this story).
Round-1 items fixed: the tracked cash side copies the withdrawal's status (AnswerWrites.insertPairSide `status =
like.status`); every answers-store write holds the erase lock (capture links edit also checks the gate); duplicate check
before the ignored-card path, which still sits after MessageParser's OTP/promo/secret filters; suppressions have reasons.
A spoofed allowlisted SMS can only create new entries (plus a Cash side with the same status under Track it); it cannot
move, hide, delete or change a linked row. The answers store holds ids, type/status codes, account/category ids, a
nonce, the ATM choice and validated last fours; Erase clears it and the erase epoch refuses every older Undo.
FINDINGS:
- [MINOR] Captured.kt:316-317 -- capture Undo hard-deletes the Cash account Track it created when no live entry uses it;
  the account_balance cascade destroys user balance checks and trashed entries keep a dangling accountId -- fix: delete
  only when unreferenced, otherwise archive
- [NIT] SmsWork.kt:271 -- the ignored-card duplicate check ignores currency
- [NIT] SmsWork.kt:582-584 -- frozen upload path deletes on the server before the linked-row check
THREAT_MODEL_UPDATED: NO_CHANGE_NEEDED
STATUS: APPROVE
