<!--
Masroof PR template (PROMPT.md S12). One story per PR, squash-merged, linear history.
Title: Conventional Commit with the story tag, e.g. `feat(sms): parse CIB debit templates [STORY-014]`.
Delete sections that do not apply, but say why (e.g. "No UI change").
-->

## Story

- Story: `docs/backlog/EPIC-xx/STORY-xxx.md` <!-- link to the story file -->
- REQ IDs covered: `REQ-<AREA>-NNN`, ...
- Phase: `PHASE-N`

## Summary

<!-- What changed and why, in 3-6 lines. Link the ADR if a decision was made. -->

## Screenshots (UI changes only)

| | Light | Dark |
|---|---|---|
| LTR (en) | | |
| RTL (ar) | | |

<!-- Also attach font-scale 1.5 for Android screens that show amounts. -->

## Test evidence

- [ ] Tests tagged with the REQ IDs above (unit / property / integration / contract / e2e / eval)
- `make check-fast MODULE=<m>` output or CI link:
- Verify report: `docs/backlog/.../STORY-xxx.verify.md`
- Review report: `docs/backlog/.../STORY-xxx.review.md`

## Eval delta (parsing / prompt / model changes only)

| Metric | Baseline | This PR | Gate |
|---|---|---|---|
| | | | |

<!-- Paste from `make eval-fast`. Rebaseline only via `/eval --rebaseline` with a written reason. -->

## Security notes

<!-- Threat notes from the design stage. Say "none" with a reason if nothing touches auth, ingestion, PII, prompts or secrets. -->
- Trust boundaries touched (docs/security/THREAT_MODEL.md):
- New data flows / PII:
- Scanner findings triaged (semgrep, gitleaks, osv, Trivy):

## Migration notes

<!-- Flyway versions added, backward compatibility with the previous release, data backfill, expected duration. "none" if no schema change. -->

## Rollback plan

<!-- How to undo this if it misbehaves in production: `make rollback` is enough? Migration reversible? Feature flag? -->

## Definition of Done (copied from PROMPT.md S2)

- [ ] All acceptance criteria have passing tests tagged with their REQ IDs
- [ ] `make check` green; coverage and mutation thresholds met for touched modules (S9)
- [ ] Eval harness green with no regression vs baseline (for AI-affecting changes)
- [ ] `qa-verifier` report PASS with evidence; reviewers report no BLOCKER/MAJOR
- [ ] UI: screenshot tests updated for LTR + RTL + dark; axe/accessibility checks clean
- [ ] Docs updated (API.md, setup docs, ADR if a decision was made, CHANGELOG via commit messages)
- [ ] No new `TODO` without a linked story ID; no `@Disabled`/`.skip`/`@Ignore` added
- [ ] `docs/PROGRESS.md` and the story status updated

## Breaking API change?

- [ ] No
- [ ] Yes: label `breaking-change` applied, ADR linked above, clients updated in this PR
