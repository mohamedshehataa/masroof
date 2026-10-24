---
name: ticketed-followups-into-story
description: Review/verify findings "ticketed -> STORY-NNN" must be written into that story as REQs; a ticket that only lives in the .review.md gets lost
metadata:
  type: feedback
---

When a `.review.md` / `.verify.md` row says "ticketed → STORY-NNN", the target story must carry it as an explicit `### REQ-...` (new number, next free in area) with probes and unhappy paths, plus an `implements:` entry citing the finding number.

**Why:** STORY-002 verify (2026-10-24) flagged that money-rule and V1-DDL follow-ups ticketed to STORY-004 existed only in the review report; the orchestrator had to request the amendment.

**How to apply:** On any spec/amend run, grep `docs/backlog/**/*.review.md` and `*.verify.md` for `ticketed` naming the story being sliced or amended, and fold each into REQs. When the additions push size near 400, write down a split boundary in the DoR size line. Do not silently tick the size box.
