# SDLC workflow (always on)

- **Lifecycle per story** (PROMPT.md S2/S5, driven by `/story STORY-NNN` or `/next`): spec (`product-analyst`) ->
  spec review (`spec-reviewer`) -> design (`architect`, + `security-reviewer` when triggered) -> failing tests
  (`test-designer`, worktree) -> implement (`backend-engineer` | `ai-parsing-engineer` | `web-engineer` |
  `android-engineer` | `devops-engineer`, worktree) -> verify (`qa-verifier`, fresh context) -> review
  (`code-reviewer` + triggered specialists, parallel) -> integrate (`make check`, `make trace`, squash merge,
  story DONE, PROGRESS + SDLC_METRICS lines, state cleared).
- **The writer never grades their own work.** Verifiers and reviewers receive the story, the diff refs and a
  "Claims to verify" list; never the implementer's summary or reasoning.
- **Loop limit:** implement -> verify -> review repeats at most **3 times** per story (`loop_count` in
  `.claude/state.json`). Then: `docs/ESCALATIONS.md` entry (tried, evidence, options), story `status: BLOCKED`,
  move on to the next READY story. Never ship a red gate; never idle.
- **Evidence over claims:** "done", "passing", "fixed" always come with the command and its observed result.
  `make check-fast MODULE=<m>` green is the minimum before any report; the Stop hook enforces it.
- **Output contracts:** every roster subagent ends its final message with `STATUS: <value>` as the last line
  plus its required sections (`.claude/hooks/output-contracts.json`); the SubagentStop hook blocks otherwise.
  The orchestrator parses only that line.
- **State and records:** `bash scripts/sdlc/state.sh set|get` for phase/story/stage/loop_count; PROGRESS line
  `YYYY-MM-DD HH:MM · P<phase> · <STORY-nnn|SDLC> · <stage> · <GREEN|RED|INFO> · <note>`; story frontmatter
  `status` is the backlog truth; branches `story/STORY-NNN-<slug>`; commits `<type>(<scope>): <desc> [STORY-NNN]`
  (lefthook rejects anything else; `--no-verify` is blocked).
- **Protected by hooks:** `.env*`, keystores, applied migrations, generated dirs, `eval/baseline.json`,
  `.claude/hooks/*`, `.claude/settings.json`, `docs/PROMPT.md` (unlock only via `/retro`, `/sdlc-selftest`, `make hooks-unlock`
  ... `make hooks-lock`), secrets in content, force-push/reset on main, production hosts outside `/release`.
- **Human checkpoints (S16) only for:** owner-only secrets/numbers, the first production deploy and other
  irreversible external actions, and budget overruns. Everything else is decided by the gates, not by asking.
- Invariants (also enforced mechanically): money as `long` minor units + ISO currency, never double/float;
  Africa/Cairo business dates stored as `timestamptz`; no bodies/OCR/account numbers logged above DEBUG;
  untrusted content never becomes LLM instructions; applied migrations immutable; generated code never hand-edited.
