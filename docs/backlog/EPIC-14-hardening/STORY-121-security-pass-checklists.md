---
id: STORY-121
epic: EPIC-14
title: "Security pass: ASVS L2 / MASVS-L1 / OWASP LLM checklists closed; transport hardening; scanning in CI"
phase: 9
status: DRAFT
size: M
modules: [backend, infra]
depends_on: [STORY-010, STORY-075, STORY-055]
reqs: [REQ-SEC-040, REQ-SEC-041, REQ-OPS-090]
implements: [PROMPT.md §12, PROMPT.md S9 (Security), PROMPT.md §14 Phase 9]
owner_agent: devops-engineer
---

# STORY-121 — Security pass: ASVS L2 / MASVS-L1 / OWASP LLM checklists closed; transport hardening; scanning in CI

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 9. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The product is audited against the named standards before release (PROMPT §12, §14).

## Requirements and acceptance criteria
### REQ-SEC-040 — docs/security checklists (ASVS L2, MASVS-L1 + selected L2, OWASP LLM Top 10) every item closed or justified; threat model updated
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SEC-041 — HTTPS only, HSTS preload-ready, CORS verified, secure cookie/bearer handling re-audited; ZAP baseline clean
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-OPS-090 — Dependabot/Renovate, CodeQL, Trivy, osv-scanner, gitleaks all gating in CI; license check (no AGPL/GPL without ADR)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
This story is the security gate; `security-reviewer` signs off.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-010, STORY-075, STORY-055

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 9 start |
