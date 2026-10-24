# Phase 0 gate report

- **Date:** 2026-10-25  ·  **Command:** `make check-full` (on `phase/phase-0-gate` at d49cefe, main 56f9450 + this gate's fixes)

## Exit criteria
| criterion | result | evidence |
|---|---|---|
| S15.1–4 monorepo, Makefile, lefthook, CLAUDE.md files, agents, skills, hooks with tests, CI, contract v0, eval scaffolding + thresholds | GREEN | `make check-full` -> `OK hook tests`, `OK sdlc-lint (agents, skills, rules, settings, stories, workflows)`, `OK contract lint`, `OK: 169 cases valid` (eval datasets); CI `PR gate` and `Main gate` green on main (run 40132995420) |
| S15.5 backlog sliced; Ready for Phase 1 (§14 exit) | GREEN | all 19 `phase: 1` stories READY (STORY-016 and 146 specified, STORY-148 split out); release stories 137–140 confirmed as Phase 10; `sdlc-lint: 0 error(s), 0 warning(s)` |
| S15.6 every guard proven (`/sdlc-selftest`) | GREEN | `make selftest` -> `16 proven, 0 pending, 0 failed` (docs/SDLC_SELFTEST.md, rerun today after all Phase 0 changes) |
| S15.7 walking skeleton through the pipeline (health endpoint + web page showing backend version via `make up`) | GREEN | STORY-002, 143, 141, 142 DONE with verify PASS; `make e2e` -> compose checks ok + Playwright `19 passed`; `make up` brings up postgres, minio, backend, web and caddy healthy |
| every `phase: 0` story DONE or DESCOPED with a reason | GREEN | 7 DONE (001, 002, 141, 142, 143, 144, 145); STORY-147 DESCOPED (owner decision 2026-10-25, OWNER-014 option c) |
| each DONE story: verify PASS + review APPROVE | GREEN (owner exception for STORY-001) | 002, 141, 142, 143, 144, 145: verify PASS and review APPROVE. STORY-001: loop 3 verify FAIL, closed as accepted residual risk by owner decision 2026-10-24; the 4 findings are in STORY-146/148 (READY) |
| `make trace` covers every in-scope REQ | GREEN | `traceability: 336 REQs, 20 covered, 15 passing, 0 unknown, gate PASS` (no uncovered DONE REQ) |
| S9 thresholds for touched modules | GREEN | backend line 88.5% (>= 85), branch 77.8% (>= 75); web line 98.6%; money/parsing/dedup/analytics and mutation SKIP (no such packages yet; the gate turns strict when they appear, verified by planting a class); `COVERAGE_GATE: OK` |
| security | GREEN | `security GREEN`: gitleaks full history, osv-scanner, trivy fs + config clean; web/backend images 0 fixable HIGH/CRITICAL |
| eval not regressed | GREEN | `make check-full` -> `OK eval (full)`, `RESULT: PASS` vs eval/baseline.json (reference adapter) |
| migrations | GREEN | `OK migration tests (empty + previous snapshot)` |
| docs/PROGRESS.md current | GREEN | last entries 2026-10-25 (this gate) |

## Stories
| story | status | verify | review |
|---|---|---|---|
| STORY-001 SDLC guardrails proven | DONE (accepted risk) | loop 3 FAIL, owner accepted | – (follow-ups STORY-146, 148) |
| STORY-002 backend module, fast gate, V1 baseline | DONE | PASS | APPROVE |
| STORY-144 Spring Boot 4.1.2 + password guard | DONE | PASS | APPROVE |
| STORY-143 GET /health, backend image, compose | DONE | PASS | APPROVE |
| STORY-141 web version page, i18n/RTL, states | DONE | PASS (loop 2) | APPROVE |
| STORY-142 make up full stack, Caddy, smoke | DONE | PASS (loop 2) | APPROVE |
| STORY-145 CI on every PR (branch protection split off) | DONE | PASS (loop 2) | APPROVE |
| STORY-147 branch protection on main | DESCOPED | – | – |

## Metrics
From `docs/SDLC_METRICS.md` (Summary): stories done 7; blocked 0; median cycle time 1.0 d; mean loops per story 0.33; review findings BLOCKER/MAJOR/MINOR 1/12/55; escaped defects 4; flaky tests quarantined 0; AI spend about USD 137 (estimate) against the Phase 0 cap of USD 200, inside the 2026-10-27 deadline.
Coverage: backend line 88.5%, branch 77.8%; web line 98.6%. Mutation: no target classes yet.

## Open escalations
OWNER-001 (Anthropic key, Phase 3), OWNER-003 (WhatsApp, Phase 5), OWNER-004 (server + domain, Phase 10), OWNER-005 (signing keystore + FCM, Phase 4/10), OWNER-006 (member phone numbers, Phase 5), OWNER-007 (speech-to-text, Phase 5), OWNER-008 (error tracking, optional, Phase 9), OWNER-009 (budgets, defaults set), OWNER-010 (property-testing library), OWNER-012 (Actions secret for AI review, optional), OWNER-013 (bot token, deferred with the delivery gates). None blocks Phase 1.

## Notes
- A one-off Gradle `NoSuchFileException` (test-results file) in the first `check-full` run did not reproduce in 2 forced reruns and 3 full runs. The likely cause is a concurrent build in the same directory.
- Known for STORY-004: PIT targets all of `eg.masroof.domain.*`, so the first domain type without mutable code (an interface, record or enum) makes the mutation step fail with "check the classpath/filters". Narrow the targets to money/parsing/dedup/analytics, or exclude such types, when the first domain classes land.

## Verdict: GREEN
