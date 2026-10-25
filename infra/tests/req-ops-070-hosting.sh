#!/usr/bin/env bash
# REQ: REQ-OPS-070
# Google Cloud e2-micro hosting (SDLC, owner decision 2026-10-26): production overlay validates and publishes only
# 80/443, deploy.sh validates its inputs, rolls back when the smoke test fails (stubbed ssh, no network), the
# Caddy prod config carries the security headers, the stack fits 1 GB RAM, bootstrap.sh opens only 22/80/443, adds swap and holds no secrets.
# Needs docker (compose config only; nothing is started). Run via `make e2e`.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1

fails=0
fail() { echo "FAIL REQ-OPS-070 $*" >&2; fails=$((fails + 1)); }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

req_ops_070_prod_overlay_validates_and_publishes_only_80_443() {
  local out ports
  out=$(POSTGRES_PASSWORD=x DOMAIN=t.duckdns.org BACKEND_IMAGE_TAG=t1 WEB_IMAGE_TAG=t1 \
    docker compose -f infra/docker-compose.yml -f infra/docker-compose.prod.yml config 2>&1) \
    || { fail "REQ-OPS-070_config: compose config failed: $out"; return; }
  ports=$(printf '%s\n' "$out" | grep -E '^\s+published:' | tr -d ' "' | sort | tr '\n' ',')
  [ "$ports" = "published:443,published:80," ] || fail "REQ-OPS-070_ports: published ports are '$ports', want exactly 80 and 443"
  printf '%s\n' "$out" | grep -q 'ghcr.io/mohamedshehataa/masroof-backend:t1' || fail "REQ-OPS-070_images: backend not pulled from GHCR by tag"
  printf '%s\n' "$out" | grep -q 'ghcr.io/mohamedshehataa/masroof-web:t1' || fail "REQ-OPS-070_images: web not pulled from GHCR by tag"
  printf '%s\n' "$out" | grep -q 'Caddyfile.prod' || fail "REQ-OPS-070_caddy: prod Caddyfile not mounted"
  if POSTGRES_PASSWORD=x BACKEND_IMAGE_TAG=t1 WEB_IMAGE_TAG=t1 docker compose -f infra/docker-compose.yml -f infra/docker-compose.prod.yml config >/dev/null 2>&1; then
    fail "REQ-OPS-070_domain_required: config succeeded without DOMAIN"
  fi
}

req_ops_070_caddy_prod_has_security_headers() {
  local h
  for h in 'Strict-Transport-Security "max-age=31536000; includeSubDomains"' 'X-Content-Type-Options "nosniff"' "default-src 'self'" "frame-ancestors 'none'" 'Referrer-Policy'; do
    grep -qF "$h" infra/Caddyfile.prod || fail "REQ-OPS-070_headers: Caddyfile.prod lacks $h"
  done
  grep -qF "{\$DOMAIN}" infra/Caddyfile.prod || fail "REQ-OPS-070_headers: Caddyfile.prod must serve {\$DOMAIN} (automatic HTTPS)"
}

req_ops_070_deploy_validates_inputs() {
  local rc
  (DEPLOY_DRY_RUN=1 HOST='' PROD_HOST='' DOMAIN='' bash infra/scripts/deploy.sh prod 1.0.0 >/dev/null 2>&1); rc=$?
  [ "$rc" -eq 2 ] || fail "REQ-OPS-070_deploy_host: missing HOST exited $rc, want 2"
  (DEPLOY_DRY_RUN=1 HOST=1.2.3.4 DOMAIN=x.duckdns.org bash infra/scripts/deploy.sh prod dev >/dev/null 2>&1); rc=$?
  [ "$rc" -eq 2 ] || fail "REQ-OPS-070_deploy_version: VERSION=dev exited $rc, want 2"
  (DEPLOY_DRY_RUN=1 HOST=1.2.3.4 DOMAIN=x.duckdns.org bash infra/scripts/deploy.sh prod 1.0.0 >/dev/null 2>&1); rc=$?
  [ "$rc" -eq 0 ] || fail "REQ-OPS-070_deploy_dry_run: dry run exited $rc"
}

# Stubbed ssh logs every remote command (and fails `apply` when FAIL_APPLY=1, exit code EXIT_CODE for backup).
make_stubs() {
  local bin="$work/bin"
  mkdir -p "$bin"
  # shellcheck disable=SC2016  # the stub's own $-expressions must stay literal
  printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" >> "%s"\ncat > /dev/null\ncase " $* " in *" apply "*) [ "${FAIL_APPLY:-}" = 1 ] && exit 1 ;; esac\nexit "${STUB_SSH_EXIT:-0}"\n' "$work/ssh.log" > "$bin/ssh"
  chmod +x "$bin/ssh"
  printf '#!/usr/bin/env bash\nexit 1\n' > "$work/smoke-fail.sh"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$work/smoke-ok.sh"
}
run_deploy() { # run_deploy <smoke-script> [env assignments via the environment] -> rc
  : > "$work/ssh.log"
  (PATH="$work/bin:$PATH" HOST=1.2.3.4 DOMAIN=x.duckdns.org MASROOF_SMOKE_SCRIPT="$work/$1" bash infra/scripts/deploy.sh prod 1.2.3 >/dev/null 2>&1)
}

