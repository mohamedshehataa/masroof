# Masroof - the single command surface. Humans, agents and CI run exactly these targets.
# Needs GNU make >= 3.81 (GnuWin32 on Windows) and bash (Git Bash on Windows). Recipes call
# scripts/make/*.sh so the logic is testable and identical on every OS.
SHELL := bash
.DEFAULT_GOAL := help

ENV := . scripts/env.sh &&
MODULES_PRESENT := sdlc $(if $(wildcard backend/gradlew),backend) $(if $(wildcard web/package.json),web) $(if $(wildcard android/gradlew),android)
MODULE ?= $(MODULES_PRESENT)
BASE ?= main
SUITE ?=
URL ?= http://localhost
VERSION ?= dev
IMAGE_TAG ?= dev

.PHONY: help bootstrap bootstrap-host up down logs check-fast check check-full e2e eval eval-fast security mutation perf trace metrics \
        contract-lint contract-diff docs-api hooks-test hooks-unlock hooks-lock test-integrity sbom build-images build-web backend-integration \
        build-apk build-apk-release deploy-staging deploy-prod smoke rollback backup selftest sdlc-lint status

help: ## List targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  make %-18s %s\n",$$1,$$2}'

bootstrap: ## One-time dev setup: python deps, lefthook, web/android/backend deps when present
	@$(ENV) bash scripts/make/bootstrap.sh

up: ## docker compose up with seeded demo data (infra/docker-compose.yml)
	@$(ENV) bash scripts/make/compose.sh up
down: ## docker compose down (keeps volumes; `make down V=1` removes them)
	@$(ENV) bash scripts/make/compose.sh down $(V)
logs: ## Tail compose logs
	@$(ENV) bash scripts/make/compose.sh logs

check-fast: ## Compile, format, lint, unit tests for MODULE="sdlc backend web android" (default: present modules) < 3 min
	@$(ENV) bash scripts/make/check-fast.sh $(MODULE)
check: ## check-fast for all modules + integration + contract + coverage gates + traceability + hook tests < 15 min
	@$(ENV) bash scripts/make/check.sh
check-full: ## check + e2e, screenshots, mutation, full eval, security, perf, migration tests (nightly / phase gate)
	@$(ENV) bash scripts/make/check-full.sh

e2e: ## Playwright (web) + Maestro (android) against make up
	@$(ENV) bash scripts/make/e2e.sh
eval: ## AI eval harness vs baseline (SUITE= optional; real API when ANTHROPIC_API_KEY is set)
	@$(ENV) bash scripts/make/eval.sh full $(SUITE)
eval-fast: ## Stratified ~25% eval subset (PR gate)
	@$(ENV) bash scripts/make/eval.sh fast $(SUITE)
security: ## semgrep, gitleaks, osv-scanner, trivy (+ ZAP/MobSF when targets exist)
	@$(ENV) bash scripts/make/security.sh
mutation: ## PIT mutation tests on backend money/parsing/dedup/analytics
	@$(ENV) bash scripts/make/module.sh backend mutation
perf: ## k6, Lighthouse CI, size-limit, Macrobenchmark
	@$(ENV) bash scripts/make/perf.sh
trace: ## Regenerate docs/TRACEABILITY.md; fail on uncovered REQ IDs for DONE stories or unknown REQ IDs in tests
	@$(ENV) $$PYTHON scripts/quality/traceability.py --out docs/TRACEABILITY.md
metrics: ## Regenerate docs/SDLC_METRICS.md
	@$(ENV) $$PYTHON scripts/quality/sdlc-metrics.py --out docs/SDLC_METRICS.md

contract-lint: ## Lint contracts/openapi.yaml (redocly)
	@$(ENV) bash scripts/make/contract.sh lint
docs-api: ## Regenerate docs/API.md from contracts/openapi.yaml (deterministic)
	@$(ENV) $$PYTHON scripts/quality/gen-api-docs.py --contract contracts/openapi.yaml --out docs/API.md
contract-diff: ## Breaking-change check of contracts/openapi.yaml vs BASE=main (oasdiff)
	@$(ENV) bash scripts/quality/oasdiff.sh $(BASE)

hooks-test: ## Run the hook test suite (.claude/hooks/tests)
	@$(ENV) bash .claude/hooks/tests/run.sh
hooks-unlock: ## Allow edits to .claude/hooks and settings.json (deliberate; record why in docs/DECISIONS.md)
	@$(ENV) bash scripts/sdlc/state.sh unlock-hooks && echo "hooks UNLOCKED - run 'make hooks-lock' when done"
hooks-lock: ## Re-protect .claude/hooks and settings.json
	@$(ENV) bash scripts/sdlc/state.sh lock-hooks && echo "hooks locked"
test-integrity: ## Full-diff test integrity check vs BASE=main (no lost assertions, no new skips)
	@$(ENV) bash scripts/quality/test-integrity-check.sh $(BASE)
sdlc-lint: ## Validate agents, skills, rules, settings, stories, workflows
	@$(ENV) $$PYTHON scripts/quality/sdlc-lint.py

sbom: ## CycloneDX SBOMs for backend, web, android into build/sbom/
	@$(ENV) bash scripts/make/sbom.sh
backend-integration: ## Backend Testcontainers integrationTest + ArchUnit archTest; fails on zero tests (CI job)
	@$(ENV) bash scripts/make/backend-integration.sh
build-images: ## Build backend + web images tagged IMAGE_TAG (default dev); PLATFORMS=linux/amd64,linux/arm64 = multi-arch, IMAGE_REGISTRY/IMAGE_PUSH=1 = push
	@$(ENV) IMAGE_TAG=$(IMAGE_TAG) bash scripts/make/build.sh images
build-web: ## Production web bundle
	@$(ENV) bash scripts/make/build.sh web
build-apk: ## Debug APK
	@$(ENV) bash scripts/make/build.sh apk-debug
build-apk-release: ## Signed release APK (needs keystore env vars)
	@$(ENV) bash scripts/make/build.sh apk-release $(VERSION)

deploy-staging: ## Deploy VERSION to staging (requires STAGING_HOST; only via /release)
	@$(ENV) bash scripts/make/deploy.sh staging $(VERSION)
deploy-prod: ## Deploy VERSION to prod over ssh: make deploy-prod HOST=<ip> DOMAIN=<name> VERSION=<tag> (only via /release)
	@$(ENV) bash scripts/make/deploy.sh prod $(VERSION)
bootstrap-host: ## One-time server setup: make bootstrap-host HOST=<ip> DOMAIN=<name>.duckdns.org DUCKDNS_TOKEN_FILE=<file> [STEP=setup|admin-add|admin-clear] (only via /release)
	@$(ENV) bash scripts/make/deploy-guard.sh infra/scripts/bootstrap-host.sh $(or $(STEP),setup)
smoke: ## Smoke test URL=$(URL)
	@$(ENV) bash infra/scripts/smoke.sh $(URL)
rollback: ## Roll back to the previously deployed images
	@$(ENV) bash infra/scripts/rollback.sh
backup: ## Run the server backup now (HOST=<ip>); the VM also backs up nightly
	@$(ENV) bash infra/scripts/backup.sh

selftest: ## Prove every SDLC guard blocks (writes docs/SDLC_SELFTEST.md)
	@$(ENV) bash scripts/sdlc/selftest.sh
status: ## Print SDLC status (phase, story, backlog counts, gates, escalations, spend)
	@$(ENV) bash scripts/sdlc/status.sh
