---
id: STORY-208
epic: EPIC-18
title: Needs-an-answer cards - link a refund, sort a transfer between own accounts, an unknown card, ATM cash
phase: 7
status: DONE
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-205, STORY-206]
reqs: [REQ-EXP-064, REQ-EXP-065, REQ-EXP-066, REQ-EXP-067]
implements: ["Say teardown item 21 (refund linking, transfer check-ins, unknown numbers in SMS, ATM cash)", "Say defect: transfers counted as spending"]
owner_agent: android-engineer
---

# STORY-208 - Needs-an-answer cards - link a refund, sort a transfer between own accounts, an unknown card, ATM cash

## Goal
Some captured money is ambiguous: a refund for what, a transfer to whom, a card the app does not know, cash taken from
an ATM. Wallet shows one short card per open question; one tap answers it and the numbers become right (Say counts
such transfers as spending; Masroof must not).

## Requirements and acceptance criteria
### REQ-EXP-064 - Link a refund to the purchase it reverses
**Given** a captured REFUND (or an INCOME from a merchant key that has an EXPENSE in the last 60 days) of 450 EGP
**Then** a card asks "Is this a refund for Amazon 1,200 on 3 Nov?" listing up to 3 candidates (same merchant key, amount
>= refund, newest first) plus "Pick another" and "Not a refund". Linking sets the refund's type REFUND and category to the
purchase's (so it nets in the right category under the D2 rule) and stores the link in `expense_source`-independent
fields chosen by the architect without a schema change (e.g. a note key or a DataStore map; record the choice).
Unhappy path: "Not a refund" keeps it as INCOME and the card never returns for that entry.

### REQ-EXP-065 - A transfer between own accounts is never spending or income
**Given** a captured outgoing TRANSFER of 5,000 from CIB and accounts CIB and NBE exist **Then** a card asks "Did this go
to one of your accounts?" with the accounts; choosing NBE keeps the CIB entry as TRANSFER and adds an incoming entry on
NBE of type `TRANSFER_IN` (a new `expense.type` value; no schema change) linked to it, so CIB's balance drops, NBE's
rises, and neither spending nor income changes. An incoming captured transfer asks the mirror question. "To someone else"
keeps the TRANSFER as today. The D2 filter (and every total) treats `TRANSFER_IN` like TRANSFER: never counted.
Unhappy path: deleting one side of a linked pair asks whether to delete both; older app versions never see
`TRANSFER_IN` (Room only moves forward), and unknown type strings read from a restored backup map to TRANSFER safely.

### REQ-EXP-066 - A card number the app does not know
**Given** a capture whose last four (e.g. 1898) matches no account **Then** a card "•••• 1898 isn't in Masroof" offers
Add account (pre-filled last four), Link to an existing account (adds it as that account's alternate last four via
the account form), or Ignore this card (stored in DataStore; later captures from it go to the review list marked
"ignored card" instead of becoming entries). Settings -> Ignored cards lists and restores them.
Unhappy path: only the last four is ever shown or stored, never more digits.

### REQ-EXP-067 - ATM cash: track it or count it
**Given** a CASH_WITHDRAWAL of 2,000 from CIB and no Cash account **Then** a card asks "Keep track of this cash?":
"Track it" creates a Cash account (if none), turns the withdrawal into a TRANSFER out of CIB plus a TRANSFER_IN to Cash
(cash then spent is entered from the Cash account), and remembers the choice for future withdrawals; "Count as spending"
keeps today's behaviour and sets the existing "Count ATM withdrawals as spending" setting on. The choice can be changed
in Settings.

## UX states (UI stories only)
A horizontally scrolling "Needs an answer" row on Wallet (hidden when empty), each card with its question, a short
excerpt of the structured record (merchant, amount, date - never message text), and 2-3 answers; a pick-entry sheet; an
account picker. RTL, dark. Strings in `strings_answers.xml`.

## Contract impact
None.

## Data / migration impact
No schema change: new `expense.type` value `TRANSFER_IN`; link storage chosen by the architect without a migration
(record in DECISIONS). Backup (STORY-183) and CSV (STORY-185) must accept the new type.

## Security / privacy / LLM-safety impact
Touches capture outcomes (ignored cards) and the spending rule: security reviewer checks that no message text is shown
or stored and that ignored-card captures still respect the OTP/promo filters.

## Eval impact
None for the LLM harness; capture fixture tests for ignored cards.

## Design notes (architect)
Card generation is a pure function over recent entries and accounts (unit-tested); answers are single Room transactions
with Undo. Analytics parity keeps passing; new tests cover TRANSFER_IN in the D2 filter and in balances.

## Dependencies
STORY-205, STORY-206

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size: M+ (cards ~250, answers ~250, UI ~250, tests ~400)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-11-08 | 1 spec | orchestrator | READY - EPIC-18 wave 4 |
| 2026-11-08 | 5 build | android-engineer | answer cards: refund link, own-account transfers (TRANSFER_IN), unknown card, ATM cash |
| 2026-11-08 | 6 verify | qa-verifier | loops 1-4 FAIL (transfer/ATM numbers, then undo/repeat, then linked-row edits and write paths) |
| 2026-11-08 | 3 design | orchestrator | loop 4 and 5 allowed past the limit; reconcile from the stored link, locked money fields, one write path |
| 2026-11-08 | 6 verify | qa-verifier | loop 5 PASS on 4177e71; MINOR follow-ups fixed (6abaaca), diff reviewed |
| 2026-11-08 | 7 review | security-reviewer | round 1 APPROVE (d2c66c9), round 2 APPROVE (6abaaca); MINOR fixed in 1532496 |
| 2026-11-08 | 8 integrate | orchestrator | DONE |
