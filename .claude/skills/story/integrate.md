# Stage 8 — integrate (orchestrator only)

Preconditions: `<story>.verify.md` ends `STATUS: PASS`; `<story>.review.md` ends `STATUS: APPROVE`;
no open BLOCKER/MAJOR. Variables from the skill: `STORY`, `SLUG`, `BASE`.

```bash
source scripts/env.sh
bash scripts/sdlc/state.sh set stage integrate
git switch "story/$STORY-$SLUG"
git fetch origin 2>/dev/null || true
git rebase main                      # linear history; conflicts -> resolve, re-run the two checks below
make check                           # full gate; red -> this is a fix loop (stage 5), not something to skip
make trace                           # every REQ in the story has a test and every tagged test has a known REQ
```
If `make check` or `make trace` is red after a rebase: treat it as a verify FAIL (increment `loop_count`,
send findings to the implementer). Never merge red.

## Story file
Edit the frontmatter `status: DONE`. Append to its Status log table:
`| <YYYY-MM-DD> | integrate | orchestrator | merged <sha>; verify PASS loop <n>; review APPROVE |`.
Commit: `docs($STORY): mark done [${STORY}]`.

## Merge (squash, linear)
With a remote and `gh auth status` OK:
```bash
git push -u origin "story/$STORY-$SLUG"
# Always `--base main`, never a stacked PR: GitHub merges a stacked PR into its base branch, not into
# main (lesson 2026-10-24, #3/#4). A story that depends on an unmerged one waits for that merge, then
# rebases onto main before opening its PR.
gh pr create --base main --fill --title "<type>(<scope>): <story title> [$STORY]" \
  --body-file <(bash -c 'cat .github/pull_request_template.md')   # then fill the template sections in the PR body
gh pr checks --watch                                              # wait for pr.yml (job `gate` must be green)
# Merge (owner decision 2026-10-25): only when `PR gate` is green on the PR head, the verify report is
# PASS with no open BLOCKER/MAJOR, and you have read the diff. Squash only, pinned to the checked head:
gh pr merge <n> --squash --match-head-commit "$(git rev-parse HEAD)" --subject "<type>(<scope>): <story title> [$STORY]"
# Then rebase any branch that depends on this one onto the new main before opening its PR.
git switch main && git pull --ff-only
```
Without a remote (local-only mode):
```bash
git switch main
git merge --squash "story/$STORY-$SLUG"
git commit -m "<type>(<scope>): <story title> [$STORY]"
git branch -D "story/$STORY-$SLUG"
```
Rules: squash only (one commit per story on main), subject <= 72 chars, Conventional Commit type
(`feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert`), tag last. `scripts/quality/commit-msg-lint.sh`
rejects anything else. Re-run `make check-fast` on main after the merge.

## Progress line
Format (PROGRESS.md header): `YYYY-MM-DD HH:MM · P<phase> · <STORY-nnn|SDLC> · <stage> · <GREEN|RED|INFO> · <note>`
```bash
printf '%s · P%s · %s · %s · %s · %s\n' "$(date '+%Y-%m-%d %H:%M')" "$PHASE" "$STORY" integrate GREEN \
  "merged $(git rev-parse --short main); verify PASS loop $N; review APPROVE; make check+trace green" >> docs/PROGRESS.md
```
Use the same `printf` for every INFO/RED line in other stages (stage names: spec, spec-review, design, tdd,
implement, verify, review, integrate, done).

## AI spend ledger (docs/SDLC_METRICS.md, section "AI spend ledger")
Append one row: `| <YYYY-MM-DD> | <STORY> | <model mix, e.g. opus x4 / sonnet x3> | <USD> | loops=<n>; <note> |`.
USD = the session cost delta since step 0 (from `/cost`, or the usage panel); when unknown write the best
estimate followed by ` (est.)`. The hand-maintained ledger is the only part of that file `make metrics`
keeps. Commit `docs(sdlc): progress + spend for $STORY [${STORY}]` on main (or include it in the squash).

## Clear state
```bash
bash scripts/sdlc/state.sh set story null
bash scripts/sdlc/state.sh set stage done
bash scripts/sdlc/state.sh set loop_count 0
```
Finally run `make check-fast` once more so the Stop hook's fingerprint is green before you stop.
