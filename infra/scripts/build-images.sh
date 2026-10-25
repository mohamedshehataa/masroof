#!/usr/bin/env bash
# make build-images (REQ-OPS-061): build the backend and web images.
#
# Default (local): plain `docker build`, single architecture, tags masroof/backend:<tag> and masroof/web:<tag>.
#
# Env:
#   IMAGE_TAG       tag (default dev, the tag compose and infra/tests inspect)
#   IMAGE_REGISTRY  e.g. ghcr.io/mohamedshehataa -> images are named ${IMAGE_REGISTRY}/masroof-backend:<tag> and
#                   .../masroof-web:<tag> (lower-cased: GHCR rejects upper case)
#   PLATFORMS       e.g. linux/amd64,linux/arm64 -> buildx multi-arch build (the Google Cloud e2-micro host is amd64, so CI builds linux/amd64 only).
#                   One platform is loaded into the local daemon; several are built and only kept when IMAGE_PUSH=1
#                   (a daemon cannot hold a multi-arch index without the containerd image store).
#   IMAGE_PUSH=1    push to IMAGE_REGISTRY (requires it, and a prior `docker login`); never set locally.
# Never skips silently: Docker down or a failed build exits non-zero and names the image. A missing module is
# skipped by name.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

TAG="${IMAGE_TAG:-dev}"
REGISTRY="$(printf '%s' "${IMAGE_REGISTRY:-}" | tr '[:upper:]' '[:lower:]')"
PLATFORMS="${PLATFORMS:-}"
PUSH="${IMAGE_PUSH:-}"

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is unavailable (daemon not reachable); cannot build images" >&2
  exit 1
fi
if [ "$PUSH" = "1" ] && [ -z "$REGISTRY" ]; then
  echo "ERROR: IMAGE_PUSH=1 needs IMAGE_REGISTRY (e.g. ghcr.io/<owner>)" >&2
  exit 1
fi

GIT_SHA="${GIT_SHA:-$(git rev-parse HEAD 2>/dev/null | cut -c1-7 || true)}"
GIT_SHA="${GIT_SHA:-0000000}" # outside a git checkout; the Dockerfile only accepts 7-40 hex characters

image_ref() { # image_ref <name>
  if [ -n "$REGISTRY" ]; then printf '%s/masroof-%s:%s' "$REGISTRY" "$1" "$TAG"; else printf 'masroof/%s:%s' "$1" "$TAG"; fi
}

build() { # build <name> <docker build args...>
  local name="$1" ref out=()
  shift
  ref="$(image_ref "$name")"
  if [ -n "$PLATFORMS" ]; then
    case "$PLATFORMS" in
      *,*)
        if [ "$PUSH" = "1" ]; then out=(--push); else echo "build-images: $ref: multi-arch without IMAGE_PUSH=1 is a build check only (nothing is stored)"; fi ;;
      *)
        if [ "$PUSH" = "1" ]; then out=(--push); else out=(--load); fi ;;
    esac
    echo "build-images: building $ref for $PLATFORMS"
    if ! docker buildx build --platform "$PLATFORMS" -t "$ref" ${out[@]+"${out[@]}"} "$@"; then
      echo "ERROR: buildx build of the $name image ($ref, $PLATFORMS) failed" >&2
      exit 1
    fi
    return 0
  fi
  echo "build-images: building $ref"
  if ! docker build -t "$ref" "$@"; then
    echo "ERROR: build of the $name image ($ref) failed" >&2
    exit 1
  fi
  if [ "$PUSH" = "1" ]; then
    echo "build-images: pushing $ref"
    docker push "$ref" >/dev/null || { echo "ERROR: push of $ref failed" >&2; exit 1; }
  fi
}

build backend -f backend/Dockerfile --build-arg "GIT_SHA=$GIT_SHA" .
if [ -f web/package.json ]; then
  build web web
else
  echo "build-images: web/package.json absent, skipping web image ($(image_ref web) not built)"
fi
echo "build-images: done (tag $TAG${PLATFORMS:+, platforms $PLATFORMS})"