req_ops_070_deploy_rolls_back_when_smoke_fails() {
  local rc
  make_stubs
  run_deploy smoke-fail.sh; rc=$?
  [ "$rc" -eq 1 ] || fail "REQ-OPS-070_rollback_exit: failed smoke exited $rc, want 1"
  grep -q 'bash -s -- restore' "$work/ssh.log" || fail "REQ-OPS-070_rollback_called: no restore after a failed smoke"
  if grep -q 'bash -s -- commit' "$work/ssh.log"; then fail "REQ-OPS-070_no_commit_on_failure: unhealthy release was committed"; fi
  grep -q 'masroof-deploy@1.2.3.4' "$work/ssh.log" || fail "REQ-OPS-070_deploy_user: ssh did not use the deploy user"
}

req_ops_070_deploy_rolls_back_when_up_fails() {
  local rc
  make_stubs
  FAIL_APPLY=1 run_deploy smoke-ok.sh; rc=$?
  [ "$rc" -eq 1 ] || fail "REQ-OPS-070_apply_fail_exit: failing apply (compose up) exited $rc, want 1"
  grep -q 'bash -s -- restore' "$work/ssh.log" || fail "REQ-OPS-070_apply_fail_restore: no restore after a failing apply"
  if grep -q 'bash -s -- commit' "$work/ssh.log"; then fail "REQ-OPS-070_apply_fail_commit: failed release was committed"; fi
}

req_ops_070_deploy_commits_when_healthy() {
  local rc
  make_stubs
  run_deploy smoke-ok.sh; rc=$?
  [ "$rc" -eq 0 ] || fail "REQ-OPS-070_deploy_ok: passing smoke exited $rc"
  grep -q 'bash -s -- commit' "$work/ssh.log" || fail "REQ-OPS-070_commit: healthy release not committed"
  if grep -q 'restore' "$work/ssh.log"; then fail "REQ-OPS-070_no_rollback_on_success: restore ran after a green smoke"; fi
}

req_ops_070_deploy_rejects_injection_and_option_hosts() {
  local rc
  make_stubs
  : > "$work/ssh.log"
  (PATH="$work/bin:$PATH" HOME="$work/home" HOST=1.2.3.4 DOMAIN=x.duckdns.org MASROOF_SMOKE_SCRIPT="$work/smoke-ok.sh" \
    bash infra/scripts/deploy.sh prod 'v1.0;touch${IFS}$HOME/PWNED;#' >/dev/null 2>&1); rc=$?
  [ "$rc" -eq 2 ] || fail "REQ-OPS-070_inject_version: injection payload in VERSION exited $rc, want 2"
  [ ! -s "$work/ssh.log" ] || fail "REQ-OPS-070_inject_version_ssh: ssh was invoked with an invalid VERSION"
  (PATH="$work/bin:$PATH" HOST=1.2.3.4 DOMAIN=x.duckdns.org IMAGE_REGISTRY='ghcr.io/o;id' MASROOF_SMOKE_SCRIPT="$work/smoke-ok.sh" \
    bash infra/scripts/deploy.sh prod 1.2.3 >/dev/null 2>&1); rc=$?
  [ "$rc" -eq 2 ] || fail "REQ-OPS-070_inject_registry: injection payload in IMAGE_REGISTRY exited $rc, want 2"
  for h in '-oProxyCommand=touch /tmp/x' '-oFoo=1' 'deploy@-oFoo' 'a b' 'h;id'; do
    (PATH="$work/bin:$PATH" HOST="$h" DOMAIN=x.duckdns.org MASROOF_SMOKE_SCRIPT="$work/smoke-ok.sh" \
      bash infra/scripts/deploy.sh prod 1.2.3 >/dev/null 2>&1); rc=$?
    [ "$rc" -eq 2 ] || fail "REQ-OPS-070_bad_host: HOST='$h' exited $rc, want 2"
  done
  (PATH="$work/bin:$PATH" HOST=1.2.3.4 DOMAIN='x.duckdns.org;id' MASROOF_SMOKE_SCRIPT="$work/smoke-ok.sh" \
    bash infra/scripts/deploy.sh prod 1.2.3 >/dev/null 2>&1); rc=$?
  [ "$rc" -eq 2 ] || fail "REQ-OPS-070_bad_domain: DOMAIN with ';' exited $rc, want 2"
  [ ! -s "$work/ssh.log" ] || fail "REQ-OPS-070_invalid_input_ssh: ssh was invoked for invalid input"
  [ ! -e "$work/home/PWNED" ] || fail "REQ-OPS-070_pwned: injected command ran"
}

