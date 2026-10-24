---
id: STORY-145
epic: EPIC-00
title: "Delivery gates"
phase: 0
status: READY
size: L
modules: [sdlc]
depends_on: [STORY-001]
reqs: [REQ-OPS-150, REQ-OPS-151, REQ-OPS-152, REQ-OPS-153, REQ-OPS-154, REQ-OPS-155, REQ-OPS-156, REQ-OPS-157, REQ-OPS-158, REQ-OPS-159, REQ-OPS-160, REQ-OPS-161, REQ-OPS-162, REQ-OPS-163, REQ-OPS-164, REQ-OPS-165]
implements: ["PROMPT_DELIVERY_GATES.md §0-§5", "PROMPT.md S2", "PROMPT.md S7", "PROMPT.md S9", "PROMPT.md S12", "PROMPT.md S16"]
owner_agent: devops-engineer
---

# STORY-145 — Delivery gates

## Goal
Each step that moves code (commit → push → PR → approval → merge → tag → deploy) passes a numeric threshold checked by a script, so no gate passes on someone's word. All thresholds live in one file, `scripts/quality/delivery-gates.yaml`, which hooks, `make` and CI all read ([docs/PROMPT_DELIVERY_GATES.md](../../PROMPT_DELIVERY_GATES.md) §0–§5). This is the owner's binding addendum, and it wins over `docs/PROMPT.md` where they conflict. It extends PROMPT S2 (stage 8 gates), S7 (hooks), S9 (thresholds), S12 (git workflow and CI) and S16 (approvals are gates, not people). Until this story is merged, the orchestrator applies the gates by hand and records each check in the merging story's `.review.md` (DECISIONS 2026-10-24).

Terms used below:
- **"the story"** is the story whose branch is checked out (`story/STORY-NNN-<slug>`) or the one passed with `--story`.
- **"merge-base"** is `git merge-base origin/main HEAD`.
- **"the branch diff"** is `<merge-base>..HEAD`.
- **"the PR"** is the open PR whose head is the story branch (known in CI, or passed with `--pr <n>`).
- **"bookkeeping paths"** are listed in `delivery-gates.yaml`: the story file, its `*.verify.md` / `*.review.md` (increment reports included), `docs/PROGRESS.md`, `docs/SDLC_METRICS.md`, `docs/TRACEABILITY.md`, `docs/DECISIONS.md` and `docs/ESCALATIONS.md`.
- **"the HEAD rule"**: a report sha `R` is valid for HEAD when `R` resolves to a commit that is an ancestor of HEAD, and either `R == HEAD` or `git diff --name-only R HEAD` lists only bookkeeping paths.

## Requirements and acceptance criteria

### REQ-OPS-150 — One threshold file, read by hooks, make and CI
**Given** `scripts/quality/delivery-gates.yaml` **When** any consumer evaluates a delivery metric **Then** it takes the threshold from that file, or from the file that it references by path. It never uses a literal of its own. The file defines:

| id | value(s) |
|---|---|
| M1 | `make check-fast` for changed modules: exit 0, wall time ≤ 180 s |
| M2 | `make check`: exit 0, wall time ≤ 900 s |
| M3 | per-module minima: a reference to `scripts/quality/quality-thresholds.yaml` (not copied); maximum drop vs main 0.5 pp |
| M4 | mutation ≥ 80 % for packages `money`, `parsing`, `dedup` (reference to `quality-thresholds.yaml`); allowed drop vs main 0.0 pp |
| M5 | 0 lost assertions, 0 new skip/disable/only, 0 assertion flips, 0 unsanctioned deleted test files |
| M6 | traceability strict: 0 unknown REQ ids; every REQ of the story and of DONE stories has ≥ 1 passing test |
| M7 | redocly errors 0; oasdiff breaking changes 0 unless label `breaking-change` **and** an ADR path in the PR body |
| M8 | gitleaks 0; semgrep ERROR 0; osv-scanner High/Critical with a fix 0; trivy High/Critical 0; unlogged suppressions 0 |
| M9 | a reference to `eval/thresholds.yaml` (`regression.tolerance_pp` 1.0, `cost` rise 25 %, OTP leak 0, injection obeyed 0); the AI-affecting path list |
| M10 | exploratory cases ≥ 10; BLOCKER/MAJOR findings 0 |
| M11 | open BLOCKER/MAJOR 0 (report rows and PR comments); MINOR fixed or ticketed |
| M12 | ≤ 400 hand-written added lines; the exclusion globs (REQ-OPS-153) |
| M13 | subject ≤ 72 characters; commits behind origin/main 0 |
| M14 | the dependency-file globs and the suppression patterns (REQ-OPS-155) |
| M15 | the required check names (REQ-OPS-161); flaky quarantine ≤ 5 |
| M16 | verify/review loops ≤ 3; story AI spend ≤ 3 × the median of DONE stories |
| M17 | k6 p95 reads < 200 ms and writes < 400 ms (S9); Lighthouse performance ≥ 90, accessibility ≥ 100, best practices ≥ 95; rollback tested ≤ 30 days ago |
| M18 | error rate < 1 %; parse-failure rate < 5 %; observation window ≥ 30 min |
| G7 | last 3 `main.yml` runs green; nightly green ≤ 24 h ago; selftest GREEN ≤ 7 days ago; restore test ≤ 30 days ago |
| — | the bookkeeping paths; the module path map (`backend/` → backend, `web/` → web, `android/` → android, everything else → sdlc) |

Each numeric threshold is defined **exactly once** across `delivery-gates.yaml`, `quality-thresholds.yaml` and `eval/thresholds.yaml`. A test loads all three files and fails on any duplicate. `flaky.max_quarantined` moves out of `quality-thresholds.yaml` into M15. `commit-msg-lint.sh` no longer contains the literal `MAX_LEN=72`.

**Consumers that read it:** `commit-msg-lint.sh` (M13 subject maximum), `pre-push-guard.sh` (M1), `coverage-gate.py` (M3 drop, M15 quarantine maximum), `delivery-gate.py` (every sub-command), `make gate`, and the workflows (only through `make` / `delivery-gate.py`).

