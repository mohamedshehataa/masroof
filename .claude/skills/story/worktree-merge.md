# Worktree subagents: base, hand-off and squash merge

Agents with `isolation: worktree` (`test-designer`, all implementers, `qa-verifier`, `performance-engineer`)
run in their own git worktree and leave their branch on disk when they changed files (see
`docs/DECISIONS.md`). The orchestrator owns the story branch and merges their work into it.

## Before launching a worktree subagent
1. Be on the story branch with a clean tree: `git switch story/$STORY-$SLUG && git status --porcelain`
   (commit or stash anything dirty; never launch with uncommitted work).
2. Record what exists now: `git worktree list --porcelain > "$TMP/wt-before.txt"` and
   `git branch --list 'worktree-*' > "$TMP/br-before.txt"` (use the scratchpad dir for `$TMP`).
3. Put this line in every worktree subagent prompt, because the worktree may have been created from the
   default branch rather than from the story branch:
   > First command: `git merge --no-edit story/$STORY-$SLUG` so your worktree contains the story's spec,
   > design and tests. If it reports "Already up to date" that is fine. Then `source scripts/env.sh`.
4. Tell writers (test-designer, implementers) to commit in their worktree with a Conventional Commit ending in
   `[$STORY]`; lefthook runs there too, so `--no-verify` is impossible and unnecessary.

## After the subagent returns
1. Find its branch: `git worktree list --porcelain | diff "$TMP/wt-before.txt" -` and
   `git branch --list 'worktree-*' | diff "$TMP/br-before.txt" -`. Exactly one new branch is expected;
   zero means the agent changed nothing (read-only agents such as `qa-verifier` normally leave nothing).
   Several -> merge each in the order the agents finished.
2. Inspect before merging: `git log --oneline story/$STORY-$SLUG..<wt-branch>` and
   `git diff --stat story/$STORY-$SLUG...<wt-branch>`. Uncommitted work inside the worktree
   (`git -C <worktree-path> status --porcelain`) must be committed first:
   `git -C <worktree-path> add -A && git -C <worktree-path> commit -m "<type>(<scope>): <what> [$STORY]"`.
3. Squash into the story branch (keeps history linear, one commit per stage):
   ```bash
   git switch story/$STORY-$SLUG
   git merge --squash <wt-branch>
   git commit -m "<type>(<scope>): <what> [$STORY]"     # e.g. test(sms): failing tests for REQ-SMS-004..007 [STORY-014]
   ```
   Conflicts: resolve only files that are not tests written by test-designer; if a test conflict appears,
   keep the test-designer version and let the next implementer loop handle it.
4. Clean up: `git worktree remove --force <worktree-path>` (if it still exists) and
   `git branch -D <wt-branch>`. Then `git worktree prune`.
5. Run the module gate yourself before moving on: `make check-fast MODULE=<m>`.

## What never happens here
- No `git rebase -i`, no `--no-verify`, no `git push --force` (guard-bash blocks them anyway).
- The orchestrator never edits test files to make a merge "work".
- The verifier's and reviewers' worktrees are read-only; if they left a branch, inspect it, do not merge code
  from it (they may only have run commands), and delete it.
