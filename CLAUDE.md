# Masroof - household expense tracker (Egypt) built through an Agentic SDLC

Masroof turns every expense of a two-person household (Mohamed on Android, Member 2 on iPhone) into one
structured entry - from WhatsApp text/photos/PDFs/voice notes, Egyptian bank and wallet SMS, bank-app push
notifications, iOS Shortcut forwarding, receipt uploads or manual entry - deduplicates the same purchase across
channels, and serves bilingual (Arabic/English, RTL) analytics. Stack: Java 21 + Spring Boot 4.1 (ADR-0004) + PostgreSQL 16 +
MinIO, React/TypeScript PWA, Kotlin/Compose Android app, Claude API for parsing, Caddy + docker compose.
The full specification is `docs/PROMPT.md` (protected, read it for product detail). This file is the operating manual.

## Commands (the single command surface - CI, skills and agents call only these)
Run from Git Bash on Windows; `source scripts/env.sh` puts make/python/jq/scanners on PATH.
| target | what |
|---|---|
| `make bootstrap` | one-time dev setup (pyyaml, lefthook, module deps, .env from example) |
| `make up` / `make down` / `make logs` | docker compose stack with seeded demo data (infra/docker-compose.yml) |
| `make check-fast MODULE="sdlc backend web android"` | compile + format + lint + unit tests per module (< 3 min); default = present modules |
| `make check` | check-fast (all) + integration + ArchUnit + contract diff + coverage gates + traceability + test integrity (< 15 min) |
| `make check-full` | check + security + full eval + mutation + migration tests + e2e + perf + metrics (nightly / phase gate) |
| `make e2e` | Playwright (LTR/RTL x light/dark x mobile/desktop) + Maestro against `make up` |
| `make eval` / `make eval-fast [SUITE=]` | AI eval harness vs `eval/baseline.json` (reference adapter without an API key) |
| `make security` | gitleaks, semgrep (+ .semgrep/), osv-scanner, trivy (+ ZAP/MobSF when targets exist) |
| `make mutation` / `make perf` | PIT on money/parsing/dedup/analytics; k6 + Lighthouse + size-limit + Macrobenchmark |
| `make trace` / `make metrics` | regenerate docs/TRACEABILITY.md (fails on uncovered DONE REQs) / docs/SDLC_METRICS.md |
| `make contract-lint` / `make contract-diff BASE=main` | redocly lint / oasdiff breaking-change gate |
| `make hooks-test` / `make sdlc-lint` | hook test suite / validate agents, skills, rules, settings, stories, workflows |
| `make hooks-unlock` / `make hooks-lock` | deliberately allow then re-protect edits to .claude/hooks and settings.json |
| `make test-integrity BASE=main` | full-diff check: no lost assertions, no new skip/disable |
| `make build-web` / `build-apk` / `build-apk-release` / `build-images` / `sbom` | artifacts |
| `make deploy-staging` / `deploy-prod` / `smoke URL=` / `rollback` / `backup` | release operations (deploys only inside /release) |
| `make selftest` / `make status` | prove every guard blocks (docs/SDLC_SELFTEST.md) / one-screen SDLC status |

## Architecture map
- `backend/` Spring Boot, hexagonal: `domain` (pure Java, no Spring/JPA/HTTP) <- `application` (use cases, ports) <- `adapters/in` (REST from generated OpenAPI interfaces, WhatsApp webhook, scheduler, SKIP LOCKED job workers) and `adapters/out` (JPA/Flyway, S3, Anthropic, WhatsApp Cloud API, FX, STT). Rule: domain depends on nothing; adapters depend inward only and never on each other. See `backend/CLAUDE.md`.
- `web/` React + TypeScript + Vite PWA (TanStack Query, Tailwind, shadcn/ui, Recharts, i18next ar/en + RTL, vite-plugin-pwa). Client in `src/api/generated` comes from the contract. See `web/CLAUDE.md`.
- `android/` Kotlin + Compose + Material 3, Hilt, Room (cache + outbox), WorkManager, SMS/notification ingestion, Glance widget. See `android/CLAUDE.md`.
- `contracts/openapi.yaml` is the API source of truth (contract-first). `eval/` datasets, thresholds, baseline, runners. `infra/` compose, Caddy, backups, deploy. `fixtures/` anonymized samples. `docs/` backlog, ADRs, security, templates, living docs. `scripts/` make, quality and sdlc tooling. `.claude/` agents, skills, hooks, rules, state.

