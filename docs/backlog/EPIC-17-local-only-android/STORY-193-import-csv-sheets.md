---
id: STORY-193
epic: EPIC-17
title: Import past expenses from a CSV or the owner's Google Sheets export
phase: 9
status: DRAFT
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-173, STORY-185]
reqs: [REQ-EXP-049]
implements: ["PROMPT.md §9 import (CSV / Google Sheets), on the device per ADR-0009"]
owner_agent: android-engineer
---

# STORY-193 - Import past expenses from a CSV or the owner's Google Sheets export

> **DRAFT stub** - reuses STORY-126 (descoped). REQ id reserved; full Given/When/Then at /spec.

## Goal
The owner's older tracker history joins the phone without retyping.

## Requirements and acceptance criteria
### REQ-EXP-049 - CSV / Sheets export mapped, previewed, validated and imported idempotently
Given a CSV picked with `OpenDocument` (UTF-8 with or without BOM, comma or semicolon) or the owner's Google Sheets
export, when the user maps columns (date, amount, category, merchant, notes) and confirms a preview, then rows become
entries with `expense_source.channel = IMPORT`-style source (architect names it), amounts parsed exactly into minor
units (Arabic-Indic digits, thousands separators), dates as Cairo noon with `timeKnown = 0`, unknown categories mapped or
"Other"; invalid rows are listed and skipped; re-importing the same file adds nothing; look-alikes of existing entries
become "Possible duplicate" cards (STORY-173), never silent merges.

## UX states (UI stories only)
Pick, map, preview, progress, result; RTL/dark.

## Contract impact
None.

## Data / migration impact
A new source channel value may be needed (architect).

## Security / privacy / LLM-safety impact
Untrusted file: size cap, row cap, no formula evaluation, text stored as plain text.

## Eval impact
None.

## Design notes (architect)
To be filled.

## Dependencies
STORY-173, STORY-185

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (headlines only)
- [x] UX states (UI stories)
- [x] Contract impact
- [ ] Data / migration impact (channel value open)
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [ ] Size ≤ 400 changed lines (estimate M; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | DRAFT stub - EPIC-17 local-only pivot, reuses STORY-126 |
