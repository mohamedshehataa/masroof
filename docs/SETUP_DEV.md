# Developer setup

Everything runs through `make`; the Makefile calls `scripts/make/*.sh`, which source `scripts/env.sh` so the same
commands work on Windows (Git Bash), Linux CI and macOS.

## Windows 11 (the owner's machine)

Installed on 2026-10-23 via winget: GNU Make 3.81 (GnuWin32), Python 3.12, jq, gitleaks, trivy, osv-scanner; already
present: Git for Windows (Git Bash), Java 21 (Temurin), Node 24, Docker Desktop, GitHub CLI, shellcheck. Also installed:
`oasdiff` (GitHub release tarball into `~/.local/bin`, checksum verified) and `lefthook` (`npm install -g lefthook`).

```bash
# in Git Bash, from the repo root
source scripts/env.sh      # puts every tool on PATH, picks the real python, sets PROJECT_DIR
make bootstrap             # pyyaml, lefthook git hooks, module deps when present, .env from .env.example
make check-fast MODULE=sdlc
make status
```

Known Windows quirks (handled by the scripts, listed so nobody re-learns them): `jq.exe` prints CRLF (use the `jq()`
wrapper from `.claude/hooks/lib.sh`); MSYS rewrites `/tmp/...` arguments passed to native tools (pass paths via env
vars); `/tmp` is an alias of `%LOCALAPPDATA%\Temp` (compare paths after `cygpath -m`); the Windows Store `python` stub
shadows the real interpreter unless Python312 is first on PATH; GNU sed treats `\r` in a replacement as a real CR.

## Linux / macOS

```bash
sudo apt-get install -y make jq shellcheck python3 python3-pip   # or brew install make jq shellcheck python
pip install pyyaml
# gitleaks, trivy, osv-scanner, oasdiff: see each project's releases; CI installs them in .github/workflows
make bootstrap
```

## Docker

`make up` needs Docker Desktop running. It starts Postgres, MinIO, the backend, the web app and Caddy from
`infra/docker-compose.yml` (arrives with the walking skeleton) and seeds demo data.

## Secrets

Copy `.env.example` to `.env` and fill the `TODO(owner)` values. `.env` is gitignored, blocked for Claude by a hook,
and never printed by any script. Everything the system reads is listed, with a comment, in `.env.example`.

## Claude Code

Open the repo in Claude Code. `.claude/settings.json` wires the hooks; `CLAUDE.md` is the operating manual; `/sdlc-status`
and `/next` drive the work. Agents added during a session become available as `subagent_type`s after a restart.
