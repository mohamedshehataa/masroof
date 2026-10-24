# ADR-0005: Delivery-gate architecture (one threshold file, one gate tool, one check per metric, branch profiles)

- **Status:** proposed
- **Date:** 2026-10-24
- **Story:** STORY-145
- **Deciders:** architect (+ security-reviewer, spec-reviewer)

## Context and problem statement
`docs/PROMPT_DELIVERY_GATES.md` (the owner's binding addendum) turns every movement of code (commit, push, PR,
approval, merge, tag, deploy) into a gate. Each gate has a numeric threshold and a machine check. Hooks, `make`
and CI must read the same numbers. Before STORY-145, thresholds were spread out: `MAX_LEN=72` in
`commit-msg-lint.sh`, `flaky.max_quarantined` in `quality-thresholds.yaml`, an aggregate `PR gate` job in
`pr.yml`, and prose in `integrate.md`. Branch classes (story, revert, release, hotfix, dependabot) need different
metric sets. Without a single design they would drift into ad-hoc `if`s spread across scripts and workflows.

The question: how do we structure the thresholds, the checking code and the CI surface so that the three
consumers cannot disagree, every metric can be tested offline, and the result is visible per metric on GitHub?

## Decision drivers
- One definition per threshold (addendum §0, REQ-OPS-150 duplicate rule).
- Offline, deterministic tests: no network, Docker or token, and a fixed clock (addendum §4).
- One check run per metric on the PR head, never an aggregate (addendum G2).
- Fail closed: a missing or broken config, or an unreachable GitHub, never passes.
- Stdlib + PyYAML only (the existing `scripts/quality` constraint), Windows Git Bash and ubuntu runners.
- Each increment stays at or below 450 hand-written lines, so the code must be modular.
- GitHub semantics: a required check that concludes `skipped` counts as passing.

## Considered options
1. **Thresholds**
   a. Keep the literals where they are and add a consistency test across files.
   b. **One YAML file (`scripts/quality/delivery-gates.yaml`) for all delivery thresholds, with `ref:` pointers to
      values already owned by `quality-thresholds.yaml` and `eval/thresholds.yaml`.**
   c. Move every threshold, coverage and eval included, into one new file.
2. **Checking code**
   a. One script per metric (`verify-gate.py`, `size-gate.py`, …).
   b. **One CLI `scripts/quality/delivery-gate.py` with sub-commands, backed by the package `scripts/quality/gates/`
      (core, adapters, one module per metric, composite stages). The pure `judge()` functions are separated from
      the I/O in `collect()`.**
   c. A third-party policy engine (OPA/conftest, danger.js, a GitHub App such as Mergify).
3. **CI surface**
   a. One shared job that posts per-metric check runs through the Checks API (`checks: write`).
   b. **One workflow job per metric, whose job name is the check name (`gate/Mn <name>`).**
4. **Branch classes**
   a. Hard-coded `if branch.startswith(...)` in each consumer.
   b. **Declarative `profiles` in the YAML: a glob that defines the class, a pattern that checks the well-formed
      name, `not_applicable` metrics with a reason, the M13 form and extra rows. The metric set is derived as all
      metrics minus `not_applicable`.**
5. **GitHub access**
   a. `urllib` against the REST API with a token.
   b. **A `Transport` interface (`gh api` subprocess in production; `RecordedTransport` of exact REST paths →
      JSON in tests) under a typed, read-only `GitHub` client.**

## Decision outcome
Chosen: **1b, 2b, 3b, 4b, 5b.**
- 1b removes duplicates without moving the coverage and eval numbers away from their existing owners and tests.
  1a still allows drift. 1c would churn `coverage-gate.py` and the eval harness for no gain.
- 2b gives one exit-code and output contract, one config loader and one Git/GitHub adapter that every metric shares.
  Composites (`pr-open`, `approve`, `merge`) reuse the metric modules. 2c brings a new runtime and a second policy
  language, and still needs our own GitHub and report parsing.
