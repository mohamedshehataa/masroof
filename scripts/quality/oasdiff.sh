#!/usr/bin/env bash
# oasdiff.sh — breaking-change gate for contracts/openapi.yaml.
#
# Usage:
#   scripts/quality/oasdiff.sh <base-ref> [adr-path]
#
# Compares `git show <base-ref>:contracts/openapi.yaml` (the base) with the working copy and runs
# `oasdiff breaking`. Uses a local `oasdiff` binary when present on PATH, otherwise falls back to
# `docker run --rm tufin/oasdiff`.
#
# Exit codes:
#   0  no breaking changes, or breaking changes explicitly accepted (see below)
#   1  breaking changes found and not accepted
#   2  usage / environment error
#
# Accepting a breaking change (S9 exception, REQ-OPS-051) requires BOTH of:
#   - env PR_LABELS (comma/space/newline separated label names) containing `breaking-change`, and
#   - a docs/adr/*.md added since <base-ref> that mentions a path changed incompatibly.
# Then it exits 0 with a warning. Legacy equivalent: env MASROOF_ALLOW_BREAKING=1 plus an existing ADR path
# passed as the second argument.
set -euo pipefail

CONTRACT_PATH="contracts/openapi.yaml"
OASDIFF_IMAGE="${OASDIFF_IMAGE:-tufin/oasdiff:latest}"

usage() {
  echo "usage: $0 <base-ref> [adr-path]" >&2
  echo "  env MASROOF_ALLOW_BREAKING=1 plus an ADR path accepts breaking changes" >&2
}

log() { printf '[oasdiff] %s\n' "$*" >&2; }

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage
  exit 2
fi

base_ref="$1"
adr_path="${2:-}"

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  log "not inside a git repository"
  exit 2
}
cd "$repo_root"

if [[ ! -f "$CONTRACT_PATH" ]]; then
  log "working copy has no $CONTRACT_PATH"
  exit 2
fi

if ! git rev-parse --verify --quiet "${base_ref}^{commit}" >/dev/null; then
  log "base ref '$base_ref' does not resolve to a commit"
  exit 2
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

base_file="$tmp_dir/base.openapi.yaml"
head_file="$tmp_dir/head.openapi.yaml"

if ! git show "${base_ref}:${CONTRACT_PATH}" >"$base_file" 2>/dev/null; then
  log "$CONTRACT_PATH does not exist at $base_ref; nothing to compare (new contract)"
  exit 0
fi
cp "$CONTRACT_PATH" "$head_file"

run_oasdiff() {
  # Arguments: base head. Prints the breaking-change report; returns oasdiff's exit code.
  if command -v oasdiff >/dev/null 2>&1; then
    log "using local oasdiff ($(oasdiff --version 2>/dev/null | head -n1))"
    oasdiff breaking "$1" "$2" --fail-on ERR --format text
  elif command -v docker >/dev/null 2>&1; then
    log "using docker image $OASDIFF_IMAGE"
    # The temp dir is mounted read-only; file names inside the container are fixed.
    docker run --rm -v "$tmp_dir:/specs:ro" "$OASDIFF_IMAGE" \
      breaking /specs/base.openapi.yaml /specs/head.openapi.yaml --fail-on ERR --format text
  else
    log "neither 'oasdiff' nor 'docker' is available on PATH"
    return 2
  fi
}

report_file="$tmp_dir/report.txt"
set +e
run_oasdiff "$base_file" "$head_file" | tee "$report_file"
status="${PIPESTATUS[0]}"
set -e

case "$status" in
  0)
    log "no breaking changes against $base_ref"
    exit 0
    ;;
  1)
    log "BREAKING changes detected against $base_ref"
    ;;
  2)
    exit 2
    ;;
  *)
    log "oasdiff failed with exit code $status"
    exit 2
    ;;
esac

# --- breaking changes found: check the override -------------------------------------------
# Paths the report names, from lines such as "in API GET /health".
breaking_paths="$(sed -n 's/.*in API [A-Z]* \(\/[^ ]*\).*/\1/p' "$report_file" | sort -u)"
if printf '%s' "${PR_LABELS:-}" | tr ', \t' '\n\n\n' | grep -qx 'breaking-change'; then
  new_adrs="$(git diff --name-only --diff-filter=A "$base_ref" -- 'docs/adr/*.md')"
  for adr in $new_adrs; do
    [[ -s "$adr" ]] || continue
    while IFS= read -r changed; do
      [[ -n "$changed" ]] || continue
      if grep -qF -- "$changed" "$adr"; then
        log "WARNING: breaking change against $base_ref ACCEPTED: label breaking-change and $adr references $changed"
        exit 0
      fi
    done <<<"$breaking_paths"
  done
  log "label breaking-change is set but no new docs/adr/*.md references a changed path"
else
  log "add the PR label 'breaking-change' (env PR_LABELS) and a new docs/adr/*.md naming the changed path to accept them"
fi

if [[ "${MASROOF_ALLOW_BREAKING:-0}" != "1" ]]; then
  log "set MASROOF_ALLOW_BREAKING=1 AND pass an ADR path to accept them"
  exit 1
fi

if [[ -z "$adr_path" ]]; then
  log "MASROOF_ALLOW_BREAKING=1 is set but no ADR path was passed"
  exit 1
fi

case "$adr_path" in
  docs/adr/*.md) ;;
  *)
    log "ADR path must match docs/adr/*.md (got '$adr_path')"
    exit 1
    ;;
esac

if [[ ! -s "$adr_path" ]]; then
  log "ADR '$adr_path' does not exist or is empty"
  exit 1
fi

if ! grep -qiE '^# ' "$adr_path"; then
  log "ADR '$adr_path' has no title heading; is it a real ADR?"
  exit 1
fi

log "breaking changes ACCEPTED by MASROOF_ALLOW_BREAKING=1 with ADR $adr_path"
exit 0
