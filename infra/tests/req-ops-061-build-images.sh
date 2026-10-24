#!/usr/bin/env bash
# REQ: REQ-OPS-061
# STORY-016: `make build-images` with IMAGE_TAG=pr-12 builds the REAL images: exit 0, no "skip:" line,
# masroof/backend:pr-12 and masroof/web:pr-12 exist, each runs as a non-root user, and every FROM line of
# backend/Dockerfile and web/Dockerfile is pinned by @sha256 digest.
# Needs Docker, make (via scripts/env.sh) and network for base images. Run via `make e2e`.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1

TAG=pr-12
fails=0
fail() { echo "FAIL REQ-OPS-061 $*" >&2; fails=$((fails + 1)); }
cleanup() { docker image rm -f "masroof/backend:$TAG" "masroof/web:$TAG" >/dev/null 2>&1; }
trap cleanup EXIT

req_ops_061_every_from_is_digest_pinned() {
  local f line
  for f in backend/Dockerfile web/Dockerfile; do
    while IFS= read -r line; do
      case "$line" in *@sha256:*) ;; *) fail "REQ-OPS-061_from_digest: unpinned FROM in $f: $line" ;; esac
    done < <(grep -E '^FROM ' "$f")
  done
}

req_ops_061_build_images_builds_both_nonroot_images() {
  local out rc img user
  out=$(. scripts/env.sh && IMAGE_TAG="$TAG" make build-images 2>&1)
  rc=$?
  [ "$rc" -eq 0 ] || fail "REQ-OPS-061_exit_0: make build-images exited $rc"
  if printf '%s\n' "$out" | grep -q 'skip:'; then
    fail "REQ-OPS-061_no_skip: output contains a skip: line"
  fi
  for img in backend web; do
    docker image inspect "masroof/$img:$TAG" >/dev/null 2>&1 || { fail "REQ-OPS-061_image_exists: masroof/$img:$TAG was not built"; continue; }
    user=$(docker image inspect -f '{{.Config.User}}' "masroof/$img:$TAG")
    case "${user%%:*}" in
      ""|root|0) fail "REQ-OPS-061_nonroot: masroof/$img:$TAG Config.User='$user'" ;;
    esac
  done
}

req_ops_061_every_from_is_digest_pinned
req_ops_061_build_images_builds_both_nonroot_images
[ "$fails" -eq 0 ] && echo "ok REQ-OPS-061 build-images" || exit 1
