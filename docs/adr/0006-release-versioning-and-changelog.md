# ADR-0006: Release version files, changelog generator and hotfix version bump

- **Status:** proposed
- **Date:** 2026-10-24
- **Story:** STORY-145 (decision E3; REQ-OPS-164, REQ-OPS-166, REQ-OPS-167)
- **Deciders:** architect (+ devops-engineer, spec-reviewer)

## Context and problem statement
STORY-145 takes `release-please` off the release route. PRs it opens with `GITHUB_TOKEN` run no `pull_request`
checks, so they could never pass G3–G6. `/release` now carries its changelog and version bump on a `release/vX.Y.Z`
branch. A `release/v*` branch may change only `release_paths` (row `G7 release content`). Three decisions follow:
which files hold the version, what generates `CHANGELOG.md` and the tag message, and where a hotfix (G8) bumps the
patch version. G8 requires the tag to come from a `maint/vX.Y` tip whose patch-id equals the hotfix squash commit.

Today the version lives in `backend/build.gradle.kts` (`version = "0.1.0"`, read by `/health`, STORY-143) and in
`contracts/openapi.yaml` (`info.version: 0.1.0`). `web/package.json` and `android/app/build.gradle.kts` will hold it
once those modules exist. `CHANGELOG.md` does not exist yet.

## Decision drivers
- No new runtime dependency on the release path, and a deterministic output that offline tests can pin.
- `release_paths` must not become a hole: a release PR may touch a dependency or contract file only on its
  version line.
- The tagged tree must state its own version (the `/health` version equals the tag).
- G8 tip rule: `HEAD` of `maint/vX.Y` is the cherry-pick of the squash commit.

## Considered options
**Changelog generator**
1. `release-please` (kept for the changelog only): opens its own PRs with `GITHUB_TOKEN`. That is the problem
   being removed.
2. `git-cliff` (Rust binary plus `cliff.toml`): capable, but a new binary on dev boxes and runners, and a new
   template language.
3. `conventional-changelog-cli` (npm): needs Node in the SDLC module, which has none today.
4. **`scripts/sdlc/changelog.py` (stdlib):** `git log <prev tag>..<ref> --format=%H%x00%s` grouped by type
   (feat, fix, perf, revert, docs, other). The `[STORY-NNN]` tags are kept. `[SDLC]` chore/docs bookkeeping
   subjects are omitted. Output is one `## vX.Y.Z — YYYY-MM-DD` section, written to stdout and to
   `.claude/state/changelog-vX.Y.Z.md`. That file feeds both `git tag -a -F` and the release-profile PR body.

**Version files**
1. Bump each module file by hand.
2. **A root `VERSION` file (one line `X.Y.Z`) as the single source, plus the mirrored version lines that builds
   and the contract read today. Each mirrored file is listed in `release_paths` with an `only_lines` regex, so a
   release PR may change only that line.**
3. Make every build read `VERSION`. This is the long-term goal. It changes `backend/build.gradle.kts` logic
   (a dependency file, so M14 applies) and is out of STORY-145's scope.

**Hotfix bump location**
1. Bump on `maint/vX.Y` after the cherry-pick. This breaks the G8 tip patch-id rule.
2. No bump on maint; the build takes the version from the tag. The tagged tree would then report the old
   version in `/health`.
3. **Bump on `maint/vX.Y` before the cherry-pick:** `chore(release): vX.Y.(Z+1) [SDLC]` (VERSION, mirrored lines,
   CHANGELOG section), then `git cherry-pick -x -s <squash>`. The tip is the cherry-pick, and the tree states the
   new version. Afterwards the same CHANGELOG section and version lines go to main through a forward PR
   `release/vX.Y.(Z+1)` (release profile).

## Decision outcome
Chosen: changelog option 4, version option 2, hotfix option 3.

`release_paths` (in `delivery-gates.yaml`; `{version}` is the release version without the `v`; added lines must match
with `{version}`, removed lines match with any `\d+\.\d+\.\d+`):

| path | allowed change |
|---|---|
| `CHANGELOG.md`, `VERSION`, `docs/DEPLOY.md`, `docs/PROGRESS.md`, `docs/SDLC_METRICS.md` | any |
| `backend/build.gradle.kts` | `^version = "{version}"( //.*)?$` |
| `contracts/openapi.yaml` | `^  version: {version}$` |
| `web/package.json` | `^  "version": "{version}",$` |
| `web/package-lock.json` | `^\s+"version": "{version}",$` (root package entries only) |
| `android/app/build.gradle.kts` | `^\s+versionName = "{version}"$`, `^\s+versionCode = \d+$` |

A release PR whose mirrored files disagree with `VERSION` FAILs `G7 release content: <file> version <a> != VERSION <b>`.

### Consequences
- Good: no new tool. The changelog is byte-deterministic for a given history, so it can be tested in a temp repo.
  A release PR cannot smuggle a dependency or contract change through `release_paths`.
- Good: hotfix tags are self-consistent, and `maint/vX.Y` is reproducible from the tag plus two commits.
- Bad: the version is mirrored in up to five files until builds read `VERSION` (follow-up story: "builds read
  VERSION"). `contracts/openapi.yaml` changes at every release; a version-only diff is non-breaking for oasdiff.
- Bad: a hotfix needs two PRs on main afterwards (the forward `release/vX.Y.(Z+1)` and `-deployed`).

## Validation
- `test_changelog.py`: a temp repo with typed commits gives the expected section byte for byte. Bookkeeping
  `[SDLC]` subjects are omitted, and an empty range FAILs.
- Release-profile fixtures: `release/v1.2.3` touching `backend/src/...` FAILs. Touching only `CHANGELOG.md` and
  `docs/PROGRESS.md` PASSes. Changing a dependency line in `backend/build.gradle.kts` FAILs. A `version =` line
  that disagrees with `VERSION` FAILs.
- Hotfix fixtures (REQ-OPS-167): the maint tip patch-id equals the squash; `v1.1.2` after `v1.1.0` FAILs `G8 expected v1.1.1`.

## Links
- ADR-0005 (delivery-gate architecture); STORY-145 Design notes §F; `.claude/skills/release/SKILL.md`
