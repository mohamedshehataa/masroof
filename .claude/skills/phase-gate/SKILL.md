---
name: phase-gate
description: Checks every exit criterion of a Part I build phase (PROMPT.md section 14 plus S9/S10 thresholds), runs make check-full, and writes docs/phases/phase-N-report.md from the template with a GREEN or RED verdict. Use when the owner says /phase-gate N or when every story of phase N is DONE; GREEN only if every criterion is green.
argument-hint: "<phase number 0-10>"
disable-model-invocation: true
user-invocable: true
---

# /phase-gate $0

A phase is done only when its gate report is all green (PROMPT.md section 14). This skill never edits the
criteria to fit the result.

## 1. Collect the criteria
1. Read `docs/PROMPT.md` section 14, item `$0` -> the phase description and explicit "Exit:" text.
2. Phase-specific additions: Phase 0 = S15 (every guard PROVEN in `docs/SDLC_SELFTEST.md`, walking-skeleton story
   DONE, backlog READY for Phase 1). Phase 3 = S10.1 Phase-3 dataset sizes and S10.2 gates. Phase 9 = ASVS/MASVS/LLM
   checklists closed, backups + restore test, performance budgets. Phase 10 = release dataset sizes, `/release`
   done, README quick start.
3. Always: every story with `phase: $0` is `DONE` or `DESCOPED` with a reason (`grep -l '^phase: $0' docs/backlog/*/STORY-*.md`
   then check `status:`); each DONE story has `.verify.md` ending PASS and `.review.md` ending APPROVE;
   `make trace` shows 100% of in-scope REQs covered; S9 thresholds for touched modules; eval not regressed;
   open escalations tagged for this phase; `docs/PROGRESS.md` current.
Write the criteria list down first (one row each) before running anything.

## 2. Run the gates (evidence)
```bash
source scripts/env.sh
bash scripts/sdlc/state.sh set stage review     # phase gate is a review stage on SDLC level; story stays null
make check-full 2>&1 | tee "$SCRATCH/phase-$0-check-full.log"; echo "exit=$?"
make trace; make metrics; make eval-fast 2>/dev/null || true
```
`check-full` takes long (nightly budget): run it in the background and keep collecting the other evidence.
If a tool is unavailable locally (emulator, ZAP), use the latest `nightly.yml` run via `gh run list --workflow nightly.yml`
and `gh run view <id> --log` as evidence, and say so in the row.

## 3. Write `docs/phases/phase-$0-report.md`
From `docs/templates/phase-report.md`: Date and Command; Exit criteria table (criterion | GREEN/RED | evidence =
command -> observed line or file path); Stories table (every story of the phase with status, verify, review
results); Metrics (copy the Summary table of `docs/SDLC_METRICS.md` plus eval headline metrics, coverage,
mutation where available); Open escalations (ids); `## Verdict: GREEN` only if every row is GREEN, else `RED`
with the list of red rows.

## 4. Record
- PROGRESS: `<ts> · P$0 · SDLC · review · <GREEN|RED> · phase $0 gate: <n>/<m> criteria green; report docs/phases/phase-$0-report.md`.
- GREEN: `bash scripts/sdlc/state.sh set phase $(( $0 + 1 ))`; append `docs/DECISIONS.md` only if a criterion was
  descoped by owner decision. Then run `/retro`.
- RED: do not bump the phase. Each red row becomes a READY story (or an escalation if only the owner can fix it);
  list them in the report under the verdict. Suggest `/next`.
- Commit: `docs(phases): phase $0 gate report [PHASE-$0]`.

## Exit gate
Report file committed; verdict matches the rows (no GREEN with a red row); state `phase` bumped only on GREEN;
`make check-fast` green before you stop.