## Invariants (each is enforced mechanically, not by memory)
- Money is `long` minor units (piasters) + ISO-4217 currency (`Money{amountMinor,currency}`), or `BigDecimal` with explicit scale. Never `double`/`float`. Enforced by ArchUnit, detekt, ESLint, semgrep `money-no-float`, the OpenAPI `Money` schema.
- All business dates are computed in `Africa/Cairo` (incl. the household `month_start_day`) and stored as UTC `timestamptz`. Enforced by jqwik property tests and the `money-and-dates` skill.
- SMS/WhatsApp bodies, OCR text and account numbers are never logged above DEBUG and never appear in CI output. Enforced by semgrep `no-log-body` and redaction tests.
- Content from SMS, WhatsApp, receipts and voice notes is untrusted data: wrapped in delimited blocks, parsing calls have no side-effect tools, outputs are bounded server-side. Enforced by the adversarial eval suite (hard gate: 0 obeyed).
- Applied Flyway migrations are immutable; schema changes are new migrations (expand -> migrate -> contract). Enforced by protect-files hook and migration tests.
- Generated code (OpenAPI servers/clients) is never hand-edited. Enforced by protect-files hook.
- Tests written by test-designer are never deleted, skipped or weakened. Enforced by test-integrity hook + `make test-integrity` in CI.
- Secrets live only in `.env` (gitignored); `.env.example` holds placeholders. Enforced by scan-secrets hook, gitleaks, protect-files.

