#!/usr/bin/env bash
# make e2e (STORY-142). In order, so the two halves never fight over host port 80:
#   1. every infra/tests/*.sh compose check (each brings up and tears down its own stack)
#   2. one throwaway stack via scripts/make/compose.sh up (temp copy, temp .env, unique project)
#   3. web Playwright against http://localhost
#   4. teardown in a trap (down -v, temp dir removed)
# IMAGE_TAG is unset for the stack: the checks inspect masroof/*:dev, while CI exports IMAGE_TAG=<sha>.
# The real .env is never read or written.
set -uo pipefail
shopt -s nullglob
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1
REPO=$PWD
unset IMAGE_TAG

if curl -s -m 2 -o /dev/null http://localhost:80/; then
  echo "e2e: something already listens on port 80; stop it (make down) and retry" >&2
  exit 1
fi

rc=0
for t in infra/tests/*.sh; do
  echo "===== e2e: $t"
  bash "$t" || { echo "FAIL $t"; rc=1; }
done

WORK=$(mktemp -d)
export COMPOSE_PROJECT_NAME="masroof-e2e-$$"
GIT_SHA=$(git rev-parse HEAD | cut -c1-7)
export GIT_SHA
# shellcheck disable=SC2329  # invoked by the EXIT trap
cleanup() {
  (cd "$WORK" && docker compose --env-file .env -f infra/docker-compose.yml down -v >/dev/null 2>&1)
  rm -rf "$WORK"
}
trap cleanup EXIT

echo "===== e2e: web Playwright against http://localhost (throwaway stack $COMPOSE_PROJECT_NAME)"
git ls-files -co --exclude-standard -z | tar -c --null -T - 2>/dev/null | tar -x -C "$WORK"
rm -f "$WORK/.env" "$WORK/infra/scripts/seed-demo.sh"
sed -e '/^DOMAIN=/d' -e "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=e2e$RANDOM$RANDOM/" .env.example >"$WORK/.env"
echo "DOMAIN=" >>"$WORK/.env"
sed -i "s|^MASROOF_JWT_SECRET=.*|MASROOF_JWT_SECRET=e2e$(openssl rand -hex 24)|" "$WORK/.env"
# a random free 172.28.<n>.0/24 so this stack cannot clash with another project's pinned network
for _ in $(seq 1 50); do
  n=$((RANDOM % 200 + 20))
  if ! docker network ls -q | xargs -r docker network inspect --format '{{range .IPAM.Config}}{{.Subnet}} {{end}}' 2>/dev/null | grep -q "172.28.$n.0/24"; then
    printf 'MASROOF_NET_SUBNET=172.28.%s.0/24\nCADDY_IP=172.28.%s.10\n' "$n" "$n" >>"$WORK/.env"
    break
  fi
done
if (cd "$WORK" && bash scripts/make/compose.sh up); then
  (cd "$REPO/web" && E2E_BASE_URL=http://localhost npm run e2e) || { echo "FAIL web npm run e2e"; rc=1; }
else
  echo "FAIL e2e stack did not come up"; rc=1
fi
exit $rc