- 3b needs no `checks: write`. The check name is the job name, which branch protection (STORY-016) uses directly.
  Per-job re-run attempts are what `flaky-guard` (M15) reads. 3a needs a write token in a job that runs PR code.
- 4b keeps exemptions and their reasons as data, so exit codes, CI `not applicable` messages and documentation
  all come from one table.
- 5b keeps tests offline: an unrecorded call is a test failure. `gh` already handles authentication and pagination
  on both the dev box and the runners. The gate tool **only reads** GitHub. Every write (create PR, approve,
  merge, comment) happens in skills or workflows, where guard-bash and job permissions govern it.

Supporting rules (part of this decision):
- Consumers are pointed at a fixture copy by option `--gates-file` or env `MASROOF_DELIVERY_GATES`. A path that
  is set but missing is exit 2, never a fallback. `ref:` paths resolve against the tooling checkout (the
  directory tree that holds `delivery-gate.py`), not against the repository being gated.
- Shell consumers read values only through `delivery-gate.py config-get <key>`. `commit-msg-lint.sh` becomes a
  wrapper around the Python commit-convention module, so one implementation serves G1 and M13.
- CI gate jobs never `if:`-skip on `pull_request` runs. A metric that does not apply concludes `success` with
  `not applicable: <reason>`. Jobs that do skip on push-event runs carry a `push ` name prefix, so a skipped
  push-event job can never stand in for a required `gate/*` check.
- The PR-body marker `gate:v1` carries the HEAD sha and a sha256 of the LF-normalised body. It detects hand
  edits, not a deliberate forger (it is not a MAC; see THREAT_MODEL B10).

### Consequences
- Good: thresholds change in one place, and the agreement test proves every consumer follows them. Every metric
  has a pure unit-testable core. Profiles make revert, release and Dependabot routes explicit and reviewable.
- Good: new metrics plug in as one module plus a YAML entry plus one workflow job.
- Bad: about 18 jobs per PR push. That costs Actions minutes, and each job repeats setup (mitigated with YAML
  anchors in `pr.yml`). `ai-review` spends Anthropic budget on every synchronize (S16 item 3).
- Bad: a second PR workflow (`pr-meta.yml`) is needed for `edited`/`labeled`/`unlabeled`. Re-running the full set
  on body edits would cost every gate again, and skipping some would create "skipped = success" checks.
- Bad: `pull_request` workflows run the PR's own workflow file. A PR that edits `pr.yml` can fake its checks or
  the Action approval. The design does not close this hole. It is mitigated by the security-reviewer triggers on
  `.github/workflows/**` and the gate tooling, by the AI review, by code-owner review in STORY-016, and by the
  follow-up of moving the approval to a `workflow_run` workflow that runs from main (THREAT_MODEL B10).
- Bad: shell consumers pay a Python start-up (~0.2–0.4 s on Windows) per call (commit-msg hook, pre-push).

## Validation
- REQ-OPS-150 agreement test: a fixture YAML with M13 50, M1 1 s, M15 0 and M12 10 changes the verdicts of
  `commit-msg-lint.sh`, `delivery-gate.py commits`/`size`, `pre-push-guard.sh` and `coverage-gate.py`.
- Duplicate test: M3/M4/M9 hold only resolvable `ref:` items; no `flaky.max_quarantined`; no `MAX_LEN=72`.
- Layer test `test_delivery_gate_layers.py`: no metric imports another metric; `subprocess` is used only in
  `gates/adapters/`; core modules import nothing from adapters, metrics or stages.
- Workflow static tests: the job names equal the YAML check names; no `checks: write`; no
  `pull_request_target`; no skippable gate job on `pull_request`.
- Live: on STORY-145's PR, `gh pr checks` lists 18 checks (blocked on OWNER-011/012).

## Links
- Story: `docs/backlog/EPIC-00-sdlc-bootstrap/STORY-145-delivery-gates.md` (Design notes)
- Addendum: `docs/PROMPT_DELIVERY_GATES.md` §0–§5
- Related: ADR-0006 (release versioning and changelog); `docs/security/THREAT_MODEL.md` B10
