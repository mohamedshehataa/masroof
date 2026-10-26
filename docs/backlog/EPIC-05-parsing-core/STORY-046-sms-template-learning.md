---
id: STORY-046
epic: EPIC-05
title: "SMS template learning: propose a regex after 3+ LLM parses of the same sender format (feature-flagged)"
phase: 3
status: IN_PROGRESS
size: M
modules: [backend, android]
depends_on: [STORY-044, STORY-045]
reqs: [REQ-SMS-014, REQ-SMS-015, REQ-SMS-070, REQ-SMS-071, REQ-SMS-072, REQ-SMS-073, REQ-SMS-074, REQ-SMS-075, REQ-SMS-076, REQ-SMS-077, REQ-SMS-078, REQ-SMS-079, REQ-SMS-080, REQ-SMS-081, REQ-SMS-082, REQ-SMS-083, REQ-SMS-084, REQ-SMS-085, REQ-SMS-086]
implements: [PROMPT.md §4.3 (parsing strategy 3), PROMPT.md S12 (feature flags for template learning)]
owner_agent: ai-parsing-engineer
---

# STORY-046 — SMS template learning: propose a regex after 3+ LLM parses of the same sender format (feature-flagged)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
New SMS formats become free deterministic templates over time, with the admin in the loop (PROMPT §4.3 step 3).

## Round 15 note (PR #44, on-device half shipped)
The essence of this story is now implemented ON THE PHONE, because no SMS text may reach the server (STORY-065 design): a
message outside the proven families (card purchase, HSBC account line, instant-transfer bank shapes, InstaPay app
notifications) becomes a local review item with the engine's suggestion; saving it stores a LEARNED TEMPLATE in Room (sender +
a digit-free, name-free shape with typed slots + the confirmed kind and, if the user corrected it, the number slot that held
the amount or last four). The next message of that sender with exactly that shape is recorded without asking. Templates never
leave the phone, are wiped on logout, and are listed and cleared in Settings ("Learned SMS formats"). Tests: `SmsLearningTest`
(REQ-SMS-070..075). What remains of this story is the server-side proposal flow (admin approval of `sms_template` rows), which
stays DRAFT and is only useful for text the server is allowed to see (WhatsApp, receipts), not bank SMS.

## Requirements and acceptance criteria
### REQ-SMS-070 — Only a proven family is uploaded without a human; every other parsed message is a review item with a one-tap suggestion
Given a message that is not a proven family (card purchase, HSBC account line, instant-transfer bank shape, InstaPay app notification) and not a learned format, when it arrives, then nothing is queued, a review item holds the engine's suggestion (kind, amount, last four, label, date), and Save uploads exactly those typed fields once as an expense with source SMS.

### REQ-SMS-071 — Confirming a review item teaches the phone that format for that sender only
Given the user saved a review item, when a later message of the same sender has exactly the same shape, then it is recorded with the confirmed kind; a different sender or a changed shape still goes to review.

### REQ-SMS-072 — A corrected amount or last four is remembered as a number slot
Given the user saved the expense with another amount, when the same shape arrives again, then the amount is read from the slot that held the corrected value; if no slot matched, the engine's own pick is used.

### REQ-SMS-073 — A learned shape holds no digit and no name, and a secret-looking message is never learned
Given any message, when its shape is stored, then every number, date, time, masked id and non-banking word is a typed slot; given a message that looks secret, then it has no shape and nothing is learned.

### REQ-SMS-074 — Learned formats are wiped on logout and can be cleared in Settings
Given learned formats, when the user logs out or taps Clear, then none remain and the next message of that format goes to review again.

### REQ-SMS-076 — A non-completion status anywhere blocks every automatic record and every learning
Given a message that says the operation failed, was declined, cancelled, reversed, returned, is pending, on hold, scheduled or requested (English or Arabic), when it arrives, then it is never recorded by a family or a learned template and never learned; given a learned format, a Dr/Cr or +/- difference, a status word next to a name, or a template whose direction disagrees with the engine's reading all go to review.