req_ops_070_backup_propagates_failure() {
  local rc
  make_stubs
  (PATH="$work/bin:$PATH" HOST=1.2.3.4 STUB_SSH_EXIT=7 bash infra/scripts/backup.sh >/dev/null 2>&1); rc=$?
  [ "$rc" -eq 7 ] || fail "REQ-OPS-070_backup_exit: remote failure exited $rc, want 7"
  (PATH="$work/bin:$PATH" HOST=1.2.3.4 STUB_SSH_EXIT=0 bash infra/scripts/backup.sh >/dev/null 2>&1); rc=$?
  [ "$rc" -eq 0 ] || fail "REQ-OPS-070_backup_ok: remote success exited $rc"
  (PATH="$work/bin:$PATH" HOST='-oProxyCommand=x' bash infra/scripts/backup.sh >/dev/null 2>&1); rc=$?
  [ "$rc" -eq 2 ] || fail "REQ-OPS-070_backup_dash_host: leading-dash HOST exited $rc, want 2"
}

# remote-deploy.sh state machine with a stub docker: previous/current only change after commit.
req_ops_070_remote_state_only_changes_after_commit() {
  local h="$work/rhome" st bin="$work/dbin"
  st="$h/masroof/infra/.deployed"
  mkdir -p "$bin" "$h/masroof/infra"
  : > "$h/masroof/infra/.env"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$bin/docker"; chmod +x "$bin/docker"
  rd() { (HOME="$h" PATH="$bin:$PATH" bash infra/host/remote-deploy.sh "$@" >/dev/null 2>&1); }
  rd apply 1.0 1.0 ghcr.io/o; [ ! -f "$st/current.env" ] || fail "REQ-OPS-070_state_apply: apply wrote current.env"
  if rd restore; then fail "REQ-OPS-070_state_restore_first: restore with nothing healthy must fail"; fi
  rd apply 1.0 1.0 ghcr.io/o; rd commit
  grep -q 'BACKEND_IMAGE_TAG=1.0' "$st/current.env" || fail "REQ-OPS-070_state_commit: current.env not written on commit"
  [ ! -f "$st/previous.env" ] || fail "REQ-OPS-070_state_first_previous: previous.env exists after the first release"
  rd apply 1.0 1.0 ghcr.io/o; rd commit
  [ ! -f "$st/previous.env" ] || fail "REQ-OPS-070_state_same_version: same-version redeploy set previous = current"
  rd apply 2.0 2.0 ghcr.io/o; rd commit
  grep -q 'BACKEND_IMAGE_TAG=1.0' "$st/previous.env" || fail "REQ-OPS-070_state_previous: previous.env is not the prior release"
  grep -q 'BACKEND_IMAGE_TAG=2.0' "$st/current.env" || fail "REQ-OPS-070_state_current: current.env is not the new release"
  rd apply 3.0 3.0 ghcr.io/o; rd restore
  grep -q 'BACKEND_IMAGE_TAG=2.0' "$st/current.env" || fail "REQ-OPS-070_state_failed_release: a failed candidate changed current.env"
}

# GCP has no cloud-init stage here: infra/host/bootstrap.sh is the single provisioning source (firewall, swap, no secrets).
req_ops_070_bootstrap_firewall_swap_and_no_secrets() {
  local f=infra/host/bootstrap.sh p
  bash -n "$f" || fail "REQ-OPS-070_bootstrap_syntax: bash -n failed"
  for p in 22/tcp 80/tcp 443/tcp; do grep -q "ufw allow $p" "$f" || fail "REQ-OPS-070_ufw: bootstrap does not allow $p"; done
  [ "$(grep -c '^ufw allow' "$f")" -eq 3 ] || fail "REQ-OPS-070_ufw_only_three: bootstrap allows ports beyond 22/80/443"
  if ! { grep -q 'vm.swappiness = 10' "$f" && grep -q '/swapfile none swap sw 0 0' "$f"; }; then
    fail "REQ-OPS-070_swap: bootstrap does not create a persistent swapfile with swappiness 10"
  fi
  grep -q 'DEPLOY_USER:-masroof-deploy' "$f" || fail "REQ-OPS-070_deploy_user: bootstrap default user is not masroof-deploy"
  if grep -q 'iptables' "$f"; then fail "REQ-OPS-070_no_oracle_iptables: the Oracle iptables workaround is still present"; fi
  if grep -q 'systemctl enable.*keepalive' "$f"; then fail "REQ-OPS-070_no_keepalive: the Oracle keep-alive timer is still installed"; fi
  [ ! -e infra/oracle ] || fail "REQ-OPS-070_one_source: infra/oracle still exists next to infra/host"
  if grep -Eqi 'token=[A-Za-z0-9]{8}|BEGIN [A-Z ]*PRIVATE|[0-9a-f]{40}' "$f"; then
    fail "REQ-OPS-070_no_secrets: a secret-looking literal is present in the bootstrap script"
  fi
  if command -v shellcheck >/dev/null 2>&1; then
    shellcheck -x "$f" infra/host/remote-deploy.sh infra/scripts/deploy.sh infra/scripts/backup.sh infra/scripts/validate.sh >/dev/null 2>&1 \
      || fail "REQ-OPS-070_shellcheck: shellcheck reports issues in the host/deploy scripts"
  fi
}

