# Escalations

Items only the owner can unblock, and stories that failed the 3-loop limit. Each entry: what was tried, failing evidence, options, and the decision needed. Mark resolved entries with `[x]` and move them to Resolved.

## Open
- [ ] **OWNER-001 · Anthropic API key** — needed for real-API eval runs and the parsing engine (Phase 3). Until then evals run with the deterministic reference adapter. Action: put `ANTHROPIC_API_KEY` in `.env`.
- [ ] **OWNER-003 · WhatsApp Business** — Meta app, dedicated number, access token, app secret, test number (Phase 5).
- [ ] **OWNER-004 · Server + domain** — VPS and domain for Caddy HTTPS (Phase 10). Also `PROD_HOST` for the production guard.
- [ ] **OWNER-005 · Android signing keystore + FCM project** (Phase 4/10).
- [ ] **OWNER-006 · Member phone numbers** (E.164) for sender→member mapping (Phase 5).
- [ ] **OWNER-007 · Speech-to-text key** (OpenAI) or approval to self-host faster-whisper (Phase 5).
- [ ] **OWNER-008 · Error tracking DSN** (GlitchTip/Sentry) — optional (Phase 9).
- [ ] **OWNER-009 · Budgets** — defaults set in `.env.example`: `SDLC_AI_BUDGET_USD=300`, `INFRA_BUDGET_USD=30`. Adjust if needed.

- [ ] **OWNER-010 · Property-testing library** — PROMPT S9 names jqwik, but jqwik's maintainers now ask AI coding agents not to use it (ADR-0004 notes the per-run message). Default decision unless you object before STORY-004 starts: QuickTheories (Apache-2.0, JUnit 5). Alternatives: keep jqwik (your call as the human user of the tool), or hand-rolled generators with JUnit 5 @ParameterizedTest.

## Resolved
- [x] **OWNER-002 · GitHub remote** — Resolved 2026-10-24 on the owner's instruction: private repo https://github.com/mohamedshehataa/masroof created, `main` + story branch pushed, default branch main, Dependabot alerts on. Branch protection (required checks, linear history) is applied by STORY-016 once CI has real jobs.
- [x] **LOOP-001 · Spec-review loop limit (2) reached for STORY-002/143/141/142** — Tried: loop 1 (26 findings) → split into four stories; loop 2 (17 findings, 3 MAJOR, all with concrete proposed text). Evidence: reviewer reports in this session. Options: (a) third review, (b) apply proposals verbatim and proceed, (c) block. Decision (orchestrator, 2026-10-23): (b) — the remaining findings were wording with proposed text, not design disagreements; applied verbatim, design proceeds; the Phase 0 retro decides whether multi-story reviews get a higher limit.
- [x] **GATE-20261024100811 stop-gate RED** - `make check-fast MODULE="backend sdlc"` failed 3 consecutive times (story: STORY-144, stage: implement). Tried: the agent attempted fixes across 3 stop attempts. Evidence: `.claude/state/last-check.log`. Options: (a) fix in the next session, (b) revert the change, (c) descope. Decision needed by the orchestrator/owner. **Resolved 2026-10-24 (orchestrator):** the red state was the expected gap between the STORY-144 upgrade commit and the guard commit (guard context tests are red until commit 2); both commits are now on the story branch (46a68af, 7fba50d) and `./gradlew checkFast archTest` exits 0 with archTest tests=9/0/0/0.
- [x] **GATE-20261024101508 stop-gate RED** - `make check-fast MODULE="backend sdlc"` failed 3 consecutive times (story: STORY-144, stage: implement). Tried: the agent attempted fixes across 3 stop attempts. Evidence: `.claude/state/last-check.log`. Options: (a) fix in the next session, (b) revert the change, (c) descope. Decision needed by the orchestrator/owner. **Resolved 2026-10-24 (orchestrator):** the red state was the expected gap between the STORY-144 upgrade commit and the guard commit (guard context tests are red until commit 2); both commits are now on the story branch (46a68af, 7fba50d) and `./gradlew checkFast archTest` exits 0 with archTest tests=9/0/0/0.
