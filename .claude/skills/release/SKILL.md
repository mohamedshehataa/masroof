---
name: release
description: Cuts a release (changelog, tag, signed APK, staging deploy + smoke, S16 owner checkpoint for the first production deploy, prod deploy + smoke, automatic rollback on failure) with the release_active state flag on only for the duration. Use when the owner says /release vX.Y.Z after a GREEN phase gate; never run production commands outside it.
argument-hint: "vX.Y.Z"
disable-model-invocation: true
user-invocable: true
---

# /release $0

`guard-bash.sh` blocks commands that mention `PROD_HOST`/`STAGING_HOST` unless `release_active` is true.
Turn it on at step 1 and off at the end of EVERY path (success, failure, rollback, owner checkpoint).

## 0. Preconditions (all must hold, quote the evidence)
- `$0` matches `^v[0-9]+\.[0-9]+\.[0-9]+$` and `git tag -l $0` is empty.
- On `main`, clean tree, up to date with origin; latest phase gate report is GREEN; `make check` green on HEAD.
- `.env` has `PROD_HOST`, `STAGING_HOST` (and the keystore path for the APK) -> else escalate `OWNER-NNN` and stop.
- No open `GATE-*` escalation. Delegate shell-heavy steps to `devops-engineer` only when a script is missing;
  the deploy itself runs through the make targets below so the one command surface holds.

## 1. Arm
```bash
source scripts/env.sh
bash scripts/sdlc/state.sh release-on; bash scripts/sdlc/state.sh set stage integrate
trap 'bash scripts/sdlc/state.sh release-off' EXIT      # in every shell you open for this skill
```
PROGRESS: `<ts> · P<phase> · SDLC · integrate · INFO · release $0 started`.

## 2. Changelog and version
- `CHANGELOG.md`: section `## $0 — <date>` built from `git log <prev tag>..HEAD --format='- %s'` grouped by type
  (feat/fix/perf/docs/other), story tags kept. Bump versions: `android/app/build.gradle.kts` (versionName/Code),
  `web/package.json`, backend `gradle.properties`/`build.gradle.kts`, `contracts/openapi.yaml` `info.version`.
- Commit `chore(release): $0 [SDLC]`; `git tag -a $0 -m "Masroof $0"`; `git push origin main $0` when a remote exists.
  (`release.yml` then runs the same steps in CI; locally continue so the result does not depend on CI.)

## 3. Build artifacts
```bash
make build-images && make build-web && make build-apk-release && make sbom
```
APK signing needs the owner's keystore (`TODO(owner)` in `.env.example`); missing -> escalate and continue with
staging only. Record SHA-256 of the APK for `/api/app/android/latest`.

## 4. Staging
```bash
make deploy-staging && make smoke URL="$STAGING_URL"
```
Smoke red -> `make rollback` (staging), PROGRESS RED, `release-off`, stop; file a READY story from the smoke output.

## 5. S16 checkpoint — first production deploy needs the owner's explicit go IN CHAT
Check `grep -c 'deploy-prod' docs/PROGRESS.md` (0 = never deployed) or no `prod-deployed` marker in
`docs/DEPLOY.md`. If this is the first production deploy and the owner has not said "go"/"deploy to prod" for
`$0` in this conversation: write `docs/ESCALATIONS.md` `- [ ] **OWNER-NNN · first production deploy $0** —
staging smoke GREEN (<url>, <time>); APK <sha256>; images <tags>. Decision: reply "go $0" to deploy, or "hold".`,
PROGRESS INFO, `release-off`, and STOP this skill. Do not interpret older messages or a `/release` invocation as
the go. Later deploys of patch/minor versions do not need the checkpoint unless they rewrite or delete
production data, rotate production keys, or send messages to real phone numbers for the first time (also S16).

## 6. Production
```bash
make backup                       # DB + media snapshot first; abort if it fails
make deploy-prod && make smoke URL="$PROD_URL"
```
Smoke or health red -> `make rollback` immediately, then `make smoke URL="$PROD_URL"` again; write
`docs/incidents/<date>-release-$0.md` from `docs/templates/incident.md`; PROGRESS RED; escalate; `release-off`.
Green -> `docs/DEPLOY.md` marker line `prod-deployed $0 <date> <image tags>`; GitHub Release with APK + SBOMs
(`gh release create $0 --notes-file <changelog section>` when `gh` is available).

## 7. Disarm and record (every path)
```bash
bash scripts/sdlc/state.sh release-off; bash scripts/sdlc/state.sh get release_active   # must print false
```
PROGRESS: `<ts> · P<phase> · SDLC · integrate · <GREEN|RED> · release $0: staging <ok|fail>, prod <ok|rolled back|awaiting owner>`.
SDLC_METRICS ledger row for the release. Commit `docs(release): $0 notes [SDLC]`.

## Exit gate
`release_active` is false; every executed step has its command and result quoted; a RED smoke always ended in
a rollback; the first prod deploy happened only after the owner's go in this chat.