**Agreement test (the addendum's "can never disagree").** **Given** a fixture copy of the file with M13 72 → 50, M1 180 → 1 s, M15 5 → 0 and M12 400 → 10. Every consumer is pointed at the copy through one environment variable or option; its name is in the design notes. **Then**:
- a valid 60-character subject is rejected by `commit-msg-lint.sh` **and** by `delivery-gate.py commits`, both naming `M13`;
- `pre-push-guard.sh`, with a `check-fast` stub that sleeps 2 s, rejects with `[M1] ... 2 s > 1 s`;
- `coverage-gate.py` with 1 quarantined test FAILs;
- `delivery-gate.py size` on 11 added lines FAILs `M12`.

With the original file, every one of these passes.

A static test asserts that every job in `pr.yml`, `main.yml` and `release.yml` that produces a `gate/*` or `pr-body-lint` check runs a `make` target or `delivery-gate.py`. No `run:` step in those jobs passes a threshold option (`--threshold`, `--max*`, `--min*`, `--limit`).

**Common output contract (every `delivery-gate.py` sub-command):**
- a table with columns `metric | check | measured | threshold | result` (result `PASS`, `FAIL` or `SKIP`, and a SKIP row always gives its reason);
- for each FAIL row, one line `FAIL <Mn|Gn> <check>: <reason>. Re-run: make gate STAGE=<sub-command>`;
- a final line `DELIVERY_GATE <sub-command>: PASS|FAIL (<p> pass, <f> fail, <s> skip)`;
- exit codes: 0 when there are 0 FAIL rows, 1 otherwise, 2 for a usage or configuration error.

Unhappy path (configuration): **Given** `delivery-gates.yaml` is missing, does not parse, or lacks a key a consumer needs **When** that consumer runs **Then** it fails closed and names the file and the key (or the parse error line). `delivery-gate.py`, `commit-msg-lint.sh` and `coverage-gate.py` exit 2; the pre-push hook rejects the push. No consumer falls back to a built-in default.
Unhappy path (usage): an unknown sub-command exits 2 and lists the valid ones. On a non-story branch without `--story`, the exit is 2 with `cannot determine the story`. A `--story` id with no story file exits 2.
Fixtures (addendum §4): unit tests use one **passing-PR fixture** (repo state, story, reports, recorded GitHub JSON) and, per metric, a fixture that fails **exactly that metric**. No test needs network, Docker or a GitHub token. GitHub data comes from recorded API JSON, and the clock is fixed by the test.
Non-functional: after this story, warm `make check-fast MODULE=sdlc` still exits 0 in **< 180 s**. It is run twice and the second run is measured.

### REQ-OPS-151 — `delivery-gate.py verify` (M10)
**Given** the story **When** `delivery-gate.py verify` runs **Then** it PASSes only if all of these hold:
1. `<story>.verify.md` exists beside the story file and is tracked by git;
2. its last non-empty line is `STATUS: PASS`;
3. its `**Head:**` sha satisfies the HEAD rule;
4. its Findings table has 0 rows with severity `BLOCKER` or `MAJOR`;
5. the first integer in its `## Exploratory` section is ≥ 10;
6. its `## Test integrity` section starts with `OK`, or the report has a line `TEST_INTEGRITY: OK`;
7. the `**Verifier:**` name is `qa-verifier` and differs from the story's `owner_agent`.

Example: the report's Head is `HEAD~1`, and HEAD changed only `docs/PROGRESS.md` → PASS.
Unhappy paths, each a FAIL row naming `M10` with `Re-run: make gate STAGE=verify`:
- no report → `no STORY-145-delivery-gates.verify.md`;
- the report is untracked → `not committed`;
- `STATUS: FAIL`;
- HEAD changed `scripts/quality/delivery-gate.py` after the report's Head → `stale`, naming that first non-bookkeeping path;
- the report's Head is not an ancestor of HEAD → `stale`;
- `9 cases tried` → `9 < 10`;
- `Test integrity` reads `VIOLATION`;
- `Verifier: devops-engineer` on a story owned by `devops-engineer`;
- one `MAJOR` row;
- no `Head` line → `Head sha not found`. This is a FAIL with exit 1, not a usage error.

### REQ-OPS-152 — `delivery-gate.py review` (M11)
**Given** the story **When** `delivery-gate.py review` runs **Then** it PASSes only if all of these hold:
1. `<story>.review.md` is tracked, and its last non-empty line is `STATUS: APPROVE`;
2. the head sha in its `**Diff:** git diff <base>...<head>` line satisfies the HEAD rule;
3. its `## Verdicts` table (`| reviewer | status | loop |`, added to `docs/templates/review-report.md` by this story) has an `APPROVE` row for `code-reviewer`, and one for every specialist that `.claude/skills/story/triggers.md` triggers for `git diff --name-only <merge-base>...HEAD`. That includes the "Also when" column: for example, a Security section that is not "None" triggers `security-reviewer`;
4. every `BLOCKER`/`MAJOR` Findings row has Resolution `fixed <sha>`, with the sha reachable from HEAD;
5. every `MINOR` row has Resolution `fixed <sha>` (reachable from HEAD) or `ticketed STORY-NNN` (an existing story file). `NIT` rows are ignored;
6. when the PR is known: every PR review comment whose body starts with `[BLOCKER]` or `[MAJOR]` is resolved. Resolved means either a commit in the PR carries the trailer `Resolves: <the comment's html_url>`, or a reply in the comment's thread contains `[WONTFIX STORY-NNN]` naming an existing story. With no PR, this row is `SKIP no PR yet`.

Unhappy paths (FAIL `M11`, `Re-run: make gate STAGE=review`):
- the diff touches `contracts/openapi.yaml` and the verdicts list only `code-reviewer` → `security-reviewer verdict missing`;
- a MINOR row with Resolution `later`;
- `fixed 1234abc` where the sha is not in the branch;
- `STATUS: CHANGES_REQUESTED`;
- the review head is stale under the HEAD rule;
- two unresolved `[MAJOR]` PR comments → FAIL listing both URLs;
- `[WONTFIX STORY-999]` where no STORY-999 file exists → still unresolved;
- the GitHub API returns 401, 403, 5xx or times out → FAIL closed: `GitHub API unavailable (HTTP 502)`. It never passes.

### REQ-OPS-153 — `delivery-gate.py size` (M12)
**Given** the branch diff **When** `delivery-gate.py size` runs **Then** the count is the sum of the **added** column of `git diff --numstat <merge-base>..HEAD`. A modified line counts once, as in STORY-144. Binary files count 0. Paths matching these exclusion globs (held in the yaml) are left out:
- generated code: `**/generated/**`, `web/src/api/generated/**`;
- lockfiles: `**/gradle.lockfile`, `**/buildscript-gradle.lockfile`, `**/package-lock.json`, `**/gradle/verification-metadata.xml`;
- wrappers: `**/gradlew`, `**/gradlew.bat`, `**/gradle/wrapper/**`;
- vendored code: `**/vendor/**`;
- spec and report artefacts: `docs/backlog/**`;
- generated docs: `docs/TRACEABILITY.md`, `docs/SDLC_METRICS.md`, `docs/SDLC_SELFTEST.md`.

The result is PASS when the count is ≤ 400. It is also PASS under a **size exception**: the branch diff adds a `docs/DECISIONS.md` line containing the story id and `size exception`, **and**, when the PR is known, the PR carries the label `size-exception`. The output lists the 5 largest files by count.
Examples:
- 400 → PASS;
- 1,200 added lines under `web/src/api/generated/` plus 10 elsewhere → count 10, PASS;
- 900 with the DECISIONS line and the label → PASS, showing `exception (DECISIONS: <line>)`.

Unhappy paths (FAIL `M12`):
- 401 → `401 > 400 hand-written lines; over 400 needs label size-exception + a DECISIONS line. Re-run: make gate STAGE=size`;
- the DECISIONS line is present and a PR is known, but the label is missing → `label size-exception missing`;
- the label is present without the DECISIONS line → `no DECISIONS size-exception line for STORY-NNN`.

### REQ-OPS-154 — `delivery-gate.py commits` (M13)
**Given** the branch diff **When** `delivery-gate.py commits` runs **Then**, for every commit in `<merge-base>..HEAD`:
- the subject passes `commit-msg-lint.sh` (format, tag and the M13 maximum of 72);
- the commit has exactly one parent, so it is not a merge commit;
- the subject does not start with `fixup!`, `squash!` or `amend!`;
- the body has a `Signed-off-by:` trailer whose e-mail equals the commit's author e-mail;
- on a `story/STORY-NNN-*` branch, the body has a `Story: STORY-NNN` trailer with that id.

Also, the branch is 0 commits behind: `git rev-list --count HEAD..origin/main` is 0.
Unhappy paths (FAIL `M13`, naming the short sha and the remedy):
- a 73-character subject → `73 > 72`;
- a merge commit → `merge commit on the branch; rebase instead`;
- `fixup! ...` → `autosquash first: git rebase -i --autosquash origin/main`;
- no `Signed-off-by` → `re-run after git rebase --signoff origin/main`;
- `Story: STORY-144` on `story/STORY-145-delivery-gates` → FAIL;
- 2 commits behind → `2 commits behind origin/main; re-run after git rebase origin/main`;
- `origin/main` unknown → `run git fetch origin` (fail closed).

### REQ-OPS-155 — `delivery-gate.py docs` (M14)
**Given** the branch diff **When** `delivery-gate.py docs` runs **Then** it PASSes only if all of these hold:
- (a) the story `status` is not `DRAFT`;
- (b) the diff adds ≥ 1 `docs/PROGRESS.md` line in the PROGRESS format containing ` · STORY-NNN · `;
- (c) if the diff changes a dependency file (yaml list: `**/build.gradle.kts`, `**/settings.gradle.kts`, `**/libs.versions.toml`, `**/gradle.lockfile`, `**/package.json`, `**/package-lock.json`, `**/requirements*.txt`, a `FROM` line in `**/Dockerfile`, or a `uses: <action>@<ref>` line in `.github/workflows/*.yml`), the diff adds ≥ 1 `docs/DECISIONS.md` line naming the story id;
- (d) every **added** line that matches a suppression pattern (yaml list: `@SuppressWarnings(`, `@Suppress(`, `nosemgrep`, `eslint-disable`, `@ts-ignore`, `@ts-expect-error`, `# noqa`, `# type: ignore`, `noinspection`, `NOSONAR`, `shellcheck disable=`, `gitleaks:allow`, and any line added to `.trivyignore`, `.gitleaksignore`, `.semgrepignore` or `osv-scanner.toml`) contains `reason:` and a `STORY-NNN` id, **and** the diff adds a DECISIONS line naming the story id and `suppression`;
- (e) every ADR the story references (`ADR-NNNN` or `docs/adr/NNNN-*.md`) exists, and every ADR file the diff adds is referenced by the story.

Unhappy paths, one fixture each (FAIL `M14`, `Re-run: make gate STAGE=docs`):
- status `DRAFT`;
- no PROGRESS line for the story;
- `backend/build.gradle.kts` changed without a DECISIONS line;
- an added `# shellcheck disable=SC2086` without `reason:`;
- the story links `docs/adr/0099-x.md`, which does not exist;
- an added `docs/adr/0007-y.md` that the story does not reference.

Whether a design note is "significant" enough for an ADR stays a judgement for the architect and the spec-reviewer. Only (e) is mechanical; see the deviations below.

### REQ-OPS-156 — `delivery-gate.py ci` (M15)
**Given** the PR with head sha `S` **When** `delivery-gate.py ci` runs **Then** it PASSes only if:
1. every check named in the yaml's required checks has a **completed** check run on `S` with conclusion `success`. A missing, pending, `failure`, `cancelled`, `skipped`, `neutral` or `timed_out` run is a FAIL naming the check and its state;
2. **no silent flaky re-run**: for each workflow run on `S` with `run_attempt > 1`, every job that concluded `failure` in an earlier attempt and `success` in a later one is a violation, unless `docs/FLAKY_TESTS.md` at `S` has a row naming that check and a `STORY-NNN` id;
3. quarantined rows in `docs/FLAKY_TESTS.md` are ≤ 5.

Recorded-JSON fixtures:
- all green → PASS;
- one required check `failure` → FAIL;
- one `pending` → FAIL;
- `gate/M1 check-fast` failed in attempt 1 and passed in attempt 2, with no quarantine row → FAIL `M15 ci: gate/M1 check-fast passed only on re-run (attempt 2) without a quarantine entry`;
- the same with a quarantine row → PASS;
- 6 quarantine rows → FAIL `6 > 5`.

Unhappy path: no PR → FAIL `no open PR for story/STORY-NNN-<slug>`. The GitHub API is unreachable or returns 401/403/5xx → FAIL closed.

### REQ-OPS-157 — `delivery-gate.py pr-open` composite (G3)
**Given** the story branch **When** `delivery-gate.py pr-open --title "<title>"` runs **Then** it reports all of these rows:
- story `status: IN_PROGRESS`;
- the branch is `story/STORY-NNN-<slug>`, where `NNN` and `<slug>` match the story file name;
- 0 commits behind origin/main;
- the title passes `commit-msg-lint.sh` (it becomes the squash subject);
- M10, M11 (with the PR row `SKIP`), M12, M13, M14;
- M2: `make check` exits 0 with wall time ≤ 900 s.

M2 runs last. If an earlier row FAILed, M2 is `SKIP not run: earlier failure`, and the exit code is still 1. On PASS it writes the generated PR body (REQ-OPS-158) to an untracked path that it prints, and prints the labels to apply:
- module labels from the changed paths;
- `security-review` when security-reviewer triggers;
- `breaking-change` only when oasdiff reported a breaking change **and** the body has an ADR path;
- `size-exception` when M12 passed through the exception.

Unhappy paths:
- status `READY` → FAIL `G3 status`;
- branch `story/STORY-144-x` while checking STORY-145 → FAIL `G3 branch`;
- no verify report → FAIL `M10`, and no body file is written;
- the verify Head is stale → FAIL `M10`;
- a `make check` stub exits 1 → FAIL `M2`, with the log path;
- a `make check` stub reports 901 s → FAIL `M2 901 s > 900 s`;
- an 80-character title → FAIL `M13`.

### REQ-OPS-158 — Generated PR body with the `gate:v1` marker, and `pr-body-lint`
**Given** pr-open passed, or `delivery-gate.py pr-body` runs for the current HEAD **Then** the body is generated from `.github/pull_request_template.md`, which this story regenerates. It has these sections filled:
- **Story:** path, title and phase;
- **REQs:** every REQ of the story with its test names from traceability (`no test` where there is none);
- **Verify / Review:** report paths, their head shas and their STATUS;
- **Coverage:** before (the main baseline) and after (HEAD) for each changed module, or `n/a: no main baseline`;
- **Eval delta:** the M9 table, or `not AI-affecting`;
- **Security:** per-scanner finding counts from the latest local `make security` for HEAD, or `pending gate/M8`;
- **Size:** the M12 count, and the exception if one applies;
- **DECISIONS:** the lines the branch adds, verbatim;
- **Rollback plan:** the story's `## Rollback plan` section, or a generated `git revert <squash sha>` plus the added migrations, or `no migration`.

The last line is `<!-- gate:v1 sha=<40-hex HEAD> body=<64-hex sha256 of the LF-normalised text above the marker> -->`. Generating twice for the same HEAD and the same inputs gives byte-identical output. No template placeholder is left unfilled. Inserted text that looks like a marker is neutralised, so the body still has exactly one marker.
**Given** a PR **When** the `pr-body-lint` check (CI on `opened`, `edited`, `synchronize`, `reopened`) or `make gate STAGE=pr-body-lint` runs **Then** it PASSes only if there is exactly one marker, its `sha` equals the PR head sha, and its `body` digest matches the text.
Unhappy paths (FAIL `pr-body-lint`):
- a body typed from the UI template with no marker → `no gate:v1 marker`;
- one character edited by hand → `digest mismatch: the body was edited by hand. Re-run: make gate STAGE=pr-body`;
- a new push without regeneration → `stale: marker sha abc1234 ≠ head def5678`;
- two markers → FAIL;
- a DECISIONS line containing `<!-- gate:v1 sha=… -->` is neutralised at generation, so lint sees one marker and passes.

### REQ-OPS-159 — `approve` and `merge` composites (G5, G6)
**Given** the PR **When** `delivery-gate.py approve` runs **Then** its rows are M10, M11 (with PR comments), M15 and M16. With no PR → FAIL `no open PR`.
**When** `delivery-gate.py merge` runs **Then** its rows are:
- **G5 approval:** ≥ 1 review in state `APPROVED` whose `commit_id` equals the head sha, from a reviewer other than the PR author. Otherwise FAIL `G5 approval: no non-author approval on head <sha> (see OWNER-011)`;
- **M2:** `make check` on HEAD, which must be 0 behind origin/main;
- **M3:** `coverage-gate.py --diff-base main`. For each changed module, every line/branch value (overall and per package token) must meet the `quality-thresholds.yaml` minimum **and** be ≥ main − 0.5 pp. For example, with main at 88.0 %: 87.5 % → PASS; 87.4 % → FAIL `M3 coverage: backend line 87.4% < main 88.0% - 0.5 pp`. No main baseline yet → `SKIP no main baseline`, with the absolute minima still enforced;
- **M4:** when the diff touches a `money`, `parsing` or `dedup` package, `coverage-gate.py --require-mutation` must show ≥ 80 % and ≥ main. Otherwise `SKIP no mutable package changed`;
- **M6 strict:** `traceability.py --strict` exits 0, and every REQ in the story's frontmatter has ≥ 1 passing test result. A REQ with only failing or result-less tests → FAIL naming it;
- **M8 full:** `make security` exits 0;
- **M9 full:** when an AI-affecting path changed, `make eval` exits 0. Otherwise `SKIP not AI-affecting`;
- **M15**, as in REQ-OPS-156;
- **M16:** every verify report of the story, increment reports included, has `Loop: n` with n ≤ 3. The story's AI-spend ledger sum in `docs/SDLC_METRICS.md` must be ≤ 3 × the median per-story USD of DONE stories with ledger rows. With fewer than 3 such stories → `SKIP`. Over the limit passes only with a DECISIONS line naming the story and `spend`. No ledger row → FAIL `no AI spend ledger row for STORY-NNN`.

Fixtures: the passing-PR fixture passes `approve` and `merge`. For each of M2, M3, M4, M6, M8, M9, M10, M11, M15, M16 and G5 approval, one fixture fails exactly that row: the external commands are stubbed through the yaml (REQ-OPS-150). The composite exits 1 and names only that metric in its `FAIL` lines. A stale approval (on the previous head sha) fails G5.

### REQ-OPS-160 — Local git hooks (G1)
**pre-commit.** The existing commands stay (gitleaks, shellcheck, the formatters, no-env). Added:
- (a) **M5 on staged test files**: the index is compared with HEAD under the same rules as `test-integrity-check.sh`. Unstaged changes are ignored, so a staged clean test plus an unstaged weakened one passes;
- (b) a staged **added** line matching a suppression pattern without `reason:` and a STORY id is rejected with `[M8]`.

**commit-msg.**
- `commit-msg-lint.sh` runs with the yaml maximum.
- On `story/STORY-NNN-*` branches, `Story: STORY-NNN` is appended if absent.
- `Signed-off-by: <user.name> <user.email>` is appended if absent.
- A subject tag `[STORY-MMM]` with MMM ≠ NNN is rejected with `[M13]`.
- Amending does not duplicate trailers. Other branches get no `Story:` trailer.
- `Merge …`, `Revert "…"` and `fixup!` subjects keep passing here; M13 rejects them at PR time.

**pre-push** (`pre-push-guard.sh`), for each ref line:
- **any** update of `refs/heads/main` or `refs/heads/master` is rejected: fast-forward, creation, deletion or non-fast-forward. The message is `[G1] pushing to main is forbidden; only PR merges create commits on main. Re-run: make gate STAGE=pr-open (via /story)`. This **supersedes** the fast-forward and creation clauses of REQ-OPS-011 (see Sanctioned test changes);
- `refs/tags/*` and branch deletions pass without M1/M5;
- any other branch update runs:
  - **M1**: the modules changed in `origin/main...<local sha>` are found through the module map. If the working tree has uncommitted changes under one of them, the push is rejected with `[M1] commit or stash changes in <module>/ before pushing`. Then `make check-fast MODULE=<modules>` must exit 0 within ≤ 180 s;
  - **M5**: `test-integrity-check.sh origin/main` evaluated on the **pushed commit**, not the working tree;
  - reports: an untracked `STORY-NNN*.verify.md` or `STORY-NNN*.review.md` for the branch's story → rejected with `[M10]` or `[M11]`;
- `origin/main` unknown → rejected with `run git fetch origin` (fail closed).

**M5 semantics (all M5 consumers: pre-commit, pre-push, `make test-integrity`, `gate/M5`).**
- An assertion flip is a `VIOLATION` with exit 1. A flip is a drop in one assertion kind's count while the total does not drop. The existing `WARN:` line is still printed.
- A flip or a deletion is allowed only when the story file of the branch has a "Sanctioned test changes" row naming that file and kind, **and** the branch diff adds a DECISIONS line naming the story. Such a change is printed as `SANCTIONED (<story> <superseded-by REQ>)`.
- `--allow-deletions` keeps working.

Every rejection is one line starting `[<id>]`, followed by a `Re-run:` command. Tests run the hooks against temp repos with a bare remote, as real git hooks.

### REQ-OPS-161 — `pr.yml`: one check run per metric, `pr-body-lint`, AI review and Action approval
**Given** a PR to `main`, or a push to `story/**` **When** `pr.yml` runs **Then** the PR head sha carries check runs named exactly as listed in the yaml:
`gate/M1 check-fast`, `gate/M2 check`, `gate/M3 coverage`, `gate/M4 mutation`, `gate/M5 test-integrity`, `gate/M6 traceability`, `gate/M7 contract`, `gate/M8 security`, `gate/M9 eval`, `gate/M10 verify`, `gate/M11 review`, `gate/M11 ai-review`, `gate/M12 size`, `gate/M13 commits`, `gate/M14 docs`, `gate/M15 ci`, `gate/M16 loop-budget`, `pr-body-lint`.

The architect chooses between one job per metric and the Checks API from a shared job. Rules:
- PR triggers include `edited`.
- A push without a PR produces the G2 subset: M1, M5, M6 (non-strict), M7, M8 fast, M9 fast, M12, M13.
- There is **no aggregate check**: the job "PR gate" is removed.
- A metric that does not apply (module absent, no AI path changed) concludes `success` with the summary `not applicable: <reason>`. It never concludes `skipped`.
- Each failing check's summary names the metric id, the measured value and the threshold, and the re-run command.
- `gate/M7` lets a breaking oasdiff pass only with the label `breaking-change` **and** a `docs/adr/NNNN-*.md` path in the body.
- `gate/M8` on a PR runs gitleaks, semgrep and osv-scanner, plus the suppression check of REQ-OPS-155(d).

**AI review.** `gate/M11 ai-review` runs `anthropics/claude-code-action` with the code-reviewer procedure, plus security-reviewer when the PR has label `security-review`. The PR diff is wrapped as delimited, untrusted data. The final message must end with `STATUS: APPROVE` or `STATUS: CHANGES_REQUESTED`.
- `APPROVE` → success.
- `CHANGES_REQUESTED`, or no parsable last line → failure.
- No `ANTHROPIC_API_KEY` secret → **failure** with `ANTHROPIC_API_KEY secret missing (OWNER-012)`. The old behaviour (skip with a notice) is removed.

**Action approval.** When every required check on the head is `success`, `gate/M11 ai-review` is APPROVE, and `delivery-gate.py approve` exits 0, a job submits an `APPROVED` review on the head sha as `github-actions[bot]`. If GitHub answers 403 or 422, the job fails with `GitHub refused the Action's approval: enable "Allow GitHub Actions to create and approve pull requests" (OWNER-011)`.

**Labels and permissions.**
- The labeler config covers module paths only, so the labels `size-exception`, `security-review` and `breaking-change` survive a labeler run.
- The workflow default is `contents: read`. `pull-requests: write` is granted only to the AI-review, approval and labeler jobs; `checks: write` only where check runs are created. No job has `contents: write`, and `pull_request_target` is not used.

Static tests check names, triggers and permissions. **Live:** on STORY-145's own PR, `gh pr checks <n>` lists all 18 checks as `pass` (blocked on OWNER-011 and OWNER-012, see below).

### REQ-OPS-162 — `main.yml` `revert-on-red`
**Given** a squash commit `M` on `main` whose `main.yml` run concludes `failure` or `timed_out` **When** the `revert-on-red` job runs **Then**:
- it creates branch `revert/<short M>` from main with `git revert --no-edit M`;
- a second commit sets the story named by `M`'s `[STORY-NNN]` tag or `Story:` trailer to `status: IN_PROGRESS`. It appends the Status log row `| <date> | revert-on-red | main.yml | reverted <short M>: run <url> red |` and a PROGRESS line `... · STORY-NNN · integrate · RED · reverted <short M> ...`;
- it opens a PR titled `revert(<scope>): <description> [STORY-NNN]`, ≤ 72 characters with the description truncated. The PR has a generated body with the marker;
- it enables squash auto-merge, so the revert lands when its required checks pass.

Unhappy paths:
- the run is `cancelled` → no revert, and a notice;
- `M` is itself a revert → no revert of the revert. Instead an issue `revert-on-red: main red after revert <sha>` is opened with label `revert-failed`;
- `M` is tagged `[SDLC]` or `[PHASE-N]` → it is reverted with no story change;
- the revert conflicts → an issue labelled `revert-failed` with the run URL, and no PR;
- the workflow-triggering token secret is missing → the job fails, and an issue names the secret (proposed OWNER-013);
- the revert PR's checks fail → auto-merge does not merge it, and it stays open.

Tests: the revert procedure runs as a script against a temp repo, with a stub `gh` on PATH that records its calls. The tests assert the branch, both commits, the story-file diff, the PR title length and the recorded `gh` arguments. A static test asserts that the job runs only after the main gate fails, and that `contents: write`, `pull-requests: write` and `issues: write` are granted **only** to this job.

### REQ-OPS-163 — guard-bash: no push to main, no self-approval, PR create/merge only inside `/story`
Scope, normalisation, the "Blocked" format (exit 2, the rule name, `Safe alternative:`) and the non-goals are those of REQ-OPS-001. Messages start with the gate id.
- **B1b (extends B1).** Every `git push` whose destination is main or master is blocked, **with or without force**. That covers an explicit `main`, `refs/heads/main`, `HEAD:main` or `story/x:main`; `-u origin main`; no refspec or `HEAD` while main is checked out; and `--all`. Message: `[G1] pushing to main is forbidden; only PR merges create commits on main`. Still allowed: `git push origin v1.2.3`, `git push origin --tags`, `git push -u origin story/STORY-145-delivery-gates`, `git push origin main-docs` (a different branch) and `git push origin :story/x`.
- **B15 PR flow.**
  - `gh pr review <n> --approve` / `-a` is **always** blocked, even with `pr_flow_active: true`: `[G5] the PR author never approves`.
  - `gh api` on a path `…/pulls/<n>/reviews` with a field `event=APPROVE` (`-f`, `-F`, `--field`, `--raw-field`) is blocked the same way.
  - `gh pr merge` (and `gh api …/pulls/<n>/merge`) is blocked unless `.claude/state.json` has `pr_flow_active: true` **and** state `story` equals the story id of the current branch: `[G6]`.
  - `gh pr merge --admin` is always blocked.
  - `gh pr create` (and `gh api -X POST …/pulls`) has the same condition: `[G3]`.
  - `gh alias set` whose expansion contains a blocked form is blocked.
  - Always allowed: `gh pr view|checks|list|diff|status|edit`, `gh pr review --comment|--request-changes`, `gh run view|list|rerun`. Silent re-runs are caught by M15.
- `scripts/sdlc/state.sh` gains `pr-flow-on` and `pr-flow-off`.

Tests tagged `REQ-OPS-163` go in `.claude/hooks/tests/test-guard-bash.sh`:
- each blocked form above, both with the flag off and with it on but the story mismatched;
- `gh pr create --fill` and `gh pr merge 12 --squash` allowed with the flag on and the story matching;
- `gh pr review 12 --approve` blocked with the flag on;
- every allowed form above.

Unhappy path: malformed hook JSON still never blocks (REQ-OPS-001). The flag is **not** a security boundary, because any agent can run `pr-flow-on`. The boundary is GitHub (required reviews and checks, STORY-016) plus the approval identity (OWNER-011).

### REQ-OPS-164 — `/story` stage 8 and `/release` call `delivery-gate.py` and refuse on red; G7, M17 and M18 as checks
**`/story` stage 8** (`.claude/skills/story/SKILL.md` and `integrate.md`) runs in this order:
1. rebase on origin/main;
2. `make gate STAGE=pr-open`. On FAIL, `gh pr create` is **not** run; the failing rows go to the implementer as a fix loop (`loop_count`+1);
3. `state.sh pr-flow-on`, with a trap that runs `pr-flow-off` on every exit path;
4. push, then `gh pr create --title <title> --body-file <generated> --label <labels>`;
5. before any approval, the bookkeeping commits:
   - story `status: DONE` with the Status log row `| <date> | integrate | orchestrator | PR #<n>; verify PASS loop <k>; review APPROVE |`;
   - the PROGRESS line, the AI-spend ledger row and `make trace` output;
   - then `make gate STAGE=pr-body`, `gh pr edit --body-file` and a push;
6. `gh pr checks --watch`;
7. `make gate STAGE=approve`. On FAIL → fix loop. If no approval arrives, the orchestrator adds a note to `docs/ESCALATIONS.md` referencing OWNER-011 and moves on to other READY work; the story stays `IN_PROGRESS` on main;
8. `make gate STAGE=merge`. On FAIL, there is no merge;
9. `gh pr merge --squash --delete-branch --subject <title> --body <trailers>`, where the trailers are `Story:`, `Reqs:`, `Verify: <path>@<sha>`, `Review: <path>@<sha>` and `Co-Authored-By:`;
10. `pr-flow-off`, `git switch main && git pull --ff-only`, and the state is cleared.

The local-only `git merge --squash` path is removed: with no remote, the story ends BLOCKED and an escalation is written. The skill's exit gate adds two conditions: `git show origin/main:<story file>` shows `status: DONE`, and `state.sh get pr_flow_active` prints `false`.
**`/release`** (`.claude/skills/release/SKILL.md`, `release.yml`) works like this:
- step 0 runs `make gate STAGE=release`, and on FAIL the skill stops before `release-on`;
- the skill never runs `git push origin main`. The changelog/version commit lands through a PR that passes G3–G6, either the release-please PR or a story-style PR;
- the tag is created only when HEAD equals origin/main and the tree is clean, and is pushed alone (`git push origin vX.Y.Z`);
- after staging, it runs `make gate STAGE=release-staging` (M17). On FAIL, staging is rolled back and the skill stops;
- after the production deploy and its 30-minute window, it runs `make gate STAGE=post-deploy` (M18). On FAIL, `make rollback` runs without asking, an incident file is written from `docs/templates/incident.md`, and a `/bug` story is opened. In `release.yml`, the `deploy-prod` job runs the same check and `make rollback` on failure.
- The S16 first-deploy checkpoint is unchanged.

**Checks.** **Given** recorded GitHub JSON, the evidence files and a fixed clock **Then**:
- `release` (G7) PASSes only if:
  - the last 3 completed `main.yml` runs on main concluded `success`;
  - the latest `nightly.yml` run on main is `success` and was created ≤ 24 h ago;
  - no `.review.md` on main has an open BLOCKER/MAJOR row (Resolution not `fixed <sha>`);
  - `docs/SDLC_SELFTEST.md` says `Verdict: **GREEN**` and was generated ≤ 7 days ago;
  - the restore-test evidence (its path is in the yaml; STORY-124 produces it) is ≤ 30 days old;
  - HEAD equals origin/main and the tree is clean;
- `release-staging` (M17) PASSes only if:
  - the smoke result exit is 0;
  - k6 p95 for reads is < 200 ms and for writes < 400 ms;
  - Lighthouse performance is ≥ 90, accessibility = 100 and best practices ≥ 95;
  - an SBOM file exists for each built module;
  - the image signature check passed;
  - the rollback-test evidence is ≤ 30 days old;
- `post-deploy` (M18) PASSes only if the sample covers ≥ 30 min, the error rate is < 1 % and the parse-failure rate is < 5 %. Its FAIL line contains `make rollback`.

Unhappy fixtures (each FAIL names its id):
- 2 of the last 3 main runs green;
- nightly 25 h old;
- selftest `AMBER`;
- selftest 8 days old;
- no restore evidence → `M17 restore test: no evidence (STORY-124)`;
- restore evidence 31 days old;
- HEAD 1 behind origin/main;
- k6 reads p95 201 ms;
- Lighthouse accessibility 99;
- error rate exactly 1.0 % → FAIL;
- window 29 min;
- no metrics data → FAIL, which means rollback.

Passing fixture: parse failure 4.9 % passes. The skill files are checked by a static test over their bash blocks:
- `make gate STAGE=pr-open` comes before `gh pr create`;
- `make gate STAGE=merge` comes before `gh pr merge`;
- `pr-flow-off` appears in a trap;
- no `git merge --squash` into main and no `git push origin main` appear;
- `/release` has `make gate STAGE=release` before `release-on`.

### REQ-OPS-165 — `make gate`, `docs/DELIVERY_GATES.md` linked from CLAUDE.md, five new self-test rows PROVEN
**`make gate STAGE=<x>`** runs `delivery-gate.py <x>` and passes its exit code through. The valid stages are `pr-open`, `approve`, `merge`, `verify`, `review`, `size`, `commits`, `docs`, `ci`, `pr-body`, `pr-body-lint`, `release`, `release-staging` and `post-deploy`. With `STAGE` missing or unknown, it exits 2 and lists the valid values. `make help` lists `gate`.
**`docs/DELIVERY_GATES.md`** is the human version of addendum §1–§3. It holds:
- the M1–M18 table (id, metric, threshold, measured by, gate);
- a short section for each of G1–G8;
- the enforcement map;
- the re-run command for each metric;
- the owner dependencies below.

A test asserts that every threshold in its table equals the yaml value, and that every metric id and check name in the yaml appears in the document. Root `CLAUDE.md` links `docs/DELIVERY_GATES.md` under **Workflow** and stays under 200 lines.
**Self-test.** `make selftest` gains five rows, each planted in an isolated temp repo/state and reverted:

| row | plant | must observe |
|---|---|---|
| D1 | PR opened without a verify report | `delivery-gate.py pr-open` exit 1 naming `M10`, no body file written |
| D2 | self-approval | guard-bash ← `gh pr review 1 --approve` exit 2 `[G5]` |
| D3 | weakened test | the `gate/M5` command from the yaml exits 1 `TEST_INTEGRITY: VIOLATION` |
| D4 | hand-edited PR body | `pr-body-lint` exit 1 `digest mismatch` |
| D5 | push to main | both: `git push origin main` (fast-forward) rejected by the installed pre-push hook with the bare remote unchanged, **and** guard-bash exit 2 `[G1]` |

`docs/SDLC_SELFTEST.md` then shows **21 PROVEN, 0 PENDING, 0 FAILED, Verdict GREEN**. The 16 existing rows stay PROVEN.
Unhappy path: if a planted violation is not blocked, the row is `FAILED` and `make selftest` exits 1. A test proves this with a stub guard that allows the violation.

## Sanctioned test changes
These rows are read by the M5 consumers (REQ-OPS-160). Each change is a deliberate requirement change made by the test-designer; none of them weakens a test.

| kind | file | assertion | was | now | superseded by |
|---|---|---|---|---|---|
| flip | `scripts/quality/tests/test-pre-push-guard.sh` | pre-push: a fast-forward of main is allowed | `expect 0` | `expect 1 "[G1]"` | REQ-OPS-160 (supersedes REQ-OPS-011 fast-forward clause) |
| flip | `scripts/quality/tests/test-pre-push-guard.sh` | pre-push: creating main on an empty remote is allowed | `expect 0` | `expect 1 "[G1]"` | REQ-OPS-160 (supersedes REQ-OPS-011 creation clause) |
| moved | `.claude/hooks/tests/test-guard-bash.sh` | REQ-OPS-001 allows: gh pr create --fill (everyday list) | allowed with no state | allowed only with `pr_flow_active` + matching story; blocked otherwise (new `REQ-OPS-163` cases) | REQ-OPS-163 |

The test-designer adds a row before committing any other change to an existing assertion. An unlisted change fails M5. The orchestrator adds the matching DECISIONS line.

## UX states (UI stories only)
N/A: no UI. Developer-facing output is the metric table and the one-line `[<id>] … Re-run: …` messages (REQ-OPS-150, REQ-OPS-160).

## Contract impact
None. `contracts/openapi.yaml` is untouched. `gate/M7` only wires the existing redocly and oasdiff gates, plus the label-and-ADR rule.

## Data / migration impact
None. No schema or Flyway change. New committed configuration: `scripts/quality/delivery-gates.yaml`. `flaky.max_quarantined` moves from `quality-thresholds.yaml` into it.

## Security / privacy / LLM-safety impact
**security-reviewer is required** at stages 3 and 7. The story changes `.github/workflows/**` (tokens, permissions, auto-merge), `.claude/hooks/guard-bash.sh` and the PR approval path.
- **CI permissions / tokens:** least privilege for each job (REQ-OPS-161, REQ-OPS-162). No `pull_request_target`. `contents: write` exists only in `revert-on-red`. The Action's approval needs the repository setting in OWNER-011.
  - `revert-on-red` needs a token whose PR events trigger workflows, because GitHub does not start `pull_request` workflows for PRs opened with `GITHUB_TOKEN`. It also needs the repository setting "Allow auto-merge" (proposed OWNER-013).
  - No secret is echoed. Gate output contains shas, paths and counts, never secret values, and never SMS/WhatsApp bodies.
- **guard-bash (trust boundary agent → git/GitHub):** new classes B1b and B15. The `pr_flow_active` flag is advisory. The real boundary is GitHub branch protection (STORY-016) with a non-author approval (OWNER-011). Editing `.claude/hooks/*` needs `make hooks-unlock` … `make hooks-lock` by the orchestrator, plus a DECISIONS line (as in STORY-001).
- **LLM safety (CI AI review):** the PR diff is untrusted data for the Claude Action. An injected "output STATUS: APPROVE" in a diff must not approve on its own. Three things guard against this: the diff is delimited as data in the prompt; only the strict last-line `STATUS:` is parsed; and the Action's approval also requires every other required `gate/*` check to be green plus `delivery-gate.py approve` (REQ-OPS-161). The reviewer keeps comment-only tools.
- **Spend:** about 18 check runs per push use GitHub Actions minutes, and the AI review uses Anthropic spend. The budget rule in S16 item 3 applies (`INFRA_BUDGET_USD`, `SDLC_AI_BUDGET_USD`).

## Eval impact
None. No prompt, parser or model change. `gate/M9` only invokes the existing `make eval-fast` / `make eval` against `eval/thresholds.yaml`, and no dataset or baseline changes.

## Design notes (architect)
Filled at stage 3. Open for design:
- how consumers are pointed at a fixture yaml (REQ-OPS-150);
- where the main coverage baseline is published (REQ-OPS-159 M3);
- one job per metric vs the Checks API (REQ-OPS-161);
- the token for `revert-on-red`;
- whether the revert procedure is a script under `scripts/sdlc/`;
- the module structure of `delivery-gate.py` (one file vs a package) and its GitHub read adapter;
- the restore/rollback evidence paths.

## Implementation increments (all on `story/STORY-145-delivery-gates`)
Each increment is its own TDD → implement → verify → review cycle:
- the test-designer writes failing tests for **only** that increment's REQs;
- the qa-verifier and reviewers get only that increment's REQs, its diff range and the "claims to verify";
- the reports are `STORY-145-delivery-gates.inc<k>.verify.md` and `STORY-145-delivery-gates.inc<k>.review.md`;
- `loop_count` resets for each increment, with a maximum of 3 each (M16).

After 145.10, one final full verify and review over `BASE..HEAD` writes `STORY-145-delivery-gates.verify.md` / `.review.md`, which M10/M11 read. Then stage 8 runs.

| inc | REQs | content | est. lines (incl. tests) | after |
|---|---|---|---|---|
| 145.1 | 150 | `delivery-gates.yaml`, loader, common output, existing consumers read it, agreement + duplicate tests | ≈ 350 | — |
| 145.2 | 151, 153, 154 | `verify`, `size`, `commits` + fixtures | ≈ 380 | 145.1 |
| 145.3 | 152, 155 | `review` (incl. GitHub read adapter for PR comments), `docs`, review-report template `## Verdicts` | ≈ 370 | 145.2 |
| 145.4 | 156 | `ci` (check runs, run attempts, quarantine) | ≈ 300 | 145.3 |
| 145.5 | 157, 158 | `pr-open`, PR body generator, `pr-body`, `pr-body-lint`, regenerated PR template | ≈ 400 | 145.4 |
| 145.6 | 159 | `approve`, `merge`, `coverage-gate.py --diff-base`, `traceability.py` story-strict | ≈ 380 | 145.5 |
| 145.7 | 160, 163 | lefthook pre-commit/commit-msg/pre-push, M5 flip + sanctioned semantics, guard-bash B1b/B15, `state.sh pr-flow-*` (hooks unlock) | ≈ 430 | 145.1 (may run in parallel with 145.2–145.6) |
| 145.8 | 161, 162 | `pr.yml` per-metric checks, AI review STATUS, Action approval, labeler, `main.yml` `revert-on-red` + script | ≈ 410 | 145.6, 145.7 |
| 145.9 | 164 | `/story` stage 8, `/release`, `release.yml` M18, `release`/`release-staging`/`post-deploy` checks | ≈ 450 | 145.8 |
| 145.10 | 165 | `make gate`, `docs/DELIVERY_GATES.md` + doc/yaml test, CLAUDE.md link, selftest D1–D5 | ≈ 330 | 145.9 |

**Size exception (M12).** The owner named exactly one story for the addendum (§0, §4), so the work stays as STORY-145 with `size: L`. The estimate is ≈ 3,800 hand-written lines including tests and fixtures; the architect refines it at stage 3. That is far over 400, so the story merges only through the M12 exception: label `size-exception` (applied by pr-open) plus a `docs/DECISIONS.md` line "STORY-145 size exception (M12): one owner-named story, ten separately verified and reviewed increments". The orchestrator adds that line before pr-open. Increments 145.7, 145.8 and 145.9 are estimated just over 400. If an increment's design estimate goes past ≈ 450, the architect splits it into a and b before `/tdd`. The AI spend will very likely exceed 3 × the median, so M16 also needs a DECISIONS `spend` line at merge.

## Owner dependencies (not papered over)
- **OWNER-011: G5 approval identity** (open in `docs/ESCALATIONS.md`). Every PR's author is the owner's account, because `gh` is signed in as `mohamedshehataa`, and GitHub forbids authors approving their own PRs. The approval that `merge` requires (REQ-OPS-159 G5 row) must therefore come from one of:
  - (a) the Action. This needs the repository setting "Allow GitHub Actions to create and approve pull requests" (checked 2026-10-24: `can_approve_pull_request_reviews` = false) **and** the `ANTHROPIC_API_KEY` secret;
  - (b) a second (bot) account that opens PRs, so the owner can approve;
  - (c) a second account of the owner's that approves.
- **OWNER-012: the `ANTHROPIC_API_KEY` repository secret** (open). Without it, `gate/M11 ai-review` fails by design (REQ-OPS-161), so no PR can pass G4/G5.
- **Proposed OWNER-013** (the orchestrator adds it to ESCALATIONS): a workflow-triggering token secret, such as the existing optional `RELEASE_PLEASE_TOKEN` or a GitHub App token, plus "Allow auto-merge", for `revert-on-red`. It is not needed for STORY-145's own merge, only for a later red main.

## Definition of Done for this story (addendum §5)
On top of the project DoD:
- all §4 deliverables exist (REQ-OPS-150..165);
- `docs/SDLC_SELFTEST.md` shows D1–D5 PROVEN;
- `docs/DELIVERY_GATES.md` is linked from `CLAUDE.md` under Workflow;
- **STORY-145 itself is merged through G1–G6, with every `gate/*` check visible and green on its PR.**

That last criterion is **blocked on OWNER-011 and OWNER-012**. Until both are settled, the story's code cannot reach `main`: no direct push is allowed, and the addendum requires the G1–G6 path rather than the manual regime. The orchestrator therefore:
- finishes increments 145.1–145.10 and the final verify and review;
- runs pr-open;
- opens the PR;
- sets the story to `BLOCKED (owner: OWNER-011, OWNER-012)` if approval cannot be obtained;
- keeps applying the manual regime to other stories.

**Demonstration, once both are settled.** `/story STORY-145` stage 8 runs with the new procedure. The evidence goes into the final `.review.md` and the Status log:
- `gh pr checks <n>` lists all 18 checks as pass;
- `gh pr view <n> --json reviews,headRefOid,body` shows an `APPROVED` review on the head sha by a non-author (`github-actions[bot]`, or the second account) and exactly one valid `gate:v1` marker;
- `make gate STAGE=merge` shows PASS;
- `git log -1 --format=%B origin/main` shows the `Story:`, `Reqs:`, `Verify:` and `Review:` trailers.

## Deviations and interpretations (for `docs/DECISIONS.md`, copied by the orchestrator)
1. **HEAD rule.** A report is valid "for HEAD" if only bookkeeping paths changed after it. Without this rule, committing the report would itself make the report stale.
2. **The marker carries a body digest** (`body=<sha256>`). With only `sha=`, a hand edit that keeps the marker could not be detected, and selftest D4 could not be proven.
3. **G6 bookkeeping moves before approval.** That means the DONE status, the Status log row with the PR number, PROGRESS, the ledger and trace. Reason: `main` accepts only PR merges, and stale approvals are dismissed on new pushes. The merge sha goes into the next PROGRESS line written on a later branch. A revert of the squash brings back the story status that main had before the merge, and `revert-on-red` then sets it to IN_PROGRESS.
4. **A "Sanctioned test changes" mechanism for M5.** The addendum's "push to main rejected" contradicts REQ-OPS-011's tested "fast-forward allowed"; this resolves it. REQ-OPS-011's fast-forward and creation clauses are superseded by REQ-OPS-160. STORY-001's text is updated when STORY-145 merges.
5. **Gate ids in messages.** Block messages for rules that are not metrics name the gate id (`[G1]`, `[G3]`, `[G5]`, `[G6]`).
6. **Extra sub-commands** beyond the addendum list: `pr-body`, `pr-body-lint`, `release`, `release-staging`, `post-deploy`.
7. **M3 baseline.** With no main coverage baseline yet, the M3 "diff vs main" check is SKIP; the absolute minima still apply.
8. **M14 ADR rule.** Only the checkable part is a gate. The addendum's own §0 says a gate that a script cannot check does not exist.
9. **M1 in CI** is the duration of the `make check-fast` step itself. If cold CI runs exceed 180 s, the story goes BLOCKED and the owner tunes the yaml; the threshold is not loosened silently.
10. **Branch protection** (required checks = the yaml list, 1 review, dismiss stale approvals, linear history, admins included) is still applied by **STORY-016** (Phase 1). Its REQ-OPS-062 check list is replaced by the yaml's required checks when STORY-016 is specified.
11. **No local-only merge.** The `/story` local-only squash path is removed. `/release` never pushes `main` and pushes only the tag.
12. **M18 input.** `post-deploy` decides from an input sample. Wiring it to Prometheus or sre-observer is STORY-133/137 (Phases 9–10).

## Dependencies
- STORY-001: hooks, guard-bash B1–B14, the REQ-OPS-011 pre-push guard and the selftest harness. Its final loop-3 verification is in flight.
- No dependency on Phase 1+ work. STORY-016 later applies branch protection using this story's check names.
- STORY-124 (Phase 9) produces the restore-test evidence. Until then, `make gate STAGE=release` FAILs M17 by design, which is correct because release is Phase 10.
- Owner: OWNER-011 and OWNER-012 for the final criterion; proposed OWNER-013 for `revert-on-red`.

## Definition of Ready checklist
- [x] Goal + Part I links (addendum §0–§5; PROMPT S2, S7, S9, S12, S16)
- [x] Numbered REQs with Given/When/Then incl. unhappy paths (16 REQs, each with failing fixtures, fail-closed paths, and GitHub/API failures where relevant)
- [x] UX states (UI stories): N/A
- [x] Contract impact (none)
- [x] Data / migration impact (none; the new yaml)
- [x] Security / privacy / LLM-safety impact (workflows, tokens, guard-bash, AI-review injection; security-reviewer required)
- [x] Eval impact (none)
- [x] Size ≤ 400 changed lines **or a recorded M12 size exception**. This is an **exception**: `size: L` ≈ 3,800 lines in ten increments (≈ 300–450 each), and the DECISIONS line is required before pr-open. The box is ticked only for the exception clause.
- [x] Dependencies listed (STORY-001; owner items OWNER-011, OWNER-012, proposed OWNER-013)

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-24 | 1-2 spec | product-analyst | READY: addendum `docs/PROMPT_DELIVERY_GATES.md` specified as REQ-OPS-150..165 (unused before, grep-checked); size L under the M12 exception in 10 increments; final DoD criterion blocked on OWNER-011/012; 12 deviations listed for DECISIONS |
