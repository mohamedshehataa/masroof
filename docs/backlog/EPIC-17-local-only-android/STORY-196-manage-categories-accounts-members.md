---
id: STORY-196
epic: EPIC-17
title: Manage categories, payment accounts and members on the phone
phase: 2
status: DRAFT
risk_tier: low
size: M
modules: [android]
depends_on: [STORY-170]
reqs: [REQ-EXP-050, REQ-EXP-051]
implements: ["PROMPT.md §11 More (categories, accounts & cards, household & members) on the device per ADR-0009 D3"]
owner_agent: android-engineer
---

# STORY-196 - Manage categories, payment accounts and members on the phone

> **DRAFT stub** - reuses STORY-040 / STORY-013 / STORY-014 / STORY-015 intent (descoped, server CRUD). REQ ids reserved.

## Goal
The household adjusts its category list, its cards and wallets, and member names without a server.

## Requirements and acceptance criteria
### REQ-EXP-050 - Categories: add, rename (ar/en), reorder, colour, archive; system categories archive but never delete
Given More -> Categories, when the user adds a subcategory or renames/archives one, then pickers and Insights follow;
archived categories keep their history and roll up as today; a category in use cannot be deleted, only archived.

### REQ-EXP-051 - Accounts and members: add/rename/archive cards and wallets (kind, institution, last four) and member names
Given More -> Accounts or Members, when the user adds "CIB card ending 4512" or renames "Me" to "Mohamed", then entries,
paid-by chips and capture last-four matching use the new values; the last member cannot be removed.

## UX states (UI stories only)
Lists, empty, edit dialogs, RTL/dark.

## Contract impact
None.

## Data / migration impact
None (v9 columns `parentId`, `color`, `archived`, `system`, `kind`, `institution`, `last4`).

## Security / privacy / LLM-safety impact
Only a last four is stored for cards, never a full number.

## Eval impact
None.

## Design notes (architect)
To be filled.

## Dependencies
STORY-170

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (headlines only)
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [ ] Size ≤ 400 changed lines (estimate M; may split at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | DRAFT stub - EPIC-17 local-only pivot, reuses STORY-040/013/014/015 |
