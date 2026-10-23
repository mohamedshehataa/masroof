---
id: STORY-082
epic: EPIC-08
title: "Voice notes: speech-to-text (Egyptian Arabic) then the text parser"
phase: 5
status: DRAFT
size: M
modules: [backend, eval]
depends_on: [STORY-076, STORY-077]
reqs: [REQ-WA-070, REQ-WA-071, REQ-WA-072]
implements: ["PROMPT.md §3.4 (Voice note: 'اشتريت خضار بميتين وخمسين جنيه')", PROMPT.md §2 (Voice notes)]
owner_agent: ai-parsing-engineer
---

# STORY-082 — Voice notes: speech-to-text (Egyptian Arabic) then the text parser

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 5. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Member 2 can speak an expense into the group (PROMPT §3.4).

## Requirements and acceptance criteria
### REQ-WA-070 — Audio media transcribed by a configurable STT adapter (Whisper API or faster-whisper) handling Egyptian Arabic
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-071 — Transcript goes through the same text pipeline; reply quotes the transcript
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-072 — Voice ingestion-to-entry p95 < 45 s; STT failure → reply asking to type it
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
media.ocr_text reused for transcript or new `transcript` column.

## Security / privacy / LLM-safety impact
Audio sent to STT provider (key in .env, `TODO(owner)`); transcript treated as untrusted text.

## Eval impact
Suite `voice` 5 → 25.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-076, STORY-077

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 5 start |
