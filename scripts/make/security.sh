#!/usr/bin/env bash
# make security: SAST (semgrep), secrets (gitleaks), SCA (osv-scanner), containers/IaC (trivy),
# DAST (ZAP baseline, when the stack is up) and MobSF (when an APK exists). CodeQL runs in CI only.
# Tools that are not installed are reported and skipped locally; CI installs all of them.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$PROJECT_DIR" || exit 1
fail=0; skipped=""
run() { local d="$1"; shift; printf '\n### %s\n' "$d"; if "$@"; then printf 'OK   %s\n' "$d"; else printf 'FAIL %s\n' "$d"; fail=1; fi; }
need() { command -v "$1" >/dev/null 2>&1 || { skipped="$skipped $1"; echo "skip: $1 not installed"; return 1; }; }

if need gitleaks; then run "gitleaks (full history)" gitleaks git --redact --config .gitleaks.toml --no-banner .; fi
if need semgrep; then
  run "semgrep" semgrep scan --error --quiet --metrics=off --config p/default --config p/owasp-top-ten --config .semgrep/ --exclude node_modules --exclude build --exclude dist .
else
  echo "hint: pip install semgrep (CI installs it)"
fi
if need osv-scanner; then run "osv-scanner" osv-scanner scan -r --skip-git . ; fi
if need trivy; then
  run "trivy fs (vulns, secrets, misconfig)" trivy fs --quiet --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --exit-code 1 --skip-dirs node_modules,build,dist .
  if [ -f infra/docker-compose.yml ]; then run "trivy config (IaC)" trivy config --quiet --severity HIGH,CRITICAL --exit-code 1 infra; fi
fi
if [ -n "${ZAP_TARGET:-}" ] && command -v docker >/dev/null 2>&1; then
  run "ZAP baseline against $ZAP_TARGET" docker run --rm --network host -t ghcr.io/zaproxy/zaproxy:stable zap-baseline.py -t "$ZAP_TARGET" -I
fi
apk="$(find android -path '*/outputs/apk/release/*.apk' 2>/dev/null | head -1 || true)"
if [ -n "$apk" ] && [ -n "${MOBSF_URL:-}" ]; then echo "MobSF scan of $apk via $MOBSF_URL is run by nightly CI"; fi
[ -n "$skipped" ] && printf '\nskipped (not installed):%s\n' "$skipped"
[ "$fail" -eq 0 ] && { echo; echo "security GREEN"; exit 0; } || { echo; echo "security RED"; exit 1; }
