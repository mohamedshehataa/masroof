---
id: STORY-145
epic: EPIC-00
title: "Delivery gates"
phase: 0
status: READY
size: L
modules: [sdlc]
depends_on: [STORY-001]
reqs: [REQ-OPS-150, REQ-OPS-151, REQ-OPS-152, REQ-OPS-153, REQ-OPS-154, REQ-OPS-155, REQ-OPS-156, REQ-OPS-157, REQ-OPS-158, REQ-OPS-159, REQ-OPS-160, REQ-OPS-161, REQ-OPS-162, REQ-OPS-163, REQ-OPS-164, REQ-OPS-165, REQ-OPS-166, REQ-OPS-167]
implements: ["PROMPT_DELIVERY_GATES.md §0-§5", "PROMPT.md S2", "PROMPT.md S7", "PROMPT.md S9", "PROMPT.md S12", "PROMPT.md S16"]
owner_agent: devops-engineer
---

# STORY-145 — Delivery gates

## Goal
Every move of code (commit → push → PR → approval → merge → tag → deploy) is a gate with a numeric threshold and a machine check. All thresholds live in one file, `scripts/quality/delivery-gates.yaml`, and hooks, `make` and CI all read it ([docs/PROMPT_DELIVERY_GATES.md](../../PROMPT_DELIVERY_GATES.md) §0–§5). This is the owner's binding addendum and wins where it conflicts with `docs/PROMPT.md`. It extends PROMPT S2 (stage 8 gates), S7 (hooks), S9 (thresholds), S12 (git workflow and CI) and S16 (approvals are gates, not people). Until this story merges, the interim route in "Dependencies" applies.

Terms used below:
- **The story**: the story named by the branch (`story/STORY-NNN-<slug>`, `hotfix/STORY-NNN-<slug>`), by a revert's source commit, or by `--story`.
- **Merge-base**: `git merge-base origin/main HEAD`. **The branch diff**: `<merge-base>..HEAD`.
- **The PR**: the open PR whose head is the branch. CI knows it; locally it is passed as `--pr <n>` or `make gate … PR=<n>`.
- **Profile**: the branch-class gate profile of REQ-OPS-166.
- **Bookkeeping paths** (yaml): the story file (restricted, see below), its `*.verify.md` and `*.review.md` (increment reports included), `docs/PROGRESS.md`, `docs/SDLC_METRICS.md`, `docs/TRACEABILITY.md`, `docs/DECISIONS.md` and `docs/ESCALATIONS.md`.
- **The HEAD rule (strict)**: a report sha `R` is valid for HEAD when `R` resolves to a commit that is an ancestor of HEAD, and either `R == HEAD` or every path in `git diff --name-only R HEAD` is a bookkeeping path.
  - After a report, the story file may change only in its frontmatter `status:` line and in rows appended to `## Status log`. Any other change makes the report stale, and the FAIL names the changed section heading.
  - A rebase that rewrites non-bookkeeping commits makes every report stale, because `R` is no longer an ancestor. Stages 6–7 then re-run as loop k+1, which counts toward M16.
- **A DECISIONS line for X**: a line anywhere in `docs/DECISIONS.md` **at HEAD** that contains the story id and X. It may have been committed outside the branch diff, for example on the STORY-002 branch.

## Requirements and acceptance criteria

### REQ-OPS-150 — One threshold file, read by hooks, make and CI
**Given** `scripts/quality/delivery-gates.yaml` **When** any consumer evaluates a delivery metric **Then** it takes the threshold from that file, or from the file and key it references. It never uses a literal of its own. The file defines:

| id | value(s) |
|---|---|
| M1 | `make check-fast` for changed modules: exit 0, wall time ≤ 180 s |
| M2 | `make check`: exit 0, wall time ≤ 900 s |
| M3 | only `ref:` entries: `scripts/quality/quality-thresholds.yaml#backend`, `#android`, `#web` (per-module minima) and `#diff_vs_main.coverage_max_drop_pp` (0.5; new key in that file) |
| M4 | only `ref:` entries: `quality-thresholds.yaml#backend.packages` (mutation ≥ 80 for `money`, `parsing`, `dedup`) and `#diff_vs_main.mutation_max_drop_pp` (0.0; new key) |
| M5 | 0 lost assertions, 0 new skip/disable/only, 0 assertion flips, 0 unsanctioned deleted test files |
| M6 | traceability strict: 0 unknown REQ ids; every REQ of the story and of DONE stories has ≥ 1 passing test |
| M7 | redocly errors 0; oasdiff breaking changes 0 unless label `breaking-change` **and** an ADR path in the body's ADRs section |
| M8 | gitleaks 0; semgrep ERROR 0; osv-scanner High/Critical with a fix 0; trivy High/Critical 0; added suppressions without inline reason 0 |
| M9 | only `ref:` entries into `eval/thresholds.yaml` (`regression.tolerance_pp`, `cost`, OTP leak, injection obeyed); AI-affecting globs: `**/prompts/**`, `**/parsing/**`, `**/parser/**`, `**/dedup/**`, `**/normaliz*/**`, `**/classif*/**`, `eval/**`, `fixtures/**`, `**/application*.yml` |
| M10 | exploratory cases ≥ 10; BLOCKER/MAJOR findings 0 |
| M11 | open BLOCKER/MAJOR 0 (report rows and PR comments); MINOR fixed or ticketed |
| M12 | ≤ 400 hand-written added lines; exclusion globs (REQ-OPS-153) |
| M13 | subject ≤ 72 characters; commits behind origin/main 0 |
| M14 | dependency-file globs (REQ-OPS-155) |
| M15 | flaky quarantine ≤ 5 |
| M16 | verify/review loops ≤ 3; story AI spend ≤ 3 × the median of DONE stories |
| M17 | k6 p95 reads < 200 ms, writes < 400 ms (S9); Lighthouse performance ≥ 90, accessibility = 100, best practices ≥ 95; rollback tested ≤ 30 days ago; restore test ≤ 30 days ago |
| M18 | error rate < 1 %; parse-failure rate < 5 %; window ≥ 30 min |
| G7 | last 3 `main.yml` runs green; nightly ≤ 24 h ago; selftest GREEN ≤ 7 days ago |
| `release_paths` | the only paths a `release/v*` branch may change: `CHANGELOG.md`, the version files (named by the architect at design), `docs/DEPLOY.md`, `docs/PROGRESS.md`, `docs/SDLC_METRICS.md` (incl. the AI spend ledger) |
| `required_checks` | exactly: `gate/M1 check-fast`, `gate/M2 check`, `gate/M3 coverage`, `gate/M4 mutation`, `gate/M5 test-integrity`, `gate/M6 traceability`, `gate/M7 contract`, `gate/M8 security`, `gate/M9 eval`, `gate/M11 ai-review`, `gate/M12 size`, `gate/M13 commits`, `gate/M14 docs`, `pr-body-lint` (14; addendum G5; `gate/M15 ci` is never in it, because it cannot wait for itself) |
| `approvers` | `[github-actions[bot], mohamedshehataa]` (G5; approval must come from a listed login that is not the PR author) |
| `profiles` | branch-class profiles (REQ-OPS-166) |
| `commands` | per-metric command lines: check-fast, check, test-integrity, security, eval-fast, eval, mutation, trace (stubbed in fixtures) |
| — | suppression patterns (REQ-OPS-161 gate/M8); bookkeeping paths; module path map (`backend/` → backend, `web/` → web, `android/` → android, everything else → sdlc) |

**Duplicates.** A duplicate is one metric's threshold value present in more than one file. The M3, M4 and M9 entries in `delivery-gates.yaml` hold only `ref: <file>#<key>` items. A test fails if any of them holds a number, if a `ref:` does not resolve, or if `flaky.max_quarantined` remains in `quality-thresholds.yaml` (it moves to M15). `commit-msg-lint.sh` must not contain the literal `MAX_LEN=72`.

**Consumers:** `commit-msg-lint.sh` (M13 maximum), `pre-push-guard.sh` (M1), `coverage-gate.py` (M3/M4 drops through the `ref:`, M15 quarantine maximum), `delivery-gate.py` (every sub-command), `make gate`, and the workflows (only through `make` / `delivery-gate.py`).

**Agreement test.** **Given** a fixture copy of the file with M13 72 → 50, M1 180 → 1 s, M15 5 → 0 and M12 400 → 10. Consumers are pointed at the copy through one environment variable or option, named in the design notes. **Then**:
- a valid 60-character subject is rejected by `commit-msg-lint.sh` and by `delivery-gate.py commits`, both naming `M13`;
- `pre-push-guard.sh` with a 2 s `check-fast` stub rejects with `[M1] … 2 s > 1 s`;
- `coverage-gate.py` with 1 quarantined test FAILs;
- `delivery-gate.py size` on 11 added lines FAILs `M12`.

With the original file, all of these pass. A static test asserts that every job in `pr.yml`, `main.yml` and `release.yml` that produces a `gate/*` or `pr-body-lint` check runs a `make` target or `delivery-gate.py`. No `run:` step of those jobs passes `--threshold`, `--max*`, `--min*` or `--limit`.

**Verification by increment** (each leg is verified in the increment that builds it):
- 145.1: the yaml, the loader, the common output contract, the duplicate test, the `commit-msg-lint.sh` and `coverage-gate.py` legs of the agreement test, and the configuration/usage unhappy paths;
- 145.2: the `commits` and `size` legs;
- 145.7: the pre-push leg;
- 145.8: the workflow static test.

**Common output contract** (every `delivery-gate.py` sub-command):
- a table with columns `metric | check | measured | threshold | result`. The result is `PASS`, `FAIL` or `SKIP`, and SKIP always gives a reason (`not applicable: <reason>` for profile exemptions);
- for each FAIL row, the line `FAIL <Mn|Gn> <check>: <reason>. Re-run: make gate STAGE=<sub-command>`;
- a final line `DELIVERY_GATE <sub-command>: PASS|FAIL (<p> pass, <f> fail, <s> skip)`;
- exit 0 for 0 FAIL rows, 1 otherwise, 2 for a usage or configuration error.