### REQ-SMS-077 — Review slots never keep a code, and only an unchanged one-tap Save teaches
Given a secret-looking or code-like review item, when it is stored, then no slots are kept (a code-like run is masked) and they are dropped when the item is saved or dismissed; given a hand-edited save, then nothing is learned.

### REQ-SMS-078 — Closed world: only a name, then a closed tail, may follow the amount of an automatic record
Given a proven family or a learned format, when anything other than a date, a time, "via InstaPay", "successfully" or a reference follows the merchant or counterparty (any other word, a lowercase Latin word, an emoji, "?" or "!", Latin mixed with Arabic, more tokens than the slot allows), then the message is a review item; given a learned name slot, then only a name of the same token count matches, and a word that is never a name ("cashback", "return") never does.

### REQ-SMS-079 — The Room schema keeps the review list when the captured records are added
Given a database at version 5 with review items, when it opens at version 6, then the review items are kept and automatic records can be stored.

### REQ-SMS-080 — Every automatic record is remembered for Undo
Given a message recorded without confirmation (a proven family or a learned format), when it is queued, then a captured record with amount, currency, label and the learned template (if any) is kept for 7 days and announced once; a review item and a one-tap Save are never announced.

### REQ-SMS-081 — Undo removes the record, or deletes the expense, and may forget the format
Given a captured record, when the user taps Undo, then a record still in the outbox is removed from it, an uploaded one is deleted through the API (the server returns the created expense id on ingest), a failed delete leaves everything as it was, and for a learned format the user is asked "Stop learning this format?".

### REQ-SMS-082 — The notification is quiet, optional and holds no message text
Given the "Recorded 500 EGP at AMAZON - Undo" notification, then it is on the low-importance channel "Captured transactions" without sound or vibration, holds only amount, currency and label, can be turned off in Settings, and is not posted without the notification permission.

### REQ-SMS-083 — The Captured chip
Given an expense the phone captured itself in the last 7 days, when it is listed in Home or Transactions, then its row shows a "Captured" chip with an Undo action.

### REQ-SMS-084 — The Room schema keeps the captured records when the Undo request flag is added
Given a database at version 6 with captured records, when it opens at version 7, then the records are kept without an Undo request and one can be recorded.

### REQ-SMS-085 — The review and add screens cannot be screenshotted
Given the review list or the add form is shown, when the window is created, then it is FLAG_SECURE (and the recents thumbnail is hidden on Android 13+), and normal again when the screen is left.

### REQ-SMS-086 — This app's own notifications are never read, and codes are masked before WorkManager
Given a notification of this app's package or a message with a code-like digit run, when it is handed to WorkManager, then the own package is denied, and a long digit run that is not an amount is masked in the input data while the card's last four and the amount stay.

### REQ-SMS-075 — A proven family is recorded without confirmation and is never learned from
Given a card-purchase message of family F1, when it arrives, then it is queued directly and no review item or template is created.

### REQ-SMS-014 — After 3 successful LLM parses sharing a sender and skeleton, a PROPOSED template is generated and validated against those messages
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SMS-015 — Admin approves/rejects proposals (PATCH status); flag `parsing.templateLearning` default off in prod
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
Uses `/api/sms-templates` PATCH (v0).

## Data / migration impact
None beyond `sms_template`.

## Security / privacy / LLM-safety impact
Proposed regexes are generated from untrusted text; must be validated (anchored, bounded) before activation.

## Eval impact
Suite `sms`: no regression after a template is activated; calibration metric.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-044, STORY-045

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (reserved, to be written by /spec)
- [x] UX states (UI stories)
- [x] Contract impact (headline)
- [x] Data / migration impact (headline)
- [x] Security / privacy / LLM-safety impact (headline)
- [x] Eval impact (headline)
- [ ] Size ≤ 400 changed lines (estimate M; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 3 start |
