# infra/ — deployment for a single VPS

Everything needed to run Masroof locally, on staging and in production with
`docker compose` behind Caddy (automatic HTTPS, required for WhatsApp webhooks).
Operator runbook: [`docs/DEPLOY.md`](../docs/DEPLOY.md). Security posture:
[`docs/security/`](../docs/security/).

## What lives here (planned layout)

| Path | Status | Purpose |
|---|---|---|
| `docker-compose.yml` | partly done (STORY-143, 142) | Base stack: `postgres`, `minio`, `backend`, `web`, `caddy` (only caddy publishes a port, 80); every service has a healthcheck; pinned digests; `otel-collector` still TODO |
| `docker-compose.dev.yml` | done (STORY-143) | Dev-only overlay on top of the base file: publishes the backend on `127.0.0.1:8080`; never loaded by `make up` or deploys |
| `docker-compose.staging.yml` | TODO | Staging overlay: separate compose project `masroof-staging`, own DB/bucket, Meta test number |
| `docker-compose.prod.yml` | TODO | Production overlay: resource limits, log rotation, restart policies |
| `Caddyfile` | partly done (STORY-142) | `DOMAIN` empty -> plain HTTP :80; `/health`, `/api/*` -> backend, everything else (never `/actuator/*`) -> web. TODO: HSTS, headers, body limits, rate limits (REQ-SEC-001, REQ-SEC-004) |
| `env/local.env.example`, `env/staging.env.example`, `env/prod.env.example` | TODO | Per-environment examples; the real `*.env` files live only on the VPS (`chmod 600`) and are gitignored |
| `scripts/smoke.sh` | done | `make smoke URL=<url>`: `/health` must say `"status":"UP"`, web root must hold `<div id="root">`, concurrently within 10 s |
| `scripts/rollback.sh` | done (stub until first deploy) | `make rollback`: redeploys the tags recorded in `.deployed/previous.env` |
| `scripts/deploy.sh` | TODO | Used by `make deploy-staging` / `make deploy-prod`: `ssh` to the host, pull images for `IMAGE_TAG`, `compose up -d`, write `.deployed/current.env` + rotate `previous.env`, run migrations via the backend's Flyway on start |
| `scripts/backup.sh` | TODO | `make backup`: `pg_dump` + MinIO `mc mirror`, encrypted with `age` using `BACKUP_ENCRYPTION_PASSPHRASE`, pushed to `BACKUP_S3_BUCKET`; nightly cron on the VPS + before every prod deploy (REQ-SEC-012) |
| `scripts/restore-test.sh` | TODO | Weekly: restore the latest dump into a throwaway container, count rows, check checksums (S13) |
| `.deployed/` | runtime, gitignored | `current.env`, `previous.env`, `rolled-back-*.env` written by deploy/rollback on the VPS |
| `observability/` | TODO | otel-collector config, Grafana dashboards JSON, alert rules (S13) |

## Contracts the Makefile and CI rely on

CI (`.github/workflows/*.yml`) only ever calls `make` targets. These targets
must honour the following environment variables:

| Variable | Used by | Meaning |
|---|---|---|
| `IMAGE_TAG` | `build-images`, `deploy-*`, `rollback` | Tag for `masroof-backend` and `masroof-web` images (PR: `pr-<n>`, main: commit SHA, release: `X.Y.Z`) |
| `IMAGE_REGISTRY` | `build-images`, `deploy-*` | Empty = local images `masroof/backend:<tag>` and `masroof/web:<tag>`; set (e.g. `ghcr.io/<owner>`) = `<registry>/masroof-backend:<tag>` and `<registry>/masroof-web:<tag>` |
| `IMAGE_PUSH=1` | `build-images` | Push after build (release only; CI logs in to GHCR beforehand) |
| `DEPLOY_HOST`, `DEPLOY_ENV` | `deploy-*`, `backup`, `rollback` | `user@host` from the `STAGING_HOST` / `PROD_HOST` secrets and `staging` / `prod` |
| `URL` | `smoke` | Public base URL to check |
| `BASE` | `contract-diff`, `test-integrity` | Git ref to diff against |
| `MASROOF_ALLOW_BREAKING=1` | `contract-diff` | Allow breaking OpenAPI changes (PR labelled `breaking-change` + ADR) |
| `MASROOF_RELEASE_ACTIVE=1` | hooks | Set by the `/release` skill and release.yml so `guard-bash.sh` allows commands against `PROD_HOST` |

## Environments

| | local | staging | prod |
|---|---|---|---|
| How | `make up` (compose, seeded demo data) | separate compose project on the same VPS, own Postgres DB + MinIO bucket, Meta test number | compose project `masroof` |
| URL | `http://localhost` (via Caddy; Vite dev on `http://localhost:5173`) | `vars.STAGING_URL` | `vars.PROD_URL` = `https://$DOMAIN` |
| Deploy | — | `main.yml` on every push to `main` when `vars.STAGING_ENABLED == 'true'` | `release.yml` on tag `v*`, with backup before and smoke + auto-rollback after |
| Secrets | `.env` (gitignored) | `/opt/masroof-staging/.env` on the VPS | `/opt/masroof/.env` on the VPS |

## Image hardening checklist (S9)

- [ ] Backend: distroless or `eclipse-temurin:21-jre` with a non-root user, `JAVA_TOOL_OPTIONS` for container memory, read-only root FS, `tmpfs` for `/tmp`
- [x] Web: static bundle served by the non-root `web` nginx-unprivileged image (no node at runtime)
- [ ] All base images pinned by digest; Dependabot `docker` ecosystem keeps them fresh
- [ ] Trivy image scan green (HIGH/CRITICAL with a fix = fail) in `main.yml`
- [ ] SBOM (CycloneDX) attached to each release

TODO(owner): VPS provider/size, domain name, `ACME_EMAIL`, backup bucket.
