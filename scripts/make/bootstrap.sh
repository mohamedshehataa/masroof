#!/usr/bin/env bash
# make bootstrap: one-time developer setup. Idempotent.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$PROJECT_DIR" || exit 1
echo "python: $("$PYTHON" --version 2>&1)"
"$PYTHON" -m pip install --quiet --user pyyaml 2>/dev/null || "$PYTHON" -m pip install --quiet pyyaml
for t in jq shellcheck gitleaks trivy osv-scanner make docker node; do printf '%-12s %s\n' "$t" "$(command -v "$t" || echo MISSING)"; done
if command -v npx >/dev/null 2>&1; then
  npx --yes lefthook@1 install >/dev/null 2>&1 && echo "lefthook: git hooks installed" || echo "lefthook: install failed (run: npx lefthook install)"
fi
[ -f web/package.json ] && (cd web && npm ci --no-audit --no-fund)
[ -f .env ] || { cp .env.example .env; echo ".env created from .env.example - fill in TODO(owner) values"; }
[ -f .claude/state.json ] || cp .claude/state.example.json .claude/state.json
echo "bootstrap done"