Unhappy path (configuration): **Given** the file is missing, unparseable, lacks a needed key, or has a `ref:` that does not resolve **When** a consumer runs **Then** it fails closed and names the file and key (or the parse error line). `delivery-gate.py`, `commit-msg-lint.sh` and `coverage-gate.py` exit 2; the pre-push hook rejects the push. No consumer falls back to a built-in default.
Unhappy path (usage): an unknown sub-command exits 2 and lists the valid ones. A branch matching no profile without `--story`/`--pr` exits 2 with `no gate profile for branch <b>`. A `--story` id with no story file exits 2.
Fixtures (addendum §4): there is one passing-PR fixture per profile, and per metric a fixture that fails exactly that metric. Tests need no network, Docker or GitHub token. GitHub data comes from recorded JSON, and the clock is fixed.
Non-functional: warm `make check-fast MODULE=sdlc` stays **< 180 s**; it is run twice and the second run is measured.

### REQ-OPS-151 — `delivery-gate.py verify` (M10)
**Given** the story **When** `delivery-gate.py verify` runs **Then** it PASSes only if all of these hold:
1. `<story>.verify.md` exists beside the story file and is tracked;
2. its last non-empty line is `STATUS: PASS`;
3. its `**Head:**` sha satisfies the strict HEAD rule;
4. its Findings table has 0 rows with severity `BLOCKER` or `MAJOR`;
5. in `## Exploratory`, the first integer that is immediately followed by the word `cases` is ≥ 10;
6. the `## Test integrity` section starts with `OK`, and the report has no line `TEST_INTEGRITY: VIOLATION`;
7. the `**Verifier:**` name is `qa-verifier` and differs from the story's `owner_agent`.

Example: Head is `HEAD~1`, and HEAD only appended a `docs/PROGRESS.md` line and a Status log row → PASS.
Unhappy paths, each a FAIL row `M10` with `Re-run: make gate STAGE=verify`:
- no report → `no STORY-145-delivery-gates.verify.md`;
- the report is untracked → `not committed`;
- `STATUS: FAIL`;
- HEAD changed `scripts/quality/delivery-gate.py` after Head → `stale`, naming that path;
- HEAD changed `### REQ-OPS-153` text in the story after Head → `stale: story section "Requirements and acceptance criteria" changed`;
- Head is not an ancestor (after a rebase) → `stale: rebased; re-run stages 6-7 as loop k+1`;
- `9 cases tried` → `9 < 10`;
- a `TEST_INTEGRITY: VIOLATION` line under a section that starts `OK` → FAIL;
- `Verifier: devops-engineer` on a story owned by `devops-engineer`;
- one `MAJOR` row;
- no Head line → `Head sha not found` (exit 1).

On profiles where M10 is exempt (REQ-OPS-166), the row is `SKIP not applicable: <reason>`.

### REQ-OPS-152 — `delivery-gate.py review` (M11)
**Given** the story **When** `delivery-gate.py review` runs **Then** it PASSes only if all of these hold:
1. `<story>.review.md` is tracked, and its last non-empty line is `STATUS: APPROVE`;
2. the head sha of its `**Diff:** git diff <base>...<head>` line satisfies the strict HEAD rule;
3. its `## Verdicts` table (`| reviewer | status | loop |`, added to `docs/templates/review-report.md`) has an `APPROVE` row for `code-reviewer` and for every specialist that `.claude/skills/story/triggers.md` triggers for `git diff --name-only <merge-base>...HEAD`. The trigger rules are scripted as follows:
   - the path column matches the changed paths;
   - "Also when" for a story section counts unless the section is None, meaning its first word is `None` or `N/A`, case-insensitive. This applies to Security for `security-reviewer`, UX states for `ux-a11y-reviewer` + `i18n-rtl-reviewer`, and Eval impact for `ai-eval-engineer`;
   - `performance-engineer` "tagged perf" means `perf` appears in the frontmatter `tags:`;
   - the clause "touches a p95 budget in S9" is not gated (see deviations);
4. every `BLOCKER`/`MAJOR` row has Resolution `fixed <sha>`, with the sha reachable from HEAD;
5. every `MINOR` row has `fixed <sha>` (reachable from HEAD) or `ticketed STORY-NNN` (an existing story file). `NIT` rows are ignored;
6. when the PR is known, every PR review comment whose body starts with `[BLOCKER]` or `[MAJOR]` is resolved. Resolved means either a PR commit has the trailer `Resolves: <comment html_url>`, or a reply in its thread contains `[WONTFIX STORY-NNN]` naming an existing story. With no PR, this row is `SKIP no PR yet`.

Unhappy paths (FAIL `M11`, `Re-run: make gate STAGE=review`):
- the diff touches `contracts/openapi.yaml` and the verdicts list only `code-reviewer` → `security-reviewer verdict missing`;
- the Security section reads `none — docs only` → counts as None, and no security verdict is required (PASS fixture);
- a MINOR row with Resolution `later`;
- `fixed 1234abc` where the sha is not in the branch;
- `STATUS: CHANGES_REQUESTED`;
- the review head is stale;
- two unresolved `[MAJOR]` PR comments → FAIL listing both URLs;
- `[WONTFIX STORY-999]` with no STORY-999 file → unresolved;
- the GitHub API returns 401/403/5xx or times out → FAIL closed: `GitHub API unavailable (HTTP 502)`.

### REQ-OPS-153 — `delivery-gate.py size` (M12)
**Given** the branch diff **When** `delivery-gate.py size` runs **Then** the count is the sum of the **added** column of `git diff --numstat <merge-base>..HEAD`. A modified line counts once, and binary files count 0. Paths matching these exclusion globs (held in the yaml) are left out:
- generated code: `**/generated/**`, `web/src/api/generated/**`;
- lockfiles: `**/gradle.lockfile`, `**/buildscript-gradle.lockfile`, `**/package-lock.json`, `**/gradle/verification-metadata.xml`;
- wrappers: `**/gradlew`, `**/gradlew.bat`, `**/gradle/wrapper/**`;
- vendored code: `**/vendor/**`;
- spec and report artefacts: `docs/backlog/**`;
- generated docs: `docs/TRACEABILITY.md`, `docs/SDLC_METRICS.md`, `docs/SDLC_SELFTEST.md`.

The result is PASS when the count is ≤ 400. It is also PASS under the size exception: a DECISIONS line for `size exception` exists at HEAD, **and**, when the PR is known, the PR has label `size-exception`. The output lists the 5 largest files.
Examples:
- 400 → PASS;
- 1,200 added lines under `web/src/api/generated/` plus 10 elsewhere → 10, PASS;
- 900 with the DECISIONS line committed on another branch (present at HEAD) and the label → PASS, showing `exception (DECISIONS: <line>)`.

Unhappy paths (FAIL `M12`):
- 401 → `401 > 400 hand-written lines; over 400 needs label size-exception + a DECISIONS line. Re-run: make gate STAGE=size`;
- the DECISIONS line is present, a PR is known, and the label is missing → `label size-exception missing`;
- the label is present without the DECISIONS line → `no DECISIONS size-exception line for STORY-NNN`.

### REQ-OPS-154 — `delivery-gate.py commits` (M13)
**Given** the branch diff **When** `delivery-gate.py commits` runs **Then**, for every commit in `<merge-base>..HEAD`:
- the subject passes `commit-msg-lint.sh` (format, tag, ≤ yaml maximum 72) in the form the profile requires;
- the subject does not start with `Revert "`;
- the commit has exactly one parent;
- the subject does not start with `fixup!`, `squash!` or `amend!`;
- the body has a `Signed-off-by:` trailer whose e-mail equals the author e-mail;
- on `story/` and `hotfix/` profiles, the body has a `Story: STORY-NNN` trailer for the branch's story.

Also, the branch is 0 commits behind: `git rev-list --count HEAD..origin/main` is 0.
Unhappy paths (FAIL `M13`, naming the short sha and the remedy):
- a 73-character subject → `73 > 72`;
- subject `Revert "feat(x): y [STORY-014]"` → `use revert(<scope>): … [STORY-NNN]`;
- a merge commit → `merge commit on the branch; rebase instead`;
- `fixup! …` → `autosquash first: git rebase -i --autosquash origin/main`;
- no `Signed-off-by` → `re-run after git rebase --signoff origin/main`;
- missing or mismatched `Story:` trailer (for example `Story: STORY-144` on `story/STORY-145-delivery-gates`) → `re-run after git rebase -x 'git commit --amend --no-edit -s --trailer "Story: STORY-NNN"' origin/main`;
- 2 commits behind → `2 commits behind origin/main; re-run after git rebase origin/main`;
- `origin/main` unknown → `run git fetch origin`.

