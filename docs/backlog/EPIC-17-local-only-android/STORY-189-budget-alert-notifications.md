---
id: STORY-189
epic: EPIC-17
title: A local notification when a budget reaches 50, 80 or 100 percent
phase: 7
status: DRAFT
risk_tier: low
size: S
modules: [android]
depends_on: [STORY-181]
reqs: [REQ-AN-156]
implements: ["PROMPT.md §10 item 8 alerts (WhatsApp/push replaced by local notifications, ADR-0009 D1 §10)"]
owner_agent: android-engineer
---

# STORY-189 - A local notification when a budget reaches 50, 80 or 100 percent

> **DRAFT stub** - reuses the alert part of STORY-102 (descoped). REQ id reserved; full Given/When/Then at /spec.

## Goal
The household hears about a budget threshold the moment an entry crosses it, from the phone itself.

## Requirements and acceptance criteria
### REQ-AN-156 - Threshold crossing posts one local notification per budget, threshold and period
Given a budget with thresholds 50/80/100 and alerts on, when a saved, captured or edited entry makes `alertsFired` gain a
threshold, then one notification "Groceries: 80% of 4,000 EGP used" is posted on a "Budget alerts" channel, never twice
for the same threshold in the same period, and not for entries dated in an earlier period; POST_NOTIFICATIONS is asked
only when the user turns alerts on.

## UX states (UI stories only)
Settings switch; notification text in ar/en; RTL.

## Contract impact
None.

## Data / migration impact
Remembers fired thresholds per budget and period (architect places it).

## Security / privacy / LLM-safety impact
Notification shows the budget name and amounts only, no merchant text; lock-screen visibility private.

## Eval impact
None.

## Design notes (architect)
To be filled.

## Dependencies
STORY-181

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (headlines only)
- [x] UX states (UI stories)
- [x] Contract impact
- [ ] Data / migration impact (placement open)
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [ ] Size ≤ 400 changed lines (estimate S; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | DRAFT stub - EPIC-17 local-only pivot, reuses STORY-102 alerts |
