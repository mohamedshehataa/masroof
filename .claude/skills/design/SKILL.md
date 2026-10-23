---
name: design
description: Runs the design stage for one READY story (architect writes design notes, ADR if needed, contract and migration plan; security-reviewer signs off on threat notes when trigger paths are touched; oasdiff checks the contract). Use when the owner says /design STORY-NNN or from /story stage 3.
argument-hint: "STORY-NNN"
disable-model-invocation: true
user-invocable: true
---

# /design $0 — stage 3

## 0. Preconditions
- Story file `ls docs/backlog/*/$0-*.md`; `status` READY or IN_PROGRESS with Definition of Ready fully ticked
  (otherwise run `/spec $0` first).
- On the story branch `story/$0-<slug>` (create from `main` if missing, see /story step 0.5).
- `source scripts/env.sh`; `bash scripts/sdlc/state.sh set story $0; ... set stage design`.
- `NEXT_ADR=$(printf '%04d' $(( $(ls docs/adr/*.md 2>/dev/null | wc -l) + 1 )))` (ADR ids are sequential;
  verify against the highest existing number, not just the count).

## 1. Architect — agent `architect`
Prompt from [../story/prompts/architect.md](../story/prompts/architect.md) with the story path, contract,
module CLAUDE.md files, `NEXT_ADR`, threat model. Expect `STATUS: DESIGN_READY` with `DESIGN`, `CONTRACT_IMPACT`,
`MIGRATION_IMPACT`, `THREATS` sections.
- `BLOCKED` naming a spec defect -> `/spec $0` (counts as a spec loop), then relaunch.

## 2. Contract gate (if `CONTRACT_IMPACT` is not `none`)
```bash
make contract-lint
make contract-diff BASE=main
```
Breaking change (oasdiff says so) with no ADR named in the report -> send the architect back once with the
oasdiff output; a second breaking-without-ADR result -> escalate. Additive changes pass.
Generated code is never hand-edited: regeneration happens in stage 5 by the implementer.

## 3. Security sign-off (if triggered)
Match the story's modules, the design's paths and `git diff --name-only main` against the `security-reviewer`
row of [../story/triggers.md](../story/triggers.md), or if `THREATS` is not `none`. Launch `security-reviewer`
in design mode with [../story/prompts/security-reviewer.md](../story/prompts/security-reviewer.md).
`CHANGES_REQUESTED` -> architect fixes -> re-review; max 2 loops, then escalate.
`THREAT_MODEL_UPDATED: required` -> architect adds it (its write scope includes docs/).

## 4. Record
- Story file must now have filled "Design notes (architect)", "Contract impact", "Data / migration impact";
  Status log row `| <date> | design | architect | DESIGN_READY (+ security APPROVE) |`.
- Validate: the migration names in `MIGRATION_IMPACT` do not exist yet
  (`ls backend/src/main/resources/db/migration/ | grep V<N>__` prints nothing) and no existing `V*` file is in
  `git status --porcelain`.
- PROGRESS line: `<ts> · P<phase> · $0 · design · INFO · DESIGN_READY; contract <none|additive|breaking ADR-NNNN>; security <n/a|APPROVE>`.
- Commit: `docs($0): design [$0]` including `contracts/openapi.yaml`, `docs/adr/<NEXT_ADR>-*.md`,
  `docs/security/THREAT_MODEL.md` when touched. `make check-fast MODULE=sdlc` must be green (contract lint is part of it).

## Exit gate
`DESIGN_READY` quoted; contract diff green (or ADR present for a breaking change); security `APPROVE` when
triggered; design notes present in the story file. Next: `/tdd $0`.
