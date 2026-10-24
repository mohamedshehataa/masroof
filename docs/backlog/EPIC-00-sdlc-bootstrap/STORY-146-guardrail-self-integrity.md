---
id: STORY-146
epic: EPIC-00
title: "Guardrail self-integrity follow-up (STORY-001 accepted risks F-01..F-04)"
phase: 1
status: DRAFT
size: M
modules: [sdlc]
depends_on: [STORY-001]
reqs: [REQ-OPS-168, REQ-OPS-169, REQ-OPS-170, REQ-OPS-171]
implements: ["PROMPT.md S7", "STORY-001 accepted residual risk"]
owner_agent: devops-engineer
---

# STORY-146 — Guardrail self-integrity follow-up

## Goal
Close the four residual risks STORY-001 was accepted with, when the owner schedules it. Starting point: unverified commit f54c33a on local branch `story-001-loop4` (HEAD-anchored self-integrity check that fails closed, per-call snapshots, case-insensitive B13, command-substitution delete targets).

## Requirements and acceptance criteria
### REQ-OPS-168 — Hooks verify their own files against git HEAD and fail closed
(to be specified by /spec) Source: STORY-001 verify loop 3 F-01.
### REQ-OPS-169 — Per-call snapshots catch writes between and across calls
(to be specified by /spec) Source: F-02.
### REQ-OPS-170 — B13 interpreter and .NET writes match protected paths case-insensitively
(to be specified by /spec) Source: F-03.
### REQ-OPS-171 — Command-substitution delete targets are blocked as unresolvable
(to be specified by /spec) Source: F-04.

## UX states (UI stories only)
N/A

## Contract impact
None

## Data / migration impact
None

## Security / privacy / LLM-safety impact
Hardens the local guardrails (full pipeline: security-reviewer required).

## Eval impact
None

## Design notes (architect)
To be done at phase start. Reuse f54c33a only after review.

## Dependencies
STORY-001 (DONE with accepted risk).

## Definition of Ready checklist
- [ ] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [ ] Security / privacy / LLM-safety impact
- [x] Eval impact
- [ ] Size ≤ 400 changed lines
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-24 | 1 intake | orchestrator | DRAFT created from STORY-001's accepted residual risk |
