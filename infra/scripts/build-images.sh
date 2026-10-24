#!/usr/bin/env bash
# make build-images (REQ-OPS-061): build masroof/backend:<tag> and masroof/web:<tag> with plain `docker build`.
# IMAGE_TAG sets the tag (default dev, the tag compose and infra/tests inspect). Never skips silently:
# Docker down or a failed build exits non-zero and names the image. A missing module is skipped by name.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

TAG="${IMAGE_TAG:-dev}"

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is unavailable (daemon not reachable); cannot build images" >&2
  exit 1
fi

GIT_SHA="${GIT_SHA:-$(git rev-parse HEAD 2>/dev/null | cut -c1-7 || true)}"
GIT_SHA="${GIT_SHA:-0000000}" # outside a git checkout; the Dockerfile only accepts 7-40 hex characters

build() { # build <name> <docker build args...>
  local name="$1"
  shift
  echo "build-images: building masroof/$name:$TAG"
  if ! docker build -t "masroof/$name:$TAG" "$@"; then
    echo "ERROR: build of the $name image (masroof/$name:$TAG) failed" >&2
    exit 1
  fi
}

build backend -f backend/Dockerfile --build-arg "GIT_SHA=$GIT_SHA" .
if [ -f web/package.json ]; then
  build web web
else
  echo "build-images: web/package.json absent, skipping web image (masroof/web:$TAG not built)"
fi
echo "build-images: done (tag $TAG)"