### REQ-OPS-155 — `delivery-gate.py docs` (M14)
**Given** the branch diff **When** `delivery-gate.py docs` runs **Then** it PASSes only if all of these hold:
- (a) the story `status` is not `DRAFT`;
- (b) the diff adds ≥ 1 `docs/PROGRESS.md` line in the PROGRESS format containing ` · STORY-NNN · ` (deviation 13: a line cannot name its own commit's sha);
- (c) for **each** changed dependency file, a DECISIONS line for that path exists. The yaml list of dependency files: `**/build.gradle.kts`, `**/settings.gradle.kts`, `**/libs.versions.toml`, `**/gradle.lockfile`, `**/package.json`, `**/package-lock.json`, `**/requirements*.txt`, a changed `FROM` line in `**/Dockerfile`, and a changed `uses: <action>@<ref>` line in `.github/workflows/*.yml`;
- (d) for **each** file that gains a suppression (patterns of REQ-OPS-161 gate/M8), a DECISIONS line containing the story id, that path and `suppression` exists. The inline `reason:` rule is M8 only (REQ-OPS-161), not M14;
- (e) every ADR the story references (`ADR-NNNN` or `docs/adr/NNNN-*.md`) exists, and every ADR file the diff adds is referenced by the story.

Unhappy paths, one fixture each (FAIL `M14`, `Re-run: make gate STAGE=docs`):
- status `DRAFT`;
- no PROGRESS line for the story;
- 2 dependency files changed (`backend/build.gradle.kts`, `web/package.json`) with 1 DECISIONS line naming only the first → FAIL naming `web/package.json`;
- `scripts/x.sh` gains `# shellcheck disable=SC2086 # reason: … STORY-145` with no DECISIONS suppression line for it → FAIL `M14` (and the separate M8 fixture shows that the inline reason passes M8);
- the story links `docs/adr/0099-x.md`, which does not exist;
- an added `docs/adr/0007-y.md` that the story does not reference.

Whether a design note is "significant" enough for an ADR stays a judgement for the architect and spec-reviewer (deviation 8).

### REQ-OPS-156 — `delivery-gate.py ci` (M15)
**Given** the PR with head sha `S` **When** `delivery-gate.py ci` runs **Then** it PASSes only if:
1. for every name in `required_checks` (which never includes `gate/M15 ci` itself), the **latest check run on `S` from a `pull_request`-event workflow run** is completed with conclusion `success`. Push-event runs on the same sha are ignored. A missing, pending, `failure`, `cancelled`, `skipped`, `neutral` or `timed_out` run → FAIL naming the check and its state;
2. **no silent flaky re-run**: for each `pull_request` workflow run on `S` with `run_attempt > 1`, a job is a violation when an earlier attempt concluded `failure` or `timed_out` and a later attempt concluded `success`. A prior `cancelled` attempt is not a violation. The violation stands unless `docs/FLAKY_TESTS.md` at `S` has a row naming that check and a `STORY-NNN`. In CI this comparison runs as step `flaky-guard` of check `gate/M15 ci`;
3. quarantine rows in `docs/FLAKY_TESTS.md` are ≤ 5.

Recorded-JSON fixtures:
- all green → PASS;
- one `failure` → FAIL;
- one `pending` → FAIL;
- `gate/M1 check-fast` failed in attempt 1 and passed in attempt 2, with no quarantine row → FAIL `M15 ci: gate/M1 check-fast passed only on re-run (attempt 2) without a quarantine entry`;
- the same with a quarantine row → PASS;
- attempt 1 `timed_out`, attempt 2 `success`, no quarantine row → FAIL;
- attempt 1 `cancelled`, attempt 2 `success` → PASS (not a violation);
- 6 quarantine rows → FAIL `6 > 5`;
- `S` has a red push-event `gate/M1 check-fast` and a green pull_request-event one → PASS (only the pull_request run counts).

Unhappy path: no PR → FAIL `no open PR for <branch>`. GitHub unreachable or 401/403/5xx → FAIL closed.

### REQ-OPS-157 — `delivery-gate.py pr-open` composite (G3)
**Given** a `story/`, `hotfix/` or `release/v*` branch **When** `delivery-gate.py pr-open --title "<title>"` runs **Then** it reports, for its profile (REQ-OPS-166), these rows:
- story `status: IN_PROGRESS` (story and hotfix profiles);
- the branch name matches the profile pattern, with `NNN` and `<slug>` matching the story file;
- 0 commits behind origin/main;
- the title passes `commit-msg-lint.sh` in the profile's M13 form;
- M10, M11 (with the PR row `SKIP`), M12, M13, M14 (or `SKIP not applicable` per profile);
- M2: `make check` exits 0 within ≤ 900 s.

M2 runs last; after an earlier FAIL it is `SKIP not run: earlier failure`, and the exit code is still 1. On PASS it writes the generated body (REQ-OPS-158) to an untracked path that it prints, and prints the labels:
- module labels, using the `.github/labeler.yml` path mapping;
- `security-review` when security-reviewer triggers;
- `breaking-change` only when oasdiff reported a breaking change **and** the ADRs section lists one;
- `size-exception` when M12 passed by exception.

On `revert/*` or `dependabot/*` branches, it exits 2 with `pr-open is not used for profile <p>`. **Re-entry:** when an open PR already exists for the branch (a stage-8 fix loop), pr-open runs every row and writes the body, but the skill does not run `gh pr create`; it updates the existing PR with `gh pr edit --body-file` (REQ-OPS-164).
Unhappy paths:
- status `READY` → FAIL `G3 status`;
- `story/STORY-144-x` while the story is STORY-145 → FAIL `G3 branch`;
- no verify report → FAIL `M10`, and no body file is written;
- a stale verify Head → FAIL `M10`;
- these fixtures pass `--story STORY-145` explicitly;
- a `make check` stub exits 1 → FAIL `M2`, with the log path;
- 901 s → FAIL `M2 901 s > 900 s`;
- an 80-character title → FAIL `M13`.

### REQ-OPS-158 — Generated PR body with the `gate:v1` marker, and `pr-body-lint`
**Given** pr-open passed, or `delivery-gate.py pr-body` runs for HEAD **Then** the body is generated from `.github/pull_request_template.md` (regenerated by this story), with these sections filled:
- **Story:** path, title and phase;
- **Summary:** the story's Goal paragraph;
- **REQs:** each REQ of the story with its test names from traceability (`no test` where there is none);
- **Screenshots:** links to the screenshot artifacts of the run, or `no UI` when no UI path changed;
- **Verify / Review:** report paths, head shas and STATUS;
- **Coverage:** before (the main baseline) and after (HEAD) per changed module, or `n/a: no main baseline` (allowed only under REQ-OPS-159's SKIP rule);
- **Eval delta:** the M9 table, or `not AI-affecting`;
- **Security:** per-scanner counts from the latest local `make security` for HEAD, or `pending gate/M8`;
- **ADRs:** every ADR referenced by the story or added by the diff (the M14(e) set), or `none`;
- **Size:** the M12 count, and the exception if any;
- **DECISIONS:** the lines the branch adds plus the DECISIONS lines for the story at HEAD, verbatim;
- **Migration notes:** added `V*__*.sql` files with expand/migrate/contract notes from the story, or `no migration`;
- **Rollback plan:** the story's `## Rollback plan` section, or a generated `git revert <squash sha>` plus the migrations, or `no migration`;
- **Definition of Done:** the S2 DoD checklist, each box ticked from the gate results.

**Sources per profile:**
- **release:** Story is `none: release vX.Y.Z`; Summary is the version's CHANGELOG section; REQs and Verify/Review are `not applicable: release bookkeeping`;
- **revert:** Story is the reverted story, or `none` for `[SDLC]`/`[PHASE-N]`; Summary is `revert of <sha>: run <url> red`; Verify/Review are `not applicable: revert of <sha>`;
- **story and hotfix** use the sources listed above.

There is one byte-exact expected-body fixture per profile (story, hotfix, release, revert).

The last line is `<!-- gate:v1 sha=<40-hex HEAD> body=<64-hex sha256 of the LF-normalised text above the marker> -->`. Rules:
- generating twice for the same HEAD and inputs gives byte-identical output;
- no template placeholder is left unfilled;
- inserted text that looks like a marker is neutralised.

**Given** a PR **When** the `pr-body-lint` check (CI on `opened`, `edited`, `synchronize`, `reopened`) or `make gate STAGE=pr-body-lint PR=<n>` runs **Then** it PASSes only if there is exactly one marker, its `sha` equals the PR head sha, and its `body` digest matches. On the `dependabot` profile it is `not applicable`.
Unhappy paths (FAIL `pr-body-lint`):
- no marker (body typed from the UI template) → `no gate:v1 marker`;
- one character edited → `digest mismatch: the body was edited by hand. Re-run: make gate STAGE=pr-body`;
- a push without regeneration → `stale: marker sha abc1234 ≠ head def5678`;
- two markers → FAIL;
- a DECISIONS line containing `<!-- gate:v1 sha=… -->` is neutralised at generation, so lint sees one marker and passes.

### REQ-OPS-159 — `approve` and `merge` composites (G5, G6)
**Given** the PR **When** `delivery-gate.py approve` runs **Then** its rows are M10, M11 (incl. PR comments), M15 and M16, each subject to the profile (exempt rows are `SKIP not applicable: <reason>`). With no PR → FAIL `no open PR`.
**When** `delivery-gate.py merge` runs **Then** its rows are:
- **G5 approval:** ≥ 1 review in state `APPROVED` with `commit_id` equal to the head sha, from a login in yaml `approvers` that is not the PR author. Otherwise FAIL `G5 approval: no approval on head <sha> from a listed non-author (approvers: github-actions[bot], mohamedshehataa; see OWNER-011)`;
- **M2:** `make check` on HEAD, which must be 0 behind origin/main;
- **M3:** `coverage-gate.py --diff-base main`, using the coverage baseline that `main.yml` publishes per green main run (REQ-OPS-161). Each changed module's line/branch value (overall and per token) must meet the `quality-thresholds.yaml` minima and be ≥ main − 0.5 pp. With main at 88.0 %: 87.5 % → PASS; 87.4 % → FAIL `M3 coverage: backend line 87.4% < main 88.0% - 0.5 pp`. `SKIP no main baseline` is allowed **only** while no green `main.yml` run exists since the baseline job landed; after that, a missing baseline is FAIL;
- **M4:** when the diff touches a `money`, `parsing` or `dedup` package, `make mutation` runs, then `coverage-gate.py --require-mutation`: ≥ 80 % and ≥ main's published mutation baseline. Otherwise `SKIP no mutable package changed`;
- **M6 strict:** `traceability.py --strict` exits 0, and every REQ in the story frontmatter has ≥ 1 passing test result. A REQ with only failing or result-less tests → FAIL naming it;
- **M8 full:** `make security` exits 0;
- **M9 full:** when an AI-affecting path changed, `make eval` exits 0. Otherwise `SKIP not AI-affecting`;
- **M15**, as in REQ-OPS-156;
- **M16:** every verify report of the story (increment reports included) has `Loop: n` with n ≤ 3. The story's AI-spend ledger sum must be ≤ 3 × the median per-story USD of DONE stories with ledger rows. With fewer than 3 such stories → `SKIP` (deviation 15). Over the limit passes only with a DECISIONS line for `spend`. No ledger row → FAIL `no AI spend ledger row for STORY-NNN`.

Fixtures: the passing-PR fixture of each profile passes `approve` and `merge`. For each of M2, M3, M4, M6, M8, M9, M10, M11, M15, M16 and G5, one fixture fails exactly that row, with the commands stubbed through the yaml. The composite exits 1 and names only that metric. G5 fixtures:
- an approval on the previous head sha → FAIL;
- an approval by the unlisted login `someone-else` → FAIL;
- an approval by the PR author `mohamedshehataa` on their own PR → FAIL;
- an approval by `github-actions[bot]` on the head → PASS.

A green main run with the baseline job but no baseline artifact → M3 FAIL.

### REQ-OPS-160 — Local git hooks and local Claude hooks (G1)
**pre-commit.** The existing commands stay (gitleaks, shellcheck, the formatters, no-env). Added:
- (a) **M5 on staged test files**: the index is compared with HEAD under `test-integrity-check.sh` rules. Unstaged changes are ignored;
- (b) **M8 inline reason**: a staged **added** suppression line without `reason:` and a STORY id, on the line or on the comment line directly above it, is rejected with `[M8]`.

**commit-msg.**
- `commit-msg-lint.sh` runs with the yaml maximum.
- On `story/STORY-NNN-*` and `hotfix/STORY-NNN-*` branches, `Story: STORY-NNN` is appended if absent.
- `Signed-off-by: <user.name> <user.email>` is appended if absent.
- A subject tag `[STORY-MMM]` with MMM ≠ NNN is rejected with `[M13]`.
- Amending does not duplicate trailers. Other branches get no `Story:` trailer.
- `Merge …`, `Revert "…"` and `fixup!` keep passing here; M13 rejects them at PR time.

**pre-push** (`pre-push-guard.sh`), per ref line:
- **any** update of `refs/heads/main` or `refs/heads/master` is rejected: fast-forward, creation, deletion or non-fast-forward. The message is `[G1] pushing to main is forbidden; only PR merges create commits on main. Re-run: make gate STAGE=pr-open (via /story)`. This supersedes REQ-OPS-011's fast-forward and creation clauses (see Sanctioned test changes);
- `refs/tags/*` and branch deletions pass without M1/M5;
- any other branch update runs:
  - **M1**: the modules changed in `origin/main...<local sha>` are found through the module map. If, under one of them, there are tracked modifications or untracked non-ignored files, the push is rejected with `[M1] commit or stash changes in <module>/ before pushing`. Then `make check-fast MODULE=<modules>` must exit 0 within ≤ 180 s;
  - **M5**: `test-integrity-check.sh origin/main` evaluated on the **pushed commit**, with `--require-increase` on the hotfix profile (REQ-OPS-167);
  - for `refs/heads/maint/v*`, the M1/M5 base is `origin/maint/vX.Y`, or the tag `vX.Y.Z` when the branch is new on the remote. Fixtures: a cherry-pick pushed to an existing `maint/v1.1` is compared with `origin/maint/v1.1`; the first push of `maint/v1.1` is compared with `v1.1.0`;
  - reports: an untracked `STORY-NNN*.verify.md` or `*.review.md` for the branch's story → rejected with `[M10]` or `[M11]`;
- `origin/main` unknown → rejected with `run git fetch origin`.

**Claude hook (enforcement map: "no unlogged suppression | local hook").** `.claude/hooks/scan-secrets.sh` blocks an Edit or Write that **introduces** a suppression line lacking `reason:` and a STORY id, on the line or on the comment line directly above it. "Introduces" means:
- for Edit, the line is in `new_string` and not in `old_string`;
- for Write, the line is not in the file currently on disk.

The block uses exit 2 with `[M8] suppression without reason: + STORY id`. Its tests are in `test-scan-secrets.sh`.

**M5 semantics** (pre-commit, pre-push, `make test-integrity`, `gate/M5`):
- an assertion flip is a `VIOLATION` (exit 1). A flip is a drop in one assertion id's count while the total does not drop. The `WARN:` line is still printed;
- a flip or deletion is allowed only when the story file has a Sanctioned test changes row matching **file + assertion id as printed by test-integrity** (kinds `flip` or `deletion` only), **and** a DECISIONS line `STORY-NNN sanctioned test change: <file> <assertion id>` exists (phrase matching is case-insensitive). Such a change is printed as `SANCTIONED (<story> <superseded-by REQ>)`;
- the allowance per (file, assertion id) equals the number of matching rows, and a larger drop is a VIOLATION. Fixture: 2 rows for (`test-pre-push-guard.sh`, `expect 0`) and 3 dropped `expect 0` → VIOLATION;
- `--allow-deletions` keeps working.

Every rejection is one line starting with `[<id>]`, plus a `Re-run:` command. Tests run the hooks as real git hooks in temp repos with a bare remote.

### REQ-OPS-161 — `pr.yml`: one check run per metric, `pr-body-lint`, AI review, Action approval; `main.yml` baselines
**Given** a PR to `main`, or a push to `story/**`, `hotfix/**` or `release/v*` **When** `pr.yml` runs **Then** the PR head sha carries check runs named exactly:
`gate/M1 check-fast`, `gate/M2 check`, `gate/M3 coverage`, `gate/M4 mutation`, `gate/M5 test-integrity`, `gate/M6 traceability`, `gate/M7 contract`, `gate/M8 security`, `gate/M9 eval`, `gate/M10 verify`, `gate/M11 review`, `gate/M11 ai-review`, `gate/M12 size`, `gate/M13 commits`, `gate/M14 docs`, `gate/M15 ci`, `gate/M16 loop-budget`, `pr-body-lint`.

The architect chooses between one job per metric and the Checks API from a shared job.

**Triggers and runs.**
- PR triggers include `edited`.
- Gate jobs check out `github.event.pull_request.head.sha`, never the merge ref.
- A **push-event run exits early** (one notice, no gate check runs) when an open PR exists for the branch.
- Without a PR, a push produces the G2 subset: M1, M5, M6 (non-strict), M7, M8 fast, M9 fast, M12, M13.
- There is no aggregate check: job "PR gate" is removed.

**Check results.**
- A metric that does not apply (module absent, no AI path, a profile exemption) concludes `success` with `not applicable: <reason>`. It never concludes `skipped`.
- Each failing check's summary names the metric id, the measured value and the threshold, and the re-run command.
- A branch with no profile → every gate check fails with `no gate profile`.

**Per-metric rules.**
- `gate/M7`: a breaking oasdiff passes only with label `breaking-change` **and** a `docs/adr/NNNN-*.md` path in the body's **ADRs** section.
- `gate/M8` on a PR runs gitleaks, semgrep, osv-scanner and **trivy fs**, plus the **inline-reason rule**. Every added line matching a suppression pattern must carry `reason:` and a STORY id, on the line or on the comment line directly above it. The yaml patterns: `@SuppressWarnings(`, `@Suppress(`, `nosemgrep`, `eslint-disable`, `@ts-ignore`, `@ts-expect-error`, `# noqa`, `# type: ignore`, `noinspection`, `NOSONAR`, `shellcheck disable=`, `gitleaks:allow`, plus any line added to `.trivyignore`, `.gitleaksignore`, `.semgrepignore` or `osv-scanner.toml`. Its fixtures are separate from M14(d): a missing reason fails M8 only. The same rule is also available as `make gate STAGE=suppressions`.
- `gate/M15 ci` contains the step `flaky-guard` (REQ-OPS-156).

**AI review.** `gate/M11 ai-review` runs `anthropics/claude-code-action` with the code-reviewer procedure, plus security-reviewer when the PR has label `security-review`. The diff is wrapped as delimited, untrusted data. The final message must end with `STATUS: APPROVE` or `STATUS: CHANGES_REQUESTED`.
- `APPROVE` → success.
- `CHANGES_REQUESTED`, or no parsable last line → failure.
- No `ANTHROPIC_API_KEY` → failure `ANTHROPIC_API_KEY secret missing (OWNER-012)`. The old skip behaviour is removed.
- The prompt requires every inline comment to start with `[BLOCKER]`, `[MAJOR]`, `[MINOR]` or `[NIT]`. A static test asserts that the prompt text contains this rule and the `STATUS:` last-line rule.
- On Dependabot-triggered runs, GitHub exposes only **Dependabot secrets**, so `ANTHROPIC_API_KEY` must also be set as a Dependabot secret (OWNER-012). A static test asserts that the ai-review job reads `secrets.ANTHROPIC_API_KEY` without a condition that excludes the `dependabot[bot]` actor, so the key reaches the job on dependabot runs.

**Action approval.** When every `required_checks` entry is `success` on the head, `gate/M11 ai-review` is APPROVE, and `delivery-gate.py approve` (for the profile) exits 0, a job submits an `APPROVED` review on the head sha as `github-actions[bot]`. GitHub 403/422 → the job fails with `GitHub refused the Action's approval: enable "Allow GitHub Actions to create and approve pull requests" (OWNER-011)`.

**Labels and permissions.**
- `labeler.yml` defines none of `size-exception`, `security-review` or `breaking-change`, and pr-open's module labels use the `labeler.yml` mapping.
- The workflow default is `contents: read`. `pull-requests: write` is granted only to the AI-review, approval and labeler jobs; `checks: write` only where check runs are created. No `contents: write`, and no `pull_request_target`.

**Baselines (145.8).** On every green run, `main.yml` publishes a coverage baseline (from `make check`) and a mutation baseline (`make mutation` on `money`/`parsing`/`dedup`; empty while none exist) as the artifact the design names. REQ-OPS-159 reads them.

Static tests check names, triggers, the early exit, the checkout ref, permissions and the labeler config. Live: on STORY-145's PR, `gh pr checks <n>` lists all 18 checks as pass (blocked on OWNER-011/012).

### REQ-OPS-162 — `main.yml` `revert-on-red`
**Given** a squash commit `M` on `main` whose `main.yml` run concludes `failure` or `timed_out`, and no `revert/*` PR is open **When** the job `revert-on-red` runs **Then**:
- it creates branch `revert/<short M>` from main;
- it makes a revert commit with `git revert -s --no-edit M`, then amends the subject to `revert(<scope>): <description> [TAG]`. The scope comes from `M`; TAG is `M`'s `[STORY-NNN]`, `[SDLC]` or `[PHASE-N]`; the subject is ≤ 72 characters with the description truncated; Signed-off-by is present;
- for a `[STORY-NNN]` tag, a second signed-off commit with subject `revert(<scope>): reopen STORY-NNN after red main [STORY-NNN]` (≤ 72 characters) sets that story to `status: IN_PROGRESS`, appends `| <date> | revert-on-red | main.yml | reverted <short M>: run <url> red |` and a PROGRESS line `… · STORY-NNN · integrate · RED · reverted <short M> …`;
- the PR (revert profile, REQ-OPS-166) gets the same title, a generated body with marker, and squash auto-merge, so it merges after the Action approval and its required checks.

Unhappy paths:
- the run is `cancelled` → no revert, and a notice;
- a `revert/*` PR is already open → no new revert; instead a comment on that PR names `M` and the run URL;
- on each push to main while a `revert/*` PR is open and behind main, `revert-on-red` rebases the revert branch onto main, re-signs its commits (`-s`), and force-pushes **the revert branch only**. A rebase conflict → an issue labelled `revert-failed`, and the branch is left unchanged;
- `M` is itself a revert → no revert of the revert; an issue `revert-on-red: main red after revert <sha>` with label `revert-failed`;
- `[SDLC]`/`[PHASE-N]` → reverted, with no story change;
- a conflict → an issue labelled `revert-failed` with the run URL, and no PR;
- the workflow-triggering token is missing → the job fails, and an issue names the secret (proposed OWNER-013);
- failing revert-PR checks → it is not merged and stays open.

Tests: the procedure runs as a script against a temp repo with a stub `gh` that records its calls. The tests assert:
- the branch;
- both commits (subject form including `revert(<scope>): reopen STORY-NNN after red main [STORY-NNN]`, Signed-off-by, story-file diff);
- the title length;
- the recorded `gh` arguments;
- the comment path when a revert PR is open;
- the rebase path: a revert branch 1 commit behind main is rebased, with every commit still carrying Signed-off-by, and the push targets only `revert/<short M>`;
- the rebase-conflict path, which opens an issue and leaves the branch unchanged.

A static test asserts that the job runs only after the main gate fails, and that `contents: write`, `pull-requests: write` and `issues: write` are granted only to this job within `main.yml`.

### REQ-OPS-163 — guard-bash: no push to main, no self-approval, PR create/merge only inside a gate flow
Scope, normalisation, the "Blocked" format (exit 2, rule, `Safe alternative:`) and non-goals are as in REQ-OPS-001. Messages start with the gate id.

**B1b (extends B1).** Every `git push` whose destination is main or master is blocked, with or without force:
- `main`, `refs/heads/main`, `HEAD:main`, `story/x:main`, `-u origin main`, `+main`, `:main`, `@:main`;
- no refspec or `HEAD` while main is checked out;
- `--all`, and `--mirror` (or a prefix of it) from any branch;
- a glob refspec that can match main (`'refs/heads/*:refs/heads/*'`, `'*:*'`, `'refs/heads/m*'`).

Message: `[G1] pushing to main is forbidden; only PR merges create commits on main`. Still allowed: `git push origin v1.2.3`, `git push origin --tags`, `git push -u origin story/STORY-145-delivery-gates`, `git push origin maint/v1.2`, `git push origin main-docs` and `git push origin :story/x`.

**B15 PR flow.**
- These are always blocked, even with any flag set: `[G5] the PR author never approves`.
  - `gh pr review <n> --approve` / `-a`;
  - `gh api …/pulls/<n>/reviews` with a field `event=APPROVE` (`-f`, `-F`, `--field`, `--raw-field`);
  - `gh api graphql` with `addPullRequestReview` or `submitPullRequestReview` and `APPROVE`.
- `gh pr merge --admin` is always blocked.
- `gh pr merge`, `gh api …/pulls/<n>/merge`, and `gh api graphql` with `mergePullRequest` or `enablePullRequestAutoMerge` are blocked `[G6]` unless a gate flow is active.
- `gh pr create` and `gh api -X POST …/pulls` are blocked `[G3]` unless a gate flow is active.
- A gate flow is active when `.claude/state.json` has `pr_flow_active: true` and `pr_flow_branch` matches a profile, with:
  - `story/` and `hotfix/`: state `story` equals the branch's story id;
  - `release/v*`: `release_active: true`;
  - `dependabot/*`: the PR is merged only, never created (the actor is `/next`, REQ-OPS-166).
- `gh alias set` whose expansion contains a blocked form is blocked.
- Always allowed: `gh pr view|checks|list|diff|status|edit`, `gh pr review --comment|--request-changes`, `gh run view|list|rerun`.

`scripts/sdlc/state.sh` gains `pr-flow-on [<branch>]`, which sets `pr_flow_active: true` and `pr_flow_branch` (default: the current branch), and `pr-flow-off`, which sets false/null.
Tests tagged `REQ-OPS-163` in `test-guard-bash.sh` cover:
- each blocked form, with the flag off and with it on but the story or branch mismatched;
- create and merge allowed for a matching story flow, for `release/v1.2.3` with `release_active: true`, and merge for `dependabot/npm_and_yarn/x`;
- `gh pr review 12 --approve` blocked with every flag on;
- every allowed form.

Unhappy path: malformed hook JSON never blocks (REQ-OPS-001). The flag is not a security boundary, because any agent can run `pr-flow-on`. The boundary is GitHub (required checks and review, STORY-016) plus the approvers (OWNER-011).

### REQ-OPS-164 — `/story` stage 8 and `/release` call `delivery-gate.py` and refuse on red; G7, M17 and M18 as checks
**`/story` stage 8** (`.claude/skills/story/SKILL.md` and `integrate.md`) runs in this order:
1. `git rebase origin/main`. If HEAD's sha changed and non-bookkeeping commits were rewritten, stages 6–7 re-run as loop k+1 (it counts toward M16; above 3 → escalate);
2. `make gate STAGE=pr-open TITLE="<title>"`. On FAIL, `gh pr create` is **not** run, and the failing rows go to the implementer as a fix loop;
3. `state.sh pr-flow-on`, with a trap that runs `pr-flow-off` on every exit path;
4. push, then `gh pr create --title <title> --body-file <generated> --label <labels>`. On re-entry with an open PR for the branch, it runs `gh pr edit --body-file <generated>` instead;
5. bookkeeping commits, all before approval:
   - story `status: DONE` with the Status log row `| <date> | integrate | orchestrator | PR #<n>; verify PASS loop <k>; review APPROVE |`;
   - the PROGRESS line, the ledger row, `make trace` and `make metrics`;
   - for every DONE story whose merge sha is not yet in PROGRESS, a line `… · STORY-NNN · merged · INFO · <sha>`;
   - then `make gate STAGE=pr-body`, `gh pr edit --body-file` and a push;
6. `gh pr checks --watch`;
7. `make gate STAGE=approve PR=<n>`. On FAIL → fix loop;
8. `make gate STAGE=merge PR=<n>`. If it FAILs **only** on G5 after `gh pr checks --watch` has completed:
   - the orchestrator adds a note to `docs/ESCALATIONS.md`;
   - a branch commit sets the story to `status: BLOCKED`, with a Status log row naming OWNER-011/012;
   - the `make gate STAGE=merge` output is posted as a PR comment;
   - `pr-flow-off` runs, and the orchestrator moves on.

   Until OWNER-011 is resolved, the owner may merge such a PR personally after reading that comment. The next stage-8 bookkeeping commit then records `… · STORY-NNN · merged · INFO · owner merge (G5 manual, OWNER-011)` (decision E1). On any other FAIL → fix loop. On FAIL there is no merge;
9. `gh pr merge --squash --delete-branch --subject <title> --body <trailers>`, where the trailers are `Story:`, `Reqs:`, `Verify: <path>@<sha>`, `Review: <path>@<sha>` and `Co-Authored-By:`;
10. `pr-flow-off`, `git switch main && git pull --ff-only`, and the state is cleared.

**Fix loop after step 5.** When stage 8 sends a fix loop after step 5:
- a bookkeeping commit sets `status: IN_PROGRESS`, with the row `| <date> | integrate | orchestrator | fix loop k: <failing ids> |`;
- on re-entry with an open PR for the branch, pr-open runs without create, and steps 4–5 use `gh pr edit --body-file`.

Fixture: a story with an open PR at `status: DONE` and a red M3 → after the fix loop, the story file shows `IN_PROGRESS` plus that row. On re-entry, the recorded `gh` calls contain `pr edit --body-file` and no `pr create`.

The local-only `git merge --squash` path is removed: with no remote, the story ends BLOCKED and an escalation is written. The exit gate adds two conditions: `git show origin/main:<story file>` has `status: DONE`, and `state.sh get pr_flow_active` prints `false`.

**`/release`** (`.claude/skills/release/SKILL.md`, `release.yml`):
1. `make gate STAGE=release` (G7). On FAIL, the skill stops before `release-on`.
2. The changelog and version bump go to branch `release/vX.Y.Z` as commit `chore(release): vX.Y.Z [SDLC]`. That PR passes G3–G6 under the release profile. `release_active` is on, and `pr-flow-on release/vX.Y.Z` is set.
3. After the `release/vX.Y.Z` PR merges, `git switch main && git pull --ff-only` runs and `make gate STAGE=release` is **run again**. Only on PASS is the tag created: HEAD equals origin/main, the tree is clean, `git tag -a vX.Y.Z -F <changelog section file>`, and the tag is pushed alone with `git push origin vX.Y.Z`. The skill never runs `git push origin main`.
4. After staging: `make gate STAGE=release-staging` (M17). On FAIL, staging is rolled back and the skill stops.
5. After the production deploy and its 30-minute window: `make gate STAGE=post-deploy` (M18). On FAIL, `make rollback` runs without asking, an incident file is written, and `/bug` is opened. In `release.yml`, `deploy-prod` runs the same check and `make rollback` on failure.
6. Post-deploy bookkeeping goes through PR `release/vX.Y.Z-deployed`: the `docs/DEPLOY.md` `prod-deployed vX.Y.Z …` marker, PROGRESS, the ledger, and commit `docs(release): vX.Y.Z notes [SDLC]`. `release-off` runs only after that PR has merged, and also on every failure path. The S16 first-deploy check reads the marker on origin/main; the checkpoint itself is unchanged.
7. The `release-please` job is removed from `release.yml`'s route, because PRs it opens with `GITHUB_TOKEN` run no `pull_request` checks.
8. The hotfix mode is in REQ-OPS-167.

**Checks.** **Given** recorded GitHub JSON, the evidence files and a fixed clock **Then**:
- `release` (G7) PASSes only if:
  - the last 3 completed `main.yml` runs on main are `success`;
  - the latest `nightly.yml` run on main is `success` and ≤ 24 h old;
  - no `.review.md` on main has an open BLOCKER/MAJOR row (Resolution not `fixed <sha>`);
  - `docs/SDLC_SELFTEST.md` shows `Verdict: **GREEN**`, generated ≤ 7 days ago;
  - row `M17 restore test`: the restore-test evidence (yaml path under M17; produced by STORY-124) is ≤ 30 days old. It is a precondition reported in the `release` check;
  - HEAD equals origin/main and the tree is clean;
- `release-staging` (M17) PASSes only if:
  - the smoke exit is 0;
  - k6 p95 reads are < 200 ms and writes < 400 ms;
  - Lighthouse performance is ≥ 90, accessibility = 100 and best practices ≥ 95;
  - an SBOM exists per built module;
  - the signature check passed;
  - the rollback evidence is ≤ 30 days old;
- `post-deploy` (M18) PASSes only if the window is ≥ 30 min, the error rate is < 1 % and the parse-failure rate is < 5 %. The FAIL line contains `make rollback`.

Unhappy fixtures (each FAIL names its id):
- 2 of 3 main runs green;
- nightly 25 h old;
- selftest `AMBER`;
- selftest 8 days old;
- no restore evidence → `M17 restore test: no evidence (STORY-124)`;
- restore evidence 31 days old;
- HEAD 1 behind;
- k6 reads 201 ms;
- Lighthouse accessibility 99;
- error rate 1.0 %;
- window 29 min;
- no metrics → FAIL (rollback).

Passing fixture: parse failure 4.9 % passes.

A static test over the skill bash blocks checks:
- `make gate STAGE=pr-open` comes before `gh pr create`;
- `make gate STAGE=merge` comes before `gh pr merge`;
- `pr-flow-off` appears in a trap;
- there is no `git merge --squash` into main and no `git push origin main`;
- `/release` runs `make gate STAGE=release` before `release-on`, and again between the release PR merge and `git tag`;
- the tag uses `git tag -a … -F`;
- `release-off` comes after the `-deployed` PR merge;
- `release.yml` has no `release-please` job on the release route.

### REQ-OPS-165 — `make gate`, `docs/DELIVERY_GATES.md` linked from CLAUDE.md, five new self-test rows PROVEN
**`make gate STAGE=<x> [PR=<n>] [TITLE=<t>] [STORY=<id>] [HOTFIX_OF=<tag>] [HOTFIX=<squash sha>]`** runs `delivery-gate.py <x>` and passes its exit code through. The variables map to options:
- `PR` → `--pr <n>`;
- `TITLE` → `--title "<t>"`;
- `STORY` → `--story <id>`;
- `HOTFIX_OF` → `--hotfix-of <tag>`;
- `HOTFIX` → `--hotfix <squash sha>`.

There is one test per variable, asserting the option reaches `delivery-gate.py` with quoting preserved (a title with spaces and `[STORY-145]`). The valid stages are `pr-open`, `approve`, `merge`, `verify`, `review`, `size`, `commits`, `docs`, `ci`, `pr-body`, `pr-body-lint`, `suppressions`, `release`, `release-staging` and `post-deploy`. A missing or unknown `STAGE` exits 2 and lists them. `make help` lists `gate`.
**`docs/DELIVERY_GATES.md`** is the human version of addendum §1–§3. It contains:
- the M1–M18 table;
- G1–G8 sections;
- the profiles;
- the re-run command per metric;
- the owner dependencies;
- the **full enforcement map**. Every §3 row is present, including:
  - "no unlogged suppression | local hook: scan-secrets (REQ-OPS-160) | pre-commit grep | gate/M8";
  - "no secret anywhere | scan-secrets | gitleaks | gitleaks + **push protection** (owner / STORY-016)".

A test asserts that every threshold in the document's table equals the yaml value, and that every metric id, check name and profile in the yaml appears in it. `CLAUDE.md` links the document under **Workflow** and stays < 200 lines.
**Self-test.** `make selftest` gains these rows, each in an isolated temp repo/state and reverted:

| row | plant | must observe |
|---|---|---|
| D1 | PR opened without a verify report | `delivery-gate.py pr-open` exit 1 naming `M10`, no body file |
| D2 | self-approval | guard-bash ← `gh pr review 1 --approve` exit 2 `[G5]` |
| D3 | weakened test | the `gate/M5` command from the yaml exits 1 `TEST_INTEGRITY: VIOLATION` |
| D4 | hand-edited PR body | `pr-body-lint` exit 1 `digest mismatch` |
| D5 | push to main | `git push origin main` (fast-forward) rejected by the installed pre-push hook with the bare remote unchanged, **and** guard-bash exit 2 `[G1]` |

`docs/SDLC_SELFTEST.md` then shows every pre-existing row plus D1–D5 PROVEN, 0 PENDING, 0 FAILED, Verdict GREEN.
Unhappy path: a planted violation that is not blocked gives a `FAILED` row, and `make selftest` exits 1. This is proven with a stub guard that allows the violation.

### REQ-OPS-166 — Branch-class gate profiles
**Given** yaml `profiles` (branch glob → applicable metrics, M13 form, not-applicable reasons) **When** any composite, sub-command, guard rule or `pr.yml` check runs for branch `B` (or the PR's head ref) **Then** the first profile whose glob matches `B` decides:

| profile | branch | applies | M13 form | not applicable (`SKIP not applicable: …` / CI success) |
|---|---|---|---|---|
| story | `story/STORY-NNN-<slug>` | M1–M16, pr-body-lint, ai-review | `type(scope): … [STORY-NNN]`, Signed-off-by, `Story:` | — |
| revert | `revert/<7-40 hex>` | M1–M9 (M5 and M7 vs `M^`), M13, M15, `revert content`, pr-body-lint, ai-review | `revert(<scope>): … [STORY-NNN\|SDLC\|PHASE-N]`, Signed-off-by from `git revert -s` | M10, M11, M16: `revert of <sha>`; M12, M14: `revert of <sha>` |
| release | `release/v<X.Y.Z>[-deployed]` | M1–M9, M13, M15, `G7 release content` (`release_paths` only), pr-body-lint, ai-review | tag `[SDLC]` or `[PHASE-N]`, Signed-off-by | M10, M11, M12, M14, M16: `release bookkeeping` |
| hotfix | `hotfix/STORY-NNN-<slug>` | as story, plus M5 `--require-increase` (REQ-OPS-167) | as story | — |
| dependabot | `dependabot/**` (merged by `/next`) | M1–M9, M15, ai-review (Dependabot secret) | the merge step rewrites the squash title to `build(deps): <subject> [SDLC]` (≤ 72, subject truncated) | M10, M11, M12, M13, M14, M16, pr-body-lint: `dependency bot PR` |

Routes:
- pr-open serves story, hotfix and release;
- `revert-on-red` opens revert PRs;
- Dependabot opens its own;
- the Action approval job evaluates `approve` for the PR's profile;
- /release bookkeeping reaches main only through `release/v*` PRs (REQ-OPS-164).

**Revert content.** On the revert profile, M5 and M7 compare HEAD with `M^` (the parent of the reverted squash `M`), not with origin/main, which still contains `M`. The row `revert content` passes only when `git diff --name-only M^ HEAD` lists bookkeeping paths only. Otherwise FAIL `revert profile: diff is not the inverse of <short M>`.

**Release content.** On the release profile, the row `G7 release content` passes only when every path in the branch diff is in yaml `release_paths`. Otherwise FAIL `release branch changes non-release path <p>`.

**Dependabot actor and steps.** `/next` handles open Dependabot PRs before picking a story (this edits `.claude/skills/next/SKILL.md` in 145.9). For each open `dependabot/**` PR whose checks have completed, it runs:
1. `state.sh pr-flow-on <head branch>`;
2. `make gate STAGE=merge PR=<n>`;
3. on PASS, `gh pr merge <n> --squash --delete-branch --subject "build(deps): <subject> [SDLC]"`, with the title truncated to 72;
4. `pr-flow-off`, in a trap.

On FAIL it leaves the PR open, comments the gate output on it, and continues with stories. Approval comes from a listed approver: `github-actions[bot]`, or `mohamedshehataa`, who is not the author. The ai-review on Dependabot runs needs `ANTHROPIC_API_KEY` as a **Dependabot secret** (OWNER-012, REQ-OPS-161). The M14 exemption is deviation 20.

**Branch matching.** A `hotfix/*` branch that does not match `hotfix/STORY-NNN-<slug>` exits **1** with the FAIL row `G8 branch must be hotfix/STORY-NNN-<slug>` (REQ-OPS-167). Exit 2 `no gate profile` is only for branches matching no profile glob at all.

Fixtures (one passing approve+merge fixture per profile) plus:
- a revert PR whose commit subject is `Revert "x"` → FAIL `M13`;
- a revert commit without Signed-off-by → FAIL `M13`;
- a release PR commit tagged `[STORY-145]` → FAIL `M13 release profile needs [SDLC] or [PHASE-N]`;
- a dependabot merge prints the squash title `build(deps): bump x from 1 to 2 [SDLC]`;
- a 90-character dependabot subject → the title is truncated to 72;
- the release profile shows M12 as `SKIP not applicable: release bookkeeping`;
- the revert of a squash that added 2 test files and 1 endpoint → M5 and M7 PASS against `M^`, and `revert content` PASSes;
- the same revert plus one extra code line → FAIL `revert profile: diff is not the inverse of <short M>`;
- `release/v1.2.3` touching `backend/src/…` → FAIL `release branch changes non-release path backend/src/…`;
- `release/v1.2.3` touching only `CHANGELOG.md` and `docs/PROGRESS.md` → PASS;
- `/next` with one open dependabot PR (stub `gh`) → the recorded calls are `pr-flow-on`, `make gate STAGE=merge`, `gh pr merge … --subject "build(deps): … [SDLC]"` and `pr-flow-off`;
- `hotfix/quick-fix` → exit 1 with the G8 row;
- branch `feature/x` → exit 2 `no gate profile for branch feature/x`.

### REQ-OPS-167 — Hotfix flow (G8)
**Given** a released tag `vX.Y.Z` and a hotfix story STORY-NNN (opened by `/bug`) **When** the hotfix flow runs **Then**:
- the branch is `hotfix/STORY-NNN-<slug>`, cut from `vX.Y.Z`, and rebased on origin/main before pr-open (M13: 0 behind);
- `delivery-gate.py pr-open --hotfix-of vX.Y.Z` accepts the hotfix profile: the tag exists and is the latest `v*` tag; the story gates apply (verify by `qa-verifier`, review, M1–M8, M9 for AI paths);
- **M5** runs `test-integrity-check.sh origin/main --require-increase`. Net assertions (now − base) must be > 0, or FAIL `[M5] hotfix needs net assertion increase (n -> n)`. The same applies in pre-push and `gate/M5`;
- B15 allows create and merge for the hotfix flow (REQ-OPS-163); M13 applies as for a story;
- after the squash merge, `/release vX.Y.(Z+1) --hotfix <squash sha>`:
  - creates `maint/vX.Y` from `vX.Y.Z` if it is absent (the addendum's "release branch", named so it does not match the `release/v*` PR profile). Pre-push bases for `maint/v*` are in REQ-OPS-160;
  - runs `git cherry-pick -x -s <squash sha>` and pushes `maint/vX.Y` (not main; pre-push M1/M5 apply);
  - runs `make gate STAGE=release --hotfix`: G7 applies, with "HEAD equals origin/main" replaced by "HEAD equals origin/maint/vX.Y, whose tip's patch-id equals the squash commit's";
  - tags `vX.Y.(Z+1)` with `git tag -a -F <changelog section>` and pushes only the tag.

Unhappy fixtures:
- assertion count 120 → 120 → FAIL `[M5] hotfix needs net assertion increase (120 -> 120)`;
- 120 → 118 → FAIL;
- `--hotfix-of v9.9.9` (no tag) → FAIL `G8 tag v9.9.9 not found`;
- `--hotfix-of v1.0.0` when `v1.1.0` exists → FAIL `G8 not the latest release tag`;
- branch `hotfix/quick-fix` → exit 1, FAIL `G8 branch must be hotfix/STORY-NNN-<slug>`;
- patch version `v1.1.2` after `v1.1.0` → FAIL `G8 expected v1.1.1`;
- a cherry-pick conflict → `/release` stops BLOCKED with an escalation, and no tag is created;
- a maint tip whose patch-id differs → FAIL `G8`.

## Sanctioned test changes
These rows are read by the M5 consumers (REQ-OPS-160). Kinds are `flip` or `deletion` only. The match key is the file plus the assertion id as printed by `test-integrity-check.sh`. The test-designer lists each change of an existing assertion in its report; the orchestrator adds the row (the test-designer cannot write `docs/backlog/`) and one DECISIONS line per row, `STORY-145 sanctioned test change: <file> <assertion id>`, before the increment's verify. The allowance per (file, assertion id) equals the number of matching rows, so the two `expect 0` rows below allow a drop of exactly 2. The rows below are pre-filled from known changes.

| kind | file | assertion id | case | was → now | superseded by |
|---|---|---|---|---|---|
| flip | `scripts/quality/tests/test-pre-push-guard.sh` | `expect 0` | pre-push: a fast-forward of main is allowed | `expect 0` → `expect 1 "[G1]"` | REQ-OPS-160 (REQ-OPS-011 fast-forward clause) |
| flip | `scripts/quality/tests/test-pre-push-guard.sh` | `expect 0` | pre-push: creating main on an empty remote is allowed | `expect 0` → `expect 1 "[G1]"` | REQ-OPS-160 (REQ-OPS-011 creation clause) |
| flip | `.claude/hooks/tests/test-guard-bash.sh` | `assert_allowed` | REQ-OPS-001 allows: gh pr create --fill (everyday list) | allowed with no state → allowed only in a matching gate flow, blocked otherwise | REQ-OPS-163 |

Row 3 uses `assert_allowed` rather than the `expect_allow` named in orchestrator decision D5. `test-guard-bash.sh` asserts with `assert_allowed` (defined in `lib-test.sh` line 53), and that is the id test-integrity prints for that file. See the README "Decisions to record" note.

## UX states (UI stories only)
N/A: no UI. Developer-facing output is the metric table and the one-line `[<id>] … Re-run: …` messages.

## Contract impact
None. `contracts/openapi.yaml` is untouched. `gate/M7` only wires the existing redocly and oasdiff gates, plus the label-and-ADR rule.

## Data / migration impact
None. No schema or Flyway change. New configuration: `scripts/quality/delivery-gates.yaml`. In `quality-thresholds.yaml`, `flaky.max_quarantined` moves to M15, and the new keys `diff_vs_main.coverage_max_drop_pp: 0.5` and `diff_vs_main.mutation_max_drop_pp: 0.0` are added.

## Security / privacy / LLM-safety impact
**security-reviewer is required** at stage 3 and for **every** increment's stage 7, including every hook diff (decision D8). The story touches workflows, tokens, auto-merge, `.claude/hooks/guard-bash.sh`, `.claude/hooks/scan-secrets.sh` and the approval path.
- **CI permissions / tokens:**
  - least privilege per job, no `pull_request_target`, and `contents: write` only in `revert-on-red` within `main.yml`;
  - the Action's approval needs OWNER-011's setting;
  - `revert-on-red` needs a workflow-triggering token and "Allow auto-merge" (proposed OWNER-013);
  - no secret is echoed. Gate output holds shas, paths and counts only, never message bodies.
- **Push protection:** GitHub secret-scanning push protection is an owner/STORY-016 item (decision D9). The local leg is scan-secrets plus gitleaks.
- **guard-bash and scan-secrets (agent → git/GitHub trust boundary):**
  - new classes B1b and B15, and the suppression rule;
  - `pr_flow_active` is advisory; the real boundary is GitHub branch protection (STORY-016) with a listed non-author approver (OWNER-011);
  - **hooks unlock:** increment 145.7 edits `.claude/hooks/*`. As in the precedent (DECISIONS 2026-10-23 "[SDLC] STORY-001 loop 1 (hooks unlocked via make hooks-unlock)" and 2026-10-24 "[SDLC] STORY-001 loop 3 (hooks unlocked via make hooks-unlock)"), the orchestrator runs `make hooks-unlock` for the implementer, logs every change in DECISIONS, and runs `make hooks-lock` afterwards. This overrides `triggers.md`'s "only /retro and /sdlc-selftest" note for this story;
  - 145.7 and later are built in a worktree only, so the main checkout's hooks stay at main's version until merge (D4).
- **LLM safety (CI AI review):** the diff is untrusted data. An injected `STATUS: APPROVE` cannot approve alone, for three reasons: the diff is delimited, only the strict last line is parsed, and approval also needs every required check plus `delivery-gate.py approve`. The reviewer has comment-only tools.
- **Spend:** about 18 check runs per PR push use Actions minutes, and the AI review uses Anthropic spend. S16 item 3 applies.

## Eval impact
None. No prompt, parser or model change. `gate/M9` invokes the existing `make eval-fast` / `make eval`, and no dataset or baseline changes.

## Design notes (architect)
Filled at stage 3. Open for design:
- the fixture-yaml selection mechanism;
- the coverage and mutation baseline artifact name and its lookup;
- one job per metric vs the Checks API;
- the `revert-on-red` token and whether the procedure is a script;
- the structure of `delivery-gate.py` and its GitHub read adapter;
- the restore/rollback evidence paths;
- the a/b splits of increments estimated > 450;
- the version files listed in `release_paths` (decision E3);
- the CHANGELOG generator `/release` uses;
- where a hotfix patch release bumps versions (on `maint/vX.Y`, with or without a forward PR to main).

## Implementation increments (all on `story/STORY-145-delivery-gates`)
Each increment is its own TDD → implement → verify → review cycle:
- the test-designer covers only the increment's REQs and legs;
- the verifier and reviewers get only those REQs, the increment's diff range (relative to the previous increment's tip; for 145.1, relative to the branch cut point) and the claims;
- the reports are `STORY-145-delivery-gates.inc<k>.verify.md` / `.inc<k>.review.md`;
- `loop_count` resets per increment, with a maximum of 3 each (deviation 14).

After the last increment:
1. the branch's own commits are rewritten with `git rebase -x 'git commit --amend --no-edit -s --trailer "Story: STORY-145"' origin/main`, which adds the Signed-off-by and Story trailers that commits made before 145.7 lack;
2. one final full verify and review over the branch diff write `STORY-145-delivery-gates.verify.md` / `.review.md`;
3. stage 8 runs.

| inc | REQs (and legs) | content | est. lines | after |
|---|---|---|---|---|
| 145.1 | 150 (yaml, loader, output, duplicates, commit-msg-lint + coverage-gate legs, config/usage) | `delivery-gates.yaml`, `quality-thresholds.yaml` keys, loader | ≈ 380 | — |
| 145.2 | 151, 153, 154; 150 (commits + size legs) | `verify`, `size`, `commits` | ≈ 400 | 145.1 |
| 145.3 | 152, 155 | `review` (+ GitHub read adapter), `docs`, review template `## Verdicts`; `docs/templates/story.md` gains an optional frontmatter `tags:` and optional `## Rollback plan` / `## Sanctioned test changes` sections, and `sdlc-lint.py` accepts them (test: a story with all three passes sdlc-lint, and one without them still passes) | ≈ 420 | 145.2 |
| 145.4 | 156 | `ci` (event filter, attempts, quarantine) | ≈ 330 | 145.3 |
| 145.5 | 157, 158 | `pr-open`, body generator, `pr-body`, `pr-body-lint`, PR template | ≈ 450 | 145.4 |
| 145.6 | 159, 166 (composites, profile fixtures), 167 (`pr-open --hotfix-of`) | `approve`, `merge`, `coverage-gate.py --diff-base`, `traceability.py` story-strict, profiles | > 450 (split a/b) | 145.5 |
| 145.7 | 160, 163; 150 (pre-push leg); 166 (B15 legs); 167 (`--require-increase`) | lefthook, `pre-push-guard.sh`, M5 semantics, scan-secrets rule, guard-bash B1b/B15, `state.sh` (hooks unlock) | > 450 (split a/b) | 145.1 **and LOOP-002 resolved** |
| 145.8 | 161, 162; 150 (workflow static leg) | `pr.yml`, AI review STATUS, Action approval, labeler, `main.yml` baselines + `revert-on-red` | > 450 (split a/b) | 145.6, 145.7 |
| 145.9 | 164, 167 (/release hotfix mode); 166 (release route, release content, `/next` dependabot steps) | `/story` stage 8 incl. fix-loop re-entry, `/release`, `/next`, `release.yml`, `release`/`release-staging`/`post-deploy` | > 450 (split a/b) | 145.8 |
| 145.10 | 165 | `make gate`, `docs/DELIVERY_GATES.md` + test, CLAUDE.md link, selftest D1–D5 | ≈ 350 | 145.9 |

Increments 145.1–145.6 change no hook and no `lefthook.yml`, so they may start before LOOP-002 is resolved. 145.7 and later wait for LOOP-002 (decision D3).

**Size exception (M12).** The owner named exactly one story (addendum §0, §4), so the work stays as STORY-145 with `size: L`. The estimate is ≈ 4,300 hand-written lines including tests and fixtures, to be refined at stage 3. The story merges only through the M12 exception: label `size-exception` (applied by pr-open) plus a DECISIONS line for `size exception`, already present at HEAD. Any increment whose design estimate is > 450 is split into a and b before `/tdd`. M16 spend will very likely need a DECISIONS line for `spend` at merge.

## Owner dependencies (not papered over)
- **OWNER-011: G5 approval identity** (open). Every PR author is `mohamedshehataa`, and GitHub forbids authors approving their own PRs. The approval that `merge` requires must come from a listed non-author in `approvers` (D7):
  - (a) `github-actions[bot]`, which needs "Allow GitHub Actions to create and approve pull requests" (`can_approve_pull_request_reviews` was false on 2026-10-24) **and** OWNER-012;
  - or `mohamedshehataa` approving a PR opened by another account (options b and c).
- **OWNER-012: `ANTHROPIC_API_KEY` as an Actions secret and a Dependabot secret** (open). Without the Actions secret, `gate/M11 ai-review` fails by design on every PR. Without the Dependabot secret, it fails on Dependabot PRs.
- **Proposed OWNER-013:** a workflow-triggering token and "Allow auto-merge" for `revert-on-red`. It is not needed for STORY-145's own merge.
- **Push protection** (D9): the owner enables it, or STORY-016 does.
- **Interim route (D4 as corrected, E1):** the owner merges interim PRs personally (no author approval is needed). The route lasts until OWNER-011 is resolved; see Dependencies.

## Definition of Done for this story (addendum §5)
- All §4 deliverables exist (REQ-OPS-150..167).
- `docs/SDLC_SELFTEST.md` shows D1–D5 PROVEN.
- `docs/DELIVERY_GATES.md` is linked from `CLAUDE.md` under Workflow.
- **STORY-145 itself is merged through G1–G6, with every `gate/*` check visible and green on its PR.**

That last criterion is **blocked on OWNER-011 and OWNER-012**. Until both are settled, the orchestrator:
- finishes all increments and the final verify and review;
- runs pr-open and opens the PR;
- when `merge` FAILs only on G5, sets `status: BLOCKED` with an escalation and posts the merge output on the PR (REQ-OPS-164 step 8). The owner may then merge personally (E1). That merge is logged as `… · STORY-145 · merged · INFO · owner merge (G5 manual, OWNER-011)`, but it does **not** satisfy the final DoD criterion. That criterion needs an approval by a listed non-author.

**Demonstration, once both are settled** (evidence in the final `.review.md` and the Status log):
- `gh pr checks <n>`: all 18 checks pass;
- `gh pr view <n> --json reviews,headRefOid,body`: an `APPROVED` review on the head sha by a listed non-author, and one valid `gate:v1` marker;
- `make gate STAGE=merge PR=<n>`: PASS;
- `git log -1 --format=%B origin/main`: the trailers.

## Deviations and interpretations (DECISIONS lines in docs/backlog/README.md "Decisions to record")
1. **Strict HEAD rule.** Bookkeeping paths may change after a report, but in the story file only the `status:` line and appended Status log rows may change. A rebase makes reports stale and starts loop k+1.
2. **The marker carries a body digest.** Without it, hand edits that keep the marker cannot be detected (selftest D4).
3. **G6 bookkeeping moves before approval.** The merge sha is recorded by the next stage-8 bookkeeping commit as `… · STORY-NNN · merged · INFO · <sha>`.
4. **Sanctioned test changes** are limited to `flip` and `deletion`, keyed by file + assertion id, with rows added by the orchestrator. REQ-OPS-011's fast-forward and creation clauses are superseded by REQ-OPS-160.
5. **Gate ids** (`[G1]`/`[G3]`/`[G5]`/`[G6]`/`[G8]`) appear in messages for rules that are not metrics.
6. **Extra sub-commands:** `pr-body`, `pr-body-lint`, `suppressions`, `release`, `release-staging`, `post-deploy`; plus `--hotfix-of` and `--hotfix`.
7. **M3/M4 baselines** are published by `main.yml`. SKIP is allowed only until the first green main run after the baseline job lands.
8. **M14 ADR rule:** only its checkable part is a gate.
9. **M1 in CI** is the `make check-fast` step duration. Above 180 s, the story goes BLOCKED and the owner tunes the yaml.
10. **STORY-016** returns to DRAFT. Its REQ-OPS-060/062 now use the yaml required checks, and the Claude review moves to STORY-145 (D6).
11. **No local-only merge path.** `/release` pushes only tags, and its bookkeeping goes through `release/v*` PRs. `release-please` is removed from the release route.
12. **M18 input:** `post-deploy` decides from an input sample. Prometheus wiring is STORY-133/137.
13. **M14(b):** the PROGRESS check matches the story id, not the HEAD sha, because a commit cannot name its own sha.
14. **`loop_count` per increment.** It resets for each STORY-145 increment, with a maximum of 3 each, and M16 checks every increment report.
15. **M16 spend** is SKIP while fewer than 3 DONE stories have ledger rows.
16. **The M11 "touches a p95 budget" clause** is not gated; `performance-engineer` is triggered by its path column and the `perf` tag only.
17. **DECISIONS lines count anywhere in the file at HEAD.** Lines for the story are accepted at HEAD even when committed outside the branch diff (M5, M12, M14, M16).
18. **The hotfix maintenance branch is `maint/vX.Y`**, not `release/vX.Y`, so it does not match the release PR profile. Hotfix tags come from the maint tip rather than main's HEAD.
19. **Gate flows in guard-bash** are identified by `pr_flow_branch` plus the profile condition, not only by the current branch.
20. **Profile exemptions** (each with its reason):
    - **revert:** M10, M11, M16 (the reverted commit already passed them, and a revert has no new spec); M12, M14 (the diff is the inverse of a gated diff, checked by `revert content`).
    - **release:** M10, M11, M12, M14, M16 (bookkeeping limited to `release_paths`, checked by `G7 release content`).
    - **dependabot:** M10, M11, M12, M13, M16 and pr-body-lint (a bot PR with no story, commits or generated body). M14 too, because Dependabot cannot add DECISIONS lines; the version change is recorded by the lockfile/manifest diff and the PR title (decision E2).
21. **`ref:` entries** replace literal values for M3, M4 and M9 in `delivery-gates.yaml`. The values stay in `quality-thresholds.yaml` and `eval/thresholds.yaml`, so each is defined once.
22. **M12 extra exclusions** beyond the addendum's "generated, wrapper, vendored": lockfiles and `verification-metadata.xml` (machine-written), `docs/backlog/**` (spec and report artefacts) and the generated docs `docs/TRACEABILITY.md`, `docs/SDLC_METRICS.md` and `docs/SDLC_SELFTEST.md`.

## Dependencies
- **STORY-001** is **BLOCKED on LOOP-002**: verification loop 3/3 failed. The options are (a) one extra loop scoped to the 4 findings (recommended), (b) DONE-with-known-defects plus a new story, and (c) accept the risk.
  - Increments 145.1–145.6 change no hooks or lefthook config, so they may start before LOOP-002 resolves. This is an orchestrator exception (D3) to `/story` step 0.2, which requires `depends_on` to be DONE.
  - 145.7 and later wait for LOOP-002.
- **Branch base (D3).** `story/STORY-145-delivery-gates` is cut from the current tip of `story/STORY-002-walking-skeleton-health-version`, because main lacks the SDLC until that branch merges. Before pr-open, once that branch has merged, STORY-145 is moved with `git rebase --onto origin/main <STORY-002 tip at the cut> HEAD`. Its branch diff then holds only STORY-145 commits. Until then, M12 and M13 are measured from the cut point.
- **Interim route (D4, corrected by the orchestrator 2026-10-24).** Until STORY-145 merges, other stories reach main through a PR to main that the **owner merges personally** in the GitHub UI after reading the manual delivery-gate table in that story's `.review.md`; the owner's merge is the manual G5 approval (GitHub forbids the author's APPROVED review, and every PR is opened by the owner's account). The orchestrator never runs `gh pr merge` on the interim route. STORY-145's own BLOCKED status reaches main the same way. **The interim route ends when OWNER-011 is resolved, not when STORY-145's code reaches main (E1).** After STORY-145 merges and until OWNER-011 is resolved:
- a G5-only FAIL at stage 8 step 8 marks the story BLOCKED with an escalation;
- the owner may merge personally after reading the `make gate STAGE=merge` output posted on the PR;
- the merge is logged as `… · STORY-NNN · merged · INFO · owner merge (G5 manual, OWNER-011)`.

Once OWNER-011 is resolved, G5 follows REQ-OPS-159 only.
- No dependency on Phase 1+ work. STORY-016 (Phase 1, now DRAFT) later applies branch protection with this story's `required_checks`, `approvers` and push protection. STORY-124 (Phase 9) produces the restore evidence; until then G7 FAILs by design.
- Owner: OWNER-011, OWNER-012, proposed OWNER-013, push protection.

## Definition of Ready checklist
- [x] Goal + Part I links (addendum §0–§5; PROMPT S2, S7, S9, S12, S16)
- [x] Numbered REQs with Given/When/Then incl. unhappy paths (18 REQs, each with failing fixtures, fail-closed paths and GitHub failures)
- [x] UX states (UI stories): N/A
- [x] Contract impact (none)
- [x] Data / migration impact (none; new yaml and threshold keys)
- [x] Security / privacy / LLM-safety impact (security-reviewer on every increment)
- [x] Eval impact (none)
- [x] Size ≤ 400 changed lines **or a recorded M12 size exception**. This is an **exception**: `size: L` ≈ 4,300 lines in ten increments, with any increment > 450 split a/b. The box is ticked for the exception clause only.
- [x] Dependencies listed (STORY-001 / LOOP-002, branch base, interim route, owner items)

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-24 | 1-2 spec | product-analyst | READY: addendum specified as REQ-OPS-150..165; size L under the M12 exception in 10 increments; final DoD criterion blocked on OWNER-011/012 |
| 2026-10-24 | 2 spec fix loop 1 | product-analyst | READY: all 42 spec-review findings applied with orchestrator decisions D1–D10; new REQ-OPS-166 (branch-class profiles) and REQ-OPS-167 (hotfix G8); strict HEAD rule; required checks (14) and approvers in the yaml; STORY-016 back to DRAFT; D5 row-3 assertion id corrected to `assert_allowed` (flagged); D4 owner-approval caveat flagged |
| 2026-10-24 | 2 spec fix loop 2 (final) | product-analyst | READY: all 21 loop-2 proposals applied verbatim with decisions E1–E3: revert content vs `M^`, `release_paths` + `G7 release content`, deviations 20–22, stage-8 fix-loop re-entry, `maint/v*` pre-push base, count-based sanctioned allowance, interim route ends at OWNER-011, Dependabot via `/next` + Dependabot secret, make gate variables, per-profile PR body fixtures, restore test as M17, M15 timed_out/cancelled, AI-review severity prefix, story template sections |
