---
name: adr
description: Creates a new Architecture Decision Record from docs/templates/adr.md with the next sequential number, links it to the active story and adds a DECISIONS.md line. Use when the owner says /adr "<title>" or when the architect, a reviewer or an eval rebaseline requires an ADR for a significant or breaking decision.
argument-hint: "\"<decision title>\" [STORY-NNN]"
disable-model-invocation: true
user-invocable: true
allowed-tools: Bash(ls *), Bash(bash *), Read, Write, Edit
---

# /adr $ARGUMENTS

Existing ADRs (highest number wins, not the count):

!`ls -1 "${CLAUDE_PROJECT_DIR}/docs/adr" 2>/dev/null | tail -5`

## Procedure
1. `TITLE` = the quoted argument. `STORY` = the second argument, else `bash scripts/sdlc/state.sh get story`,
   else `n/a`. `N` = highest existing `NNNN` prefix + 1, zero-padded to 4 digits. Slug = lowercase ASCII, hyphens
   (e.g. `0007-fx-rate-storage.md`).
2. Copy `docs/templates/adr.md` to `docs/adr/<NNNN>-<slug>.md` and fill every section; nothing may stay as the
   template's `…` placeholders:
   - Title `# ADR-<NNNN>: <TITLE>`; Status `proposed` (the architect or reviewer who required it flips it to
     `accepted` in the same story); Date today; Story `<STORY>`; Deciders: architect + the reviewers involved.
   - Context: the forces, with links to PROMPT.md sections and the story.
   - Decision drivers, considered options (at least 2 real alternatives with trade-offs), decision outcome with
     "because", consequences (good and bad), validation (which test, metric or gate proves it holds).
   - For breaking contract changes: the oasdiff summary and the client migration path.
   - For new dependencies: license, maintenance signal, size, and the security-reviewer's note.
3. If a previous ADR is superseded: set its Status to `superseded by ADR-<NNNN>` and link both ways.
4. Append to `docs/DECISIONS.md`: `- <date> — <TITLE> — <one-line why>; ADR-<NNNN>`.
5. Mention the ADR in the story's "Design notes (architect)" (if a story is active) and in the PR body later.
6. Commit: `docs(adr): ADR-<NNNN> <short title> [<STORY or SDLC>]`.

## Exit gate
File exists with no template placeholders left (`grep -n '…' docs/adr/<NNNN>-*.md` prints nothing), the
DECISIONS line is present, and you quote the ADR path and number.
