---
name: spec
description: Runs intake and specification for an epic or a feature (product-analyst slices and writes REQs with Given/When/Then, spec-reviewer reviews in a fresh context, max 2 loops). Use when the owner says /spec EPIC-NN, /spec "<feature>", or when /story meets a DRAFT story; stages 1-2 only, no design or code.
argument-hint: "EPIC-NN | STORY-NNN | \"<feature description>\""
disable-model-invocation: true
user-invocable: true
---

# /spec $ARGUMENTS — stages 1-2 (intake, slicing, specification, spec review)

## 0. Scope
- `STORY-NNN` -> spec that one story file (find it with `ls docs/backlog/*/STORY-NNN-*.md`).
- `EPIC-NN` -> slice the epic folder `docs/backlog/EPIC-NN-<slug>/`; create it from Part I if it is missing.
- Free text -> product-analyst decides the epic (existing or new, next number `ls -d docs/backlog/EPIC-* | wc -l` + 1).
- `source scripts/env.sh`; `bash scripts/sdlc/state.sh set stage spec` (leave `story` as is unless /story set it).
- Work on `main` for backlog-only changes, or on the current story branch when called from /story.

## 1. Intake and slicing — agent `product-analyst`
Prompt from [../story/prompts/product-analyst.md](../story/prompts/product-analyst.md). For an epic or free text
add: "Slice into stories of size S/M, one vertical slice each, ordered by dependency and value; create one
`STORY-NNN-<slug>.md` per story from `docs/templates/story.md`, ids global and sequential; set `depends_on`
explicitly; fill at least the next phase's stories to the Definition of Ready; later ones may stay DRAFT with a
goal line." Expect `STATUS: SPEC_READY` with `STORIES:` and `REQS:` lists.

## 2. Spec review — agent `spec-reviewer` (fresh context)
For each story the analyst marked READY, launch the reviewer with
[../story/prompts/spec-reviewer.md](../story/prompts/spec-reviewer.md) (stories of one epic may be reviewed in
parallel, one agent each, in one message). Pass only the story path and PROMPT.md section refs.
- `APPROVE` -> keep `status: READY`.
- `CHANGES_REQUESTED` -> relaunch product-analyst for that story with the FINDINGS verbatim, then review
  again. Max 2 loops per story; after that set `status: DRAFT`, add a Status log row with the open findings and
  an escalation entry (see [../story/escalation.md](../story/escalation.md), reason "spec not converging").

## 3. Record
1. Validate frontmatter of every touched story: `python -c "import yaml,sys,glob;[yaml.safe_load(open(f,encoding='utf-8').read().split('---')[1]) for f in glob.glob('docs/backlog/*/STORY-*.md')]"`.
2. Check REQ id uniqueness: `grep -rhoE 'REQ-[A-Z]+-[0-9]{3}' docs/backlog | sort | uniq -d` must print nothing
   (the same id may appear in one story only; cross-references use "see STORY-NNN").
3. PROGRESS line: `<ts> · P<phase> · <STORY or SDLC> · spec · INFO · <n> stories READY, <m> DRAFT (<epic>)`.
4. Commit: `docs(backlog): spec <EPIC or STORY> [STORY-NNN]` (use `[SDLC]` for epic-wide slicing with no single story).

## Exit gate
Every story you report as READY has all Definition of Ready boxes ticked and a spec-reviewer `APPROVE`
(quote its STATUS line). Otherwise it is DRAFT, and you say why.