# 1 GB RAM: every service has a cap, the caps sum below 900 MB, the JVM and Postgres are tuned for it.
req_ops_070_stack_fits_one_gb() {
  local json total svc mem
  json=$(POSTGRES_PASSWORD=x DOMAIN=t.duckdns.org BACKEND_IMAGE_TAG=t1 WEB_IMAGE_TAG=t1 \
    docker compose -f infra/docker-compose.yml -f infra/docker-compose.prod.yml config --format json 2>&1) \
    || { fail "REQ-OPS-070_mem_config: compose config failed: $json"; return; }
  for svc in postgres backend web caddy minio; do
    mem=$(printf '%s' "$json" | jq -r --arg s "$svc" '.services[$s].deploy.resources.limits.memory // empty')
    [ -n "$mem" ] || fail "REQ-OPS-070_mem_limit_$svc: no memory limit on $svc"
  done
  total=$(printf '%s' "$json" | jq '[.services[].deploy.resources.limits.memory | tonumber] | add')
  { [ "${total:-0}" -gt 0 ] && [ "${total:-0}" -le 900000000 ]; } || fail "REQ-OPS-070_mem_sum: limits sum to '${total:-?}' bytes, want 1..900000000"
  printf '%s' "$json" | jq -e '.services.backend.environment.JAVA_TOOL_OPTIONS | (test("-Xmx[0-9]+m") and test("UseSerialGC"))' >/dev/null \
    || fail "REQ-OPS-070_jvm_flags: backend JAVA_TOOL_OPTIONS lacks an explicit -Xmx and SerialGC"
  printf '%s' "$json" | jq -e '.services.backend.environment.SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE | (tonumber <= 10)' >/dev/null \
    || fail "REQ-OPS-070_hikari: Hikari pool is not capped at 10 or fewer"
  printf '%s' "$json" | jq -e '.services.postgres.command | (index("max_connections=20") != null and index("shared_buffers=48MB") != null)' >/dev/null \
    || fail "REQ-OPS-070_pg_tuning: Postgres is not tuned for small memory (max_connections=20, shared_buffers=48MB)"
  printf '%s' "$json" | jq -e '.services.backend.healthcheck.start_period' >/dev/null \
    || fail "REQ-OPS-070_start_period: backend healthcheck start_period is not set for the slow shared vCPU"
}

req_ops_070_build_images_push_needs_registry() {
  local out rc
  out=$(IMAGE_PUSH=1 IMAGE_REGISTRY='' bash infra/scripts/build-images.sh 2>&1); rc=$?
  [ "$rc" -ne 0 ] || fail "REQ-OPS-070_push_registry: IMAGE_PUSH=1 without IMAGE_REGISTRY exited 0"
  case "$out" in *IMAGE_REGISTRY*|*Docker*) ;; *) fail "REQ-OPS-070_push_registry_msg: unhelpful message: $out" ;; esac
}

req_ops_070_prod_overlay_validates_and_publishes_only_80_443
req_ops_070_caddy_prod_has_security_headers
req_ops_070_deploy_validates_inputs
req_ops_070_deploy_rolls_back_when_smoke_fails
req_ops_070_deploy_rolls_back_when_up_fails
req_ops_070_deploy_commits_when_healthy
req_ops_070_deploy_rejects_injection_and_option_hosts
req_ops_070_backup_propagates_failure
req_ops_070_remote_state_only_changes_after_commit
req_ops_070_bootstrap_firewall_swap_and_no_secrets
req_ops_070_stack_fits_one_gb
req_ops_070_build_images_push_needs_registry
[ "$fails" -eq 0 ] && echo "ok REQ-OPS-070 hosting" || exit 1
