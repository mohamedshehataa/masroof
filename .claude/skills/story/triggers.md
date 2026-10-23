# Triggers and routing (PROMPT.md S5)

## Specialist reviewer triggers
Compute `git diff --name-only $BASE...HEAD` and launch every reviewer whose pattern matches at least one
path. `code-reviewer` always runs. Match with bash `case` patterns or `grep -E` on the list below.

| Reviewer | Launch when a changed path matches | Also when |
|---|---|---|
| `security-reviewer` | `**/auth/**`, `**/webhook/**`, `**/webhooks/**`, `**/ingest/**`, `contracts/**`, `**/prompts/**`, `android/**/AndroidManifest.xml`, dependency files (`build.gradle.kts`, `gradle/libs.versions.toml`, `web/package.json`, `web/package-lock.json`, `*/Dockerfile`, `infra/**`), `**/security/**`, `**/crypto/**`, files touching PII columns (`phone`, `whatsapp_id`, `token_hash`, `raw_message`, `media`) | the story's "Security / privacy / LLM-safety impact" section is not "None" |
| `ux-a11y-reviewer` + `i18n-rtl-reviewer` (always together) | any UI file: `web/src/**/*.tsx`, `web/src/**/*.css`, `android/**/ui/**`, `android/**/*Screen*.kt`, `android/**/*Composable*.kt`, string resources (`web/src/i18n/**`, `web/public/locales/**`, `android/**/res/values*/strings.xml`), `web/src/**/*.stories.*` | the story has a "UX states" section filled in |
| `performance-engineer` | repository/query code (`**/adapters/out/**`, `**/repository/**`, `**/*Repository*.java`, `**/db/migration/**`, `**/*Query*.kt`, `**/dao/**`), analytics (`**/analytics/**`), list screens (`**/*List*.tsx`, `**/*List*Screen*.kt`, `**/Transactions*`), bundle-affecting changes (`web/package.json`, `web/vite.config.*`, `web/src/main.tsx`, `web/src/App.tsx`, `android/app/build.gradle.kts`) | the story is tagged `perf` or touches a p95 budget in S9 |
| `ai-eval-engineer` | `**/prompts/**`, parser/dedup/normalization code (`**/parsing/**`, `**/parser/**`, `**/dedup/**`, `**/normaliz*/**`, `**/classif*/**`), model config (`**/application*.yml` lines containing `anthropic`, `model`, `temperature`), `eval/**`, `fixtures/**` | the story's "Eval impact" section is not "None" |

All reviewers for one story are launched in ONE message so they run in parallel. Each gets the same inputs
(story path, `BASE`, `HEAD`, how to run, "Claims to verify") and never the author's summary.

## Implementer routing (stage 5)
`owner_agent` in the frontmatter wins. Otherwise, per module in `modules`:

| module | implementer | notes |
|---|---|---|
| `backend` | `backend-engineer` | use `ai-parsing-engineer` instead when the story touches `parsing`, `prompts`, `dedup`, `normalization`, `merchant` rules, FX, receipt vision or model config |
| `web` | `web-engineer` | |
| `android` | `android-engineer` | |
| `infra`, `sdlc` | `devops-engineer` | CI, Docker, Caddy, backups, observability, Makefile, hooks (hooks need `make hooks-unlock`, which only /retro and /sdlc-selftest may do; otherwise escalate) |
| `contracts` | `architect` writes `contracts/openapi.yaml` in stage 3; then `backend-engineer` regenerates servers/clients | generated dirs are never hand-edited |
| `eval` | `ai-parsing-engineer` for code; `test-designer` for new dataset cases; `ai-eval-engineer` for baselines/thresholds | baseline moves only via `/eval --rebaseline` |

Merge order for multi-module stories: contract -> backend -> web/android (clients may run in parallel
worktrees, cap 4 concurrent implementers project-wide).

## Stage pass values (from `.claude/hooks/output-contracts.json`)
| agent | pass | fail / loop |
|---|---|---|
| product-analyst | `SPEC_READY` | `BLOCKED` |
| spec-reviewer | `APPROVE` | `CHANGES_REQUESTED` |
| architect | `DESIGN_READY` | `BLOCKED` |
| test-designer | `TESTS_FAILING_AS_EXPECTED` | `BLOCKED` |
| implementers | `READY_FOR_VERIFY` | `BLOCKED` |
| qa-verifier | `PASS` | `FAIL`, `BLOCKED` |
| code-reviewer, security-reviewer, ux-a11y-reviewer, i18n-rtl-reviewer, performance-engineer | `APPROVE` | `CHANGES_REQUESTED` |
| ai-eval-engineer | `APPROVE` | `REJECT`, `BLOCKED` |

A report without a parsable `STATUS:` line counts as the fail value (the SubagentStop hook already blocks it;
if you still see one, treat it as FAIL and relaunch once).
