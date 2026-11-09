# STORY-186 security review (security-reviewer, fresh context)

## Round 1 - head `1d46406` - CHANGES_REQUESTED
Holds: READ_SMS asked only on Start after an explanation, with a manifest justification; bound `?` query parameters;
no new exported component; backfill runs the same allowlist, gate, OTP and parser checks as live capture before
anything is stored; keyed HMAC local ids; revocation handled; `masroof_backfill` holds keyed ids and counts only,
cleared by Erase all data and excluded from backup; the summary notification shows counts only, private on the lock
screen; pages of 200 in one unique WorkManager run.
FINDINGS:
- [MAJOR] SmsInbox.kt:86-99 -- the body of every inbox row in range was read into memory, not just allowlisted
  senders' (data minimisation; the spec, manifest comment and permission dialog say otherwise)
- [MINOR] Erase all data did not cancel the backfill work and left stale state that could write after the erase
- [MINOR] live/backfill dedupe depended on date_sent and identical bodies
- [NIT] a system stop was saved as STOPPED
Accepted risk (DECISIONS): backfill trusts rows the default SMS app wrote to the inbox.
STATUS: CHANGES_REQUESTED

## Round 2 - `1d46406..e0ac697` - APPROVE
The MAJOR is fixed: only the address column is read first, and message text only for allowlisted senders through
`address IN (?,...)` with bound values; erase bumps the epoch, cancels the work and every write checks the epoch
under the erase lock; a system stop resumes. FINDINGS: [MINOR] the backfill twin match could set a card tail and
overwrite the merchant on an old notification-only entry (account balance assignment); [MINOR] the summary
notification could post after an erase.
STATUS: APPROVE

## Round 3 - `e0ac697..1fb47a0` - APPROVE
Both round-2 MINORs fixed (enrichment only fills a missing tail on a fresh, unedited, live entry and keeps the
merchant; the summary posts inside the locked DONE save). `possible_repeat` review items are built from typed fields
with a masked tail; no repeat check edits, restores or deletes an existing entry; soft-deleted twins only get a
BACKFILL source. FINDINGS: [MINOR] the 30-day memory kept message times in clear next to the HMAC keys; [MINOR] review
mark and lookup used different sender lengths; [NIT] pruning on every mark. All fixed in the later rounds (the memory
values are sealed under the phone-bound LocalKeystore; the run state is coarse days only).
STATUS: APPROVE
