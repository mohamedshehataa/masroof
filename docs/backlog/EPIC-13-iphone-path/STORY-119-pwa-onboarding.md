---
id: STORY-119
epic: EPIC-13
title: "PWA/iPhone onboarding: accept invite → password → language → Add to Home Screen → join WhatsApp → Shortcut guide"
phase: 8
status: DRAFT
size: M
modules: [web]
depends_on: [STORY-019, STORY-113, STORY-078, STORY-118]
reqs: [REQ-PWA-060]
implements: [PROMPT.md §11 Onboarding (PWA/iPhone)]
owner_agent: web-engineer
---

# STORY-119 — PWA/iPhone onboarding: accept invite → password → language → Add to Home Screen → join WhatsApp → Shortcut guide

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 8. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Member 2 is fully set up in one flow (PROMPT §11).

## Requirements and acceptance criteria
### REQ-PWA-060 — Guided, resumable onboarding steps per §11 with skip options and completion state
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
Uses `/api/settings` link status.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-019, STORY-113, STORY-078, STORY-118

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 8 start |
