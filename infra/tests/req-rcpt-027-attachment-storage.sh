#!/usr/bin/env bash
# REQ: REQ-RCPT-027
# Receipt attachments (STORY-168): the backend gets the MinIO S3 settings from the same .env values MinIO uses,
# does not hard-depend on MinIO to start, the prod memory caps are unchanged, and Spring's multipart limits match the
# 8 MiB attachment cap with uploads buffered in heap (not the tmpfs /tmp). Needs docker (compose config only).
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1

fails=0
py=python3; "$py" -c pass 2>/dev/null || py=python   # Linux CI has python3; Git Bash on Windows has python
fail() { echo "FAIL REQ-RCPT-027 $*" >&2; fails=$((fails + 1)); }

req_rcpt_027_backend_gets_s3_env_without_depending_on_minio() {
  local out backend
  out=$(POSTGRES_PASSWORD=x S3_ACCESS_KEY=ak-test S3_SECRET_KEY=sk-test DOMAIN=t.duckdns.org BACKEND_IMAGE_TAG=t1 WEB_IMAGE_TAG=t1 \
    docker compose -f infra/docker-compose.yml -f infra/docker-compose.prod.yml config --format json 2>&1) \
    || { fail "config: compose config failed: $out"; return; }
  backend=$(printf '%s' "$out" | "$py" -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["services"]["backend"]))' 2>/dev/null) \
    || { fail "config: no backend service"; return; }
  local problems
  problems=$(printf '%s' "$backend" | "$py" -c '
import json, sys
b = json.load(sys.stdin)
env = b.get("environment", {})
want = {"S3_ENDPOINT": "http://minio:9000", "S3_ACCESS_KEY": "ak-test", "S3_SECRET_KEY": "sk-test", "S3_BUCKET": "masroof-media"}
bad = ["env " + k for k, v in want.items() if env.get(k) != v]
if "minio" in (b.get("depends_on") or {}):
    bad.append("hard depends_on minio")
mem = str(b.get("deploy", {}).get("resources", {}).get("limits", {}).get("memory"))
if mem not in ("402653184", "384M", "384m"):
    bad.append("memory cap changed: " + mem)
print("; ".join(bad))
' 2>&1)
  [ -z "$problems" ] || fail "backend: $problems"
}

req_rcpt_027_multipart_limits_match_the_8_mib_cap() {
  local yml=backend/src/main/resources/application.yml
  grep -Eq '^\s+max-file-size:\s*8MB' "$yml" || fail "multipart: max-file-size is not 8MB in $yml"
  grep -Eq '^\s+file-size-threshold:\s*9MB' "$yml" || fail "multipart: file-size-threshold must keep uploads in heap (9MB) in $yml"
  grep -q 'MAX_BYTES = 8 \* 1024 \* 1024' backend/src/main/java/eg/masroof/application/attachment/AttachmentService.java \
    || fail "multipart: AttachmentService.MAX_BYTES is not 8 MiB"
}

req_rcpt_027_backend_gets_s3_env_without_depending_on_minio
req_rcpt_027_multipart_limits_match_the_8_mib_cap
[ "$fails" -eq 0 ] && echo "ok REQ-RCPT-027 attachment storage wiring" || exit 1
