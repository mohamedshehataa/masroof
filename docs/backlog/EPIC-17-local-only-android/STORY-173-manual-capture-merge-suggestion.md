---
id: STORY-173
epic: EPIC-17
title: A manual entry that looks like a captured one is offered as a possible duplicate
phase: 6
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-172]
reqs: [REQ-DEDUP-063, REQ-DEDUP-064, REQ-DEDUP-065]
implements: ["ADR-0009 D1 §7 (manual entry that looks like a capture gets a merge suggestion; no auto-merge of manual entries)", "PROMPT.md §7 (possible duplicate card, Merge / Keep both)"]
owner_agent: android-engineer
---

# STORY-173 - A manual entry that looks like a captured one is offered as a possible duplicate

## Goal
Mohamed sometimes types a purchase by hand and the bank SMS arrives too (or the other way round). The phone never
merges these silently; it shows a "Possible duplicate" card in the Needs review list with Merge and Keep both, so the
month total counts the purchase once. On-device reduction of PROMPT §7 (ADR-0009 D1); reuses the side-by-side card idea
of STORY-089 (descoped).

## Requirements and acceptance criteria
### REQ-DEDUP-063 - A look-alike pair becomes a "Possible duplicate" card
**Given** a manual entry (source `MANUAL`) of 1,450 EGP, type EXPENSE, dated today without a time (`timeKnown = 0`), and a
captured entry (source `SMS` or `PUSH`) of 1,450 EGP, type EXPENSE, at 14:05 Cairo time today **When** the second of the
two is saved (either order) **Then** both entries stay in the lists and totals as they are, and one "Possible duplicate"
card appears in Needs review showing both side by side (amount, date/time, merchant, category, source icons) with
Merge and Keep both. The rule for a pair: same currency and `amountMinor`, both not deleted, one has a `MANUAL` source and
the other only capture sources, and either the manual entry has `timeKnown = 0` and the capture falls on the same Cairo
calendar day, or both are timed and at most 2 hours apart.
Unhappy path: **Given** amounts 1,450 vs 1,450.50, different currencies, a capture on the previous Cairo day (23:55 vs a
manual entry dated today), or two manual entries **Then** no card appears.
Unhappy path: **Given** a manual entry that matches two captures **Then** one card per pair appears and merging one
removes the other card (a manual entry merges at most once).
Unhappy path: **Given** a Cairo-midnight edge (capture at 00:01 Cairo on day D+1, stored as 22:01 UTC of day D) and an
untimed manual entry on day D **Then** no card appears (days are Cairo days, not UTC days).

### REQ-DEDUP-064 - Merge keeps one entry with all its sources
**Given** a "Possible duplicate" card **When** the user taps Merge **Then** one entry survives (the captured one) with the
capture's amount, time, last four and payment method, plus the manual entry's category, notes and paid-by where the
captured entry has none; every `expense_source` of the manual entry is re-pointed to the survivor; the manual entry is
soft deleted; receipts of either are attached to the survivor; the total counts the purchase once; the card is gone; a
snackbar "Merged - Undo" restores both entries and their sources when tapped.
Unhappy path: **Given** one of the two entries was deleted or edited to another amount since the card appeared **When**
Merge is tapped **Then** nothing is merged, the card disappears and "This suggestion is out of date" is shown.

### REQ-DEDUP-065 - Keep both is remembered and nothing is ever merged automatically
**Given** a card **When** the user taps Keep both **Then** the card disappears, both entries stay, and the same pair never
produces a card again (also after an app restart). **Given** any pair that matches REQ-DEDUP-063 **Then** no merge
happens without a tap on Merge.
Unhappy path: **Given** the user dismisses the card by swiping (if the list supports swipe) **Then** it counts as Keep
both.

## UX states (UI stories only)
- Needs review: the card sits with message review items, newest first; count badge includes it.
- empty: no card when nothing matches. loading: from Room. error: "Couldn't merge, try again" keeps the card.
- offline: identical. RTL: the two columns swap sides, amounts keep their digits setting; dark theme; TalkBack reads
  "Possible duplicate: 1,450 EGP manual entry and 1,450 EGP SMS from CIB".

## Contract impact
None.

## Data / migration impact
None expected (v9 `expense_source` re-pointing and soft delete). If remembering "Keep both" pairs needs storage, the
architect places it (EPIC-17 README "Open design points").

## Security / privacy / LLM-safety impact
No message text is shown on the card (typed fields only). No new permission.

## Eval impact
None for the LLM harness. Dedup rules are covered by Kotlin unit tests (pairs above incl. the Cairo-midnight case).

## Design notes (architect)
ADR-0009 D1 §7 and D3 (`expense_source`: a merge re-points a source; "keep both" leaves them apart).

## Dependencies
STORY-172

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: matcher ≈ 50, merge ≈ 60, card ≈ 60, tests ≈ 200)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D1 §7) |
