---
name: next
description: Picks the highest-priority READY story whose dependencies are all DONE (lowest phase, then lowest id) and runs the /story pipeline on it. Use when the owner says /next or "continue with the backlog"; never while another story is IN_PROGRESS in state.json.
argument-hint: "[EPIC-NN | module hint]"
disable-model-invocation: true
user-invocable: true
allowed-tools: Bash(bash *), Bash(git *), Read, Grep, Glob
---

# /next — pick and run the next story

Candidates computed by [candidates.sh](candidates.sh) when this skill loaded:

!`bash "${CLAUDE_SKILL_DIR}/candidates.sh"`

## Procedure
1. If `state.story` is not `null`: do not pick a new story. Say which story is active and run `/story <that id>`
   to resume it (the pipeline re-reads `stage` from `state.json` and continues from there).
2. Eligibility is already computed above (READY and every `depends_on` id in DONE). Additionally a Phase N+1
   story is not eligible while `docs/phases/phase-N-report.md` is missing or its verdict is RED: the phase gate
   is the approval. Phase 0 stories always come first.
3. Order: lowest `phase`, then lowest numeric id (the list is already sorted). If the owner passed a hint in
   `$ARGUMENTS` (an epic id or a module name), prefer a matching eligible story but never break the rules above.
4. Nothing eligible: say so, show the "waiting on" list and the open escalations, and suggest exactly one of
   `/spec <epic>` (no stories for this phase), `/phase-gate <n>` (all phase stories DONE), or "resolve <id>".
   Never pick a BLOCKED or IN_PROGRESS story here; never idle silently.
5. Announce the pick in one line (`id · title · phase · modules · size`), then invoke `/story <id>` and follow
   that skill to its exit gate.
6. When `/story` ends (DONE or BLOCKED): report the result in one line and stop, unless the owner asked to
   "run the backlog"; then continue with the next eligible story, keeping a one-line-per-story running summary,
   and stop when nothing is eligible or an escalation needs the owner.
