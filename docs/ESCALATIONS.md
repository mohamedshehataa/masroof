# Escalations

Items only the owner can unblock, and stories that failed the 3-loop limit. Each entry: what was tried, failing evidence, options, and the decision needed. Mark resolved entries with `[x]` and move them to Resolved.

## Open
- [ ] **OWNER-001 · Anthropic API key** — needed for real-API eval runs and the parsing engine (Phase 3). Until then evals run with the deterministic reference adapter. Action: put `ANTHROPIC_API_KEY` in `.env`.
- [ ] **OWNER-002 · GitHub remote** — `gh` is authenticated as `mohamedshehataa`. Phase 0 will create a **private** repo `masroof` and push `main` unless you object; branch protection + Dependabot + secret scanning + CodeQL will be enabled. Nothing is pushed until the SDLC self-test is green.
- [ ] **OWNER-003 · WhatsApp Business** — Meta app, dedicated number, access token, app secret, test number (Phase 5).
- [ ] **OWNER-004 · Server + domain** — VPS and domain for Caddy HTTPS (Phase 10). Also `PROD_HOST` for the production guard.
- [ ] **OWNER-005 · Android signing keystore + FCM project** (Phase 4/10).
- [ ] **OWNER-006 · Member phone numbers** (E.164) for sender→member mapping (Phase 5).
- [ ] **OWNER-007 · Speech-to-text key** (OpenAI) or approval to self-host faster-whisper (Phase 5).
- [ ] **OWNER-008 · Error tracking DSN** (GlitchTip/Sentry) — optional (Phase 9).
- [ ] **OWNER-009 · Budgets** — defaults set in `.env.example`: `SDLC_AI_BUDGET_USD=300`, `INFRA_BUDGET_USD=30`. Adjust if needed.

## Resolved
