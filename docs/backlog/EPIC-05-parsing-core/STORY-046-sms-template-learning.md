---
id: STORY-046
epic: EPIC-05
title: "SMS template learning: propose a regex after 3+ LLM parses of the same sender format (feature-flagged)"
phase: 3
status: IN_PROGRESS
size: M
modules: [backend, android]
depends_on: [STORY-044, STORY-045]
reqs: [REQ-SMS-014, REQ-SMS-015, REQ-SMS-070, REQ-SMS-071, REQ-SMS-072, REQ-SMS-073, REQ-SMS-074, REQ-SMS-075]
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
