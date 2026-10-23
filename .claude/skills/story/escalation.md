# Escalation (after 3 failed loops, or when only the owner can unblock)

Never ship a failing gate and never idle. Do all of the following, then move to the next independent READY
story (`/next`).

1. Append to `docs/ESCALATIONS.md` under `## Open` (one bullet, newest last; keep the `- [ ]` prefix so the
   SessionStart hook counts it):
   ```
   - [ ] **<STORY-nnn> · <short title> · loop limit reached** — Stage: <verify|review|spec|design>. Loops: 3/3.
     Tried: (1) <what the implementer changed in loop 1>; (2) <loop 2>; (3) <loop 3>.
     Failing evidence: `<command>` -> <observed result, one line>; verify report `<path>.verify.md` (loop 3); review `<path>.review.md`.
     Suspected root cause: <spec gap | test gap | design gap | environment | unknown>.
     Options: (a) <smallest change to the spec or design that would unblock>; (b) <split the story into ...>; (c) descope with reason.
     Decision needed from: <owner | orchestrator in the next session>.
   ```
   For owner-only blockers (keys, numbers, legal, spend) use the `OWNER-NNN` prefix instead and list exactly
   what to put where (for example `ANTHROPIC_API_KEY` in `.env`).
2. Story frontmatter `status: BLOCKED`; add a Status log row `| <date> | <stage> | orchestrator | BLOCKED: loop limit, see ESCALATIONS |`.
3. PROGRESS line: `<ts> · P<phase> · <STORY> · <stage> · RED · blocked after 3 loops; see docs/ESCALATIONS.md`.
4. Add a `docs/LESSONS.md` entry if a root cause class is already clear
   (`what happened -> root cause (spec gap / test gap / review miss / environment) -> prevention`).
5. Leave the story branch in place (do not merge, do not delete). Commit the docs:
   `docs(<story>): escalate after 3 loops [<STORY>]`.
6. `make check-fast MODULE=<modules>` must be green on the branch you leave checked out (switch to `main` if
   the story branch is red, so the Stop hook does not fire on someone else's failure).
7. Clear state: `bash scripts/sdlc/state.sh set story null; ... set stage done; ... set loop_count 0`.
8. In chat: one concise paragraph with the decision needed and the options; then continue with other work.
