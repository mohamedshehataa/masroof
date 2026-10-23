# Decisions log

One line per non-obvious decision: **decision — why**. Significant ones also get an ADR in `docs/adr/`.

- 2026-10-23 — Repo lives at `C:\Personal\Masroof` on Windows 11; all scripts are Bash run under Git Bash, with `scripts/env.sh` adding tool paths — hooks on Windows execute in Git Bash per current Claude Code docs, and one shell keeps "one command surface" true across Windows, Linux CI and macOS.
- 2026-10-23 — GNU Make 3.81 (GnuWin32) is the local `make`; the Makefile avoids 3.82+ features (`.ONESHELL`, `.SHELLFLAGS`, `!=`) — it is the only maintained winget make package, and CI runners have newer make, so the lowest common denominator rules.
- 2026-10-23 — Quality scripts are Python 3.12 (stdlib + PyYAML) or Bash; Node is used only inside `web/` and for `npx @redocly/cli` — keeps the SDLC tooling dependency-light and identical in CI.
- 2026-10-23 — Blocking hooks exit 2 with the reason on stderr (not JSON `permissionDecision`) — the docs state exit 2 blocks PreToolUse/Stop/SubagentStop and shows stderr to Claude; it also works when `jq` is missing.
- 2026-10-23 — Subagent output contracts live in one file `.claude/hooks/output-contracts.json`, consumed by the SubagentStop hook, the lint and the agent definitions — one source of truth so a contract change can't drift.
- 2026-10-23 — Hook edits are blocked unless `.claude/state.json.hooks_unlocked` is true (set by `/retro`, `/sdlc-selftest` or `make hooks-unlock`) — protects the guardrails from the agents they guard while leaving a deliberate, logged escape hatch.
- 2026-10-23 — Story IDs `STORY-NNN` (global, 3 digits), epics `EPIC-NN`, statuses DRAFT/READY/IN_PROGRESS/BLOCKED/DONE/DESCOPED in YAML frontmatter — machine-readable for `/sdlc-status`, traceability and the SessionStart hook.
- 2026-10-23 — Bootstrap commits use the pseudo story tag `[PHASE-0]`; every later commit must carry `[STORY-nnn]` (or `[SDLC]` for SDLC-only maintenance) — enforced by `scripts/quality/commit-msg-lint.sh` via lefthook.
- 2026-10-23 — Unofficial WhatsApp Web automation libraries (Baileys, whatsapp-web.js, etc.) are banned — they violate WhatsApp's terms and risk the number being banned; only the official Cloud API is used (PROMPT §3.3).
- 2026-10-23 — `gitleaks git --pre-commit --staged` replaces the deprecated `gitleaks protect` in lefthook — gitleaks 8.30 is what winget installs.
- 2026-10-23 — Subagents get `Agent` excluded via `disallowedTools` as well as omitted from `tools` — current docs say nesting is allowed by default up to 3 levels, so omission alone is not enough.
- 2026-10-23 — Worktree subagents (`isolation: worktree`) branch from the default branch and leave their branch on disk when they change files; the orchestrator merges `worktree-*` branches after review — documented behaviour; no auto-merge back exists.
- 2026-10-23 — The eval harness ships a deterministic Python *reference parser* adapter so eval gates are provable in Phase 0 without an API key — the SDLC self-test must break a real gate, and the real LLM parser does not exist yet.
- 2026-10-23 — `retro-facilitator` gets `Bash` (the S4 roster listed only Read/Grep/Glob/Write/Edit) — it must run `make hooks-unlock`/`hooks-lock`, `make metrics` and the hook tests to apply and verify its own changes.
- 2026-10-23 — Reviewers and the verifier have no Write/Edit tools, so `qa-verifier` saves its report through a Bash heredoc to `<story>.verify.md` only, `security-reviewer` emits the STRIDE delta under `THREAT_MODEL_UPDATED:` for the orchestrator/architect to apply, and in `/bug` the verifier specifies the regression test precisely while `test-designer` writes it — keeps "the judge never fixes" mechanically true.
- 2026-10-23 — `jq.exe` on Windows emits CRLF; every script that consumes jq output as text goes through a `jq()` wrapper that strips CR — silent `\r` suffixes broke string comparisons in hooks.
- 2026-10-23 — Paths are canonicalised with `cygpath -m` before prefix comparison in hooks — `/tmp` is a mount alias of the Windows temp dir and tool inputs arrive as `C:\...`, so naive string comparison failed.
- 2026-10-23 — Each product module exposes exactly one fast gate entrypoint (`./gradlew checkFast`, `npm run check-fast`); `scripts/make/check-fast.sh` only dispatches — module toolchains can change without touching the SDLC.
