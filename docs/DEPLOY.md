# DEPLOY — single VPS runbook

Skeleton written in Phase 0; filled in by `devops-engineer` stories in Phase 1+.
Layout and Makefile/CI contracts: [`infra/README.md`](../infra/README.md).
Optional AWS variant: `docs/DEPLOY_AWS.md` (TODO).

## 0. Prerequisites

- A VPS (2 vCPU / 4 GB RAM / 40 GB SSD is enough for a household), Ubuntu 24.04 LTS. TODO(owner): provider and region.
- A domain with an `A`/`AAAA` record pointing at the VPS (`DOMAIN` in `.env`). TODO(owner).
- Ports open: 22 (SSH, key-only), 80 and 443 (Caddy). Everything else closed by `ufw`.
- Docker Engine + Compose plugin installed from Docker's apt repository (not `curl | sh`).
- A deploy user `masroof` in the `docker` group; root SSH disabled; `fail2ban` on.
- GitHub repository secrets and variables set (see the header comments in
  `.github/workflows/main.yml` and `release.yml`): `PROD_SSH_KEY`, `PROD_HOST`,
  `STAGING_SSH_KEY`, `STAGING_HOST`, `ANDROID_KEYSTORE_*`, `COSIGN_KEY`, and
  variables `PROD_URL`, `STAGING_URL`, `STAGING_ENABLED`, `*_KNOWN_HOSTS`.

## 1. First deploy

1. On the VPS: `sudo mkdir -p /opt/masroof && sudo chown masroof: /opt/masroof`.
2. Copy `infra/docker-compose.yml`, `infra/docker-compose.prod.yml`, `infra/Caddyfile`
   and `infra/env/prod.env.example` -> `/opt/masroof/.env`; `chmod 600 .env`.
3. Fill `.env` (see `.env.example` at the repo root for every variable). Generate
   secrets locally: `openssl rand -base64 32` (`APP_ENCRYPTION_KEY`),
   `openssl rand -base64 48` (`JWT_SECRET`). Never paste them into chat, tickets or CI logs.
4. `docker compose --env-file .env -f docker-compose.yml -f docker-compose.prod.yml up -d`.
   Flyway migrates the empty database on first backend start.
5. Caddy obtains the certificate; verify `make smoke URL=https://$DOMAIN` from your laptop.
6. Register the WhatsApp webhook (`docs/SETUP_WHATSAPP.md`) only after step 5 is green.
7. Create the first admin + household through the bootstrap endpoint/CLI (TODO: story).
8. Install the cron entries: nightly `infra/scripts/backup.sh`, weekly `infra/scripts/restore-test.sh`.

## 2. Update (normal release)

Tag a release (release-please opens the PR; merging it creates `vX.Y.Z`) -> `release.yml`:

1. builds the signed APK and the versioned images (pushed to GHCR, cosign-signed when `COSIGN_KEY` exists),
2. `make backup` (pre-deploy dump + media sync),
3. `make deploy-prod` (`infra/scripts/deploy.sh`: pull `IMAGE_TAG`, `compose up -d`, record tags in `infra/.deployed/`),
4. `make smoke URL=$PROD_URL`,
5. on failure `make rollback` automatically (see 3), then the job fails loudly,
6. uploads APK + SBOMs to the GitHub Release. The backend serves the new APK via `/api/app/android/latest` with its SHA-256.

Manual equivalent from a laptop with the `/release` skill active: `make deploy-prod IMAGE_TAG=X.Y.Z`.
`guard-bash.sh` blocks commands against `PROD_HOST` unless `MASROOF_RELEASE_ACTIVE=1`.

## 3. Rollback

- Images: `make rollback` (runs `infra/scripts/rollback.sh` on the VPS). It redeploys the
  tags in `infra/.deployed/previous.env`, waits for `/health`, rotates the state files.
- Database: Flyway migrations are forward-only. Every migration that changes or drops data
  ships with an expand/contract plan in its story; if a release must be undone after a
  destructive migration, restore the pre-deploy backup (section 4) into a fresh DB and
  point the previous backend at it. Practise this in the weekly restore test.
- Record every rollback with `docs/templates/incident.md`.

## 4. Backups and restore test

- Nightly (cron on the VPS) and before every prod deploy: `infra/scripts/backup.sh` ->
  `pg_dump -Fc` + `mc mirror` of the media bucket, encrypted with `age`
  (`BACKUP_ENCRYPTION_PASSPHRASE`), uploaded to `BACKUP_S3_BUCKET`, 30 daily + 12 monthly retained.
- Weekly: `infra/scripts/restore-test.sh` restores the latest dump into a throwaway
  Postgres container, counts rows per table and compares media checksums; result posted
  to the admin via the bot (S13 alerts).
- Manual restore: `infra/scripts/restore.sh <dump>` (TODO) — documented step-by-step once written.

TODO(owner): where the backup bucket lives (same provider? different account?), passphrase storage (password manager).

## 5. Where secrets live

| Secret | Location | Never |
|---|---|---|
| App/DB/MinIO/JWT/WhatsApp/Anthropic keys | `/opt/masroof/.env` on the VPS (`600`, owner `masroof`) | in git, in images, in CI logs |
| CI deploy keys, keystore, cosign key | GitHub Actions secrets (environment-scoped `production` / `staging`) | in workflow files or PR comments |
| Android release keystore | GitHub secret `ANDROID_KEYSTORE_B64` + offline copy in the owner's password manager | on a laptop disk unencrypted |
| Backup passphrase | owner's password manager + VPS `.env` | in the backup bucket |

Rotation: every secret has an owner and a rotation note in `docs/security/THREAT_MODEL.md`
("Assets"). Revoke-and-replace, never edit in place without a note in `docs/DECISIONS.md`.

## 6. Staging

Same VPS, compose project `masroof-staging` under `/opt/masroof-staging`, own DB and bucket,
subdomain `staging.$DOMAIN`, Meta test number. Deployed by `main.yml` on every push to `main`
when `vars.STAGING_ENABLED == 'true'`; `make smoke URL=$STAGING_URL` gates it.

## 7. Monitoring hooks

Health: `GET /health` (must contain `UP`). Metrics: `/actuator/prometheus` (internal network only).
Alerts and dashboards: `infra/observability/` (TODO, S13).