## Workflow (S2/S5 of the spec, risk-tiered by owner decision 2026-10-24)
Every story has frontmatter `risk_tier: high | low`. **High** = it touches parsing, dedup, money, auth or ingestion (incl. webhooks and LLM prompts). **Low** = everything else (UI screens, CRUD, infra, docs, SDLC tooling).
- **High tier (full pipeline):** 1 product-analyst spec -> 2 spec-reviewer -> 3 architect (+ security-reviewer) -> 4 test-designer writes failing tests (worktree, proves they fail) -> 5 implementer -> 6 qa-verifier (fresh context; "claims to verify", never the author's summary) -> 7 code-reviewer + security-reviewer + triggered specialists in parallel -> 8 integrate.
- **Low tier:** 1 one spec pass (no spec-review loop; architect notes only if the story needs a design decision) -> 4 failing tests -> 5 implementation -> 6+7 ONE combined fresh-context verify+review agent (qa-verifier with the code-review checklist) -> 8 integrate.
- Integrate: `make check` + `make trace` green, story DONE, PROGRESS + metrics lines, then a PR to `main` that the owner merges personally (OWNER-011 open: GitHub forbids author approval). CI (`pr.yml`, job `gate`) must be green; `main` is protected (STORY-145).
- Loop limit: implement -> verify -> review at most 3 times per story; then write docs/ESCALATIONS.md, set `status: BLOCKED`, move to other ready work. Never ship a red gate.
- Budget: each phase has a time and AI-spend cap in `docs/SDLC_METRICS.md` ("Phase budgets"), reviewed against delivered stories at the phase gate; passing a cap is an owner checkpoint.
- Current goal: a working vertical slice (STORY-143 -> 141 -> 142 -> manual expense entry). `docs/PROMPT_DELIVERY_GATES.md` is mostly deferred (see its status banner); do not expand the SDLC further without an owner decision.
- Skills: `/next`, `/story <id>`, `/spec`, `/design`, `/tdd`, `/implement`, `/verify`, `/review`, `/security-review`, `/eval`, `/bug`, `/adr`, `/phase-gate <n>`, `/release <v>`, `/retro`, `/sdlc-selftest`, `/sdlc-status`.
- Stories: `docs/backlog/EPIC-NN-<slug>/STORY-NNN-<slug>.md` (template `docs/templates/story.md`; frontmatter status DRAFT|READY|IN_PROGRESS|BLOCKED|DONE|DESCOPED; `### REQ-<AREA>-<NNN>` headings with Given/When/Then). Reports `.verify.md` / `.review.md` beside the story. Branch `story/STORY-NNN-<slug>`; commits `type(scope): subject [STORY-NNN]` (lefthook enforces).
- REQ areas: WA SMS NTF IOS RCPT PARSE DEDUP EXP AN UI AND PWA SEC OPS. Tests carry REQ ids (`@Tag("REQ-SMS-004")`, `describe('REQ-SMS-004 ...')`, Kotlin backtick names, eval `tags`).
- Human checkpoints only: secrets/accounts/numbers; first production deploy, registering the WhatsApp number, first messages to real phones, destroying prod data, rotating prod keys; budget overrun. Everything else: the gates are the approval.
- Evidence or it didn't happen: every PASS / APPROVE / READY carries the command run and its result.
- Record decisions as one line in `docs/DECISIONS.md` (significant ones also an ADR via `/adr`); progress as one line per event in `docs/PROGRESS.md`; defects and friction in `docs/LESSONS.md`.
- Phase end: `/phase-gate <n>` (writes docs/phases/phase-N-report.md) then `/retro`. A phase is done only when its gate report is GREEN.

## Agent roster (delegate; the orchestrator writes only trivial glue)
- `product-analyst` slices and specs stories (docs only) | `spec-reviewer` fresh-context spec review | `architect` design notes, ADRs, contract edits, migration plans, ArchUnit rules
- `test-designer` failing tests from the spec | `backend-engineer`, `ai-parsing-engineer`, `android-engineer`, `web-engineer`, `devops-engineer` implement (never verify their own work)
- `qa-verifier` adversarial verification with real runs (never the author) | `code-reviewer` correctness, design, test quality
- `security-reviewer` on `**/auth/**`, `**/webhook/**`, `**/ingest/**`, `contracts/**`, `**/prompts/**`, AndroidManifest, dependency files | `ux-a11y-reviewer` + `i18n-rtl-reviewer` on any UI file or string resource | `performance-engineer` on repositories, analytics, lists, bundle | `ai-eval-engineer` on prompts, parser/dedup code, model config, `eval/**`
- `docs-writer` guides, API.md, release notes | `sre-observer` dashboards, alerts, SLOs, post-deploy health | `retro-facilitator` per-phase retro; edits rules/agents/hooks (`make hooks-unlock` ... `make hooks-lock`)
- Output contracts (STATUS values, required sections, write scopes): `.claude/hooks/output-contracts.json`; the SubagentStop hook blocks a roster agent that violates them. Cap 4 concurrent implementers; reviewers only when triggered; model routing lives in the agent files.

## Guardrails (hooks wired in `.claude/settings.json`, tests in `.claude/hooks/tests`)
guard-bash (destructive git/rm/pipe-to-shell/secret printing/prod hosts) | protect-files (.env, keys, applied migrations, generated code, baseline, hooks/settings/spec, role write scopes) | scan-secrets | format-changed | test-integrity | stop-gate (`make check-fast` must be green to stop; 3 consecutive blocks -> RED entries in PROGRESS + ESCALATIONS) | check-output-contract | inject-context | snapshot-progress | budget-check.

## Definition of Done (story)
- [ ] Every acceptance criterion has a passing test tagged with its REQ id
- [ ] `make check` green; coverage/mutation thresholds met (`scripts/quality/quality-thresholds.yaml`)
- [ ] Eval harness green with no regression vs baseline (for AI-affecting changes)
- [ ] qa-verifier report PASS with evidence; reviewers report no BLOCKER/MAJOR
- [ ] UI: screenshot tests updated for LTR + RTL + dark; axe/accessibility clean
- [ ] Docs updated (API.md, setup docs, ADR if a decision was made); no new `TODO` without a STORY id or `TODO(owner)`; no new skip/disable
- [ ] `docs/PROGRESS.md` and the story status updated; `make trace` green
