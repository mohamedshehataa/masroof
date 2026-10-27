---
id: STORY-197
epic: EPIC-17
title: Scan a paper receipt with the document scanner
phase: 3
status: DRAFT
risk_tier: low
size: S
modules: [android]
depends_on: [STORY-175]
reqs: [REQ-RCPT-024]
implements: ["ADR-0009 D4 (ML Kit GmsDocumentScanning, optional add-on with its own DECISIONS line)"]
owner_agent: android-engineer
---

# STORY-197 - Scan a paper receipt with the document scanner

> **DRAFT stub** - reuses the scanner part of STORY-061 (descoped). Needs a DECISIONS line for the Play-services
> dependency when picked up. REQ id reserved.

## Goal
A crumpled receipt becomes a flat, cropped image or PDF in one step.

## Requirements and acceptance criteria
### REQ-RCPT-024 - "Scan" opens ML Kit document scanning; the result is stored through the STORY-174 rules
Given Google Play services with the scanner module, when the user taps Scan, then the scanned pages come back as JPEG or
PDF and are validated, re-encoded and stored exactly as REQ-RCPT-016/017; no CAMERA permission is added; without Play
services the Scan option is hidden. No text is read from the scan in v1.

## UX states (UI stories only)
Scan option, unavailable state, RTL/dark.

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
New dependency (ML Kit document scanner) runs in Play services; scan output treated as untrusted input like any picker
file.

## Eval impact
None.

## Design notes (architect)
ADR-0009 D4 "Document scanner".

## Dependencies
STORY-175

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (headline only)
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [ ] Size ≤ 400 changed lines (estimate S; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | DRAFT stub - EPIC-17 local-only pivot, reuses STORY-061 scanner |
