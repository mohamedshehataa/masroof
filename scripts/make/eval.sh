#!/usr/bin/env bash
# make eval / make eval-fast: run the AI eval harness against the baseline.
#   eval.sh full|fast [suite]
# Adapter: http (real backend + real API) when ANTHROPIC_API_KEY is set and the backend is reachable,
# otherwise the deterministic reference parser. Override with EVAL_ADAPTER=reference|http|mock.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$PROJECT_DIR" || exit 1
mode="${1:-full}"; suite="${2:-}"
[ -f eval/runners/run.py ] || { echo "skip: eval harness not present"; exit 0; }
adapter="${EVAL_ADAPTER:-}"
if [ -z "$adapter" ]; then
  if [ -n "${ANTHROPIC_API_KEY:-}" ] && curl -fsS --max-time 3 "${EVAL_PARSE_URL:-http://localhost:8080/api/parse/dry-run}" -o /dev/null 2>/dev/null; then adapter=http; else adapter=reference; fi
fi
args=(--adapter "$adapter")
[ "$mode" = fast ] && args+=(--fast)
[ -n "$suite" ] && args+=(--suite "$suite")
echo "eval: mode=$mode adapter=$adapter suite=${suite:-all}"
"$PYTHON" eval/runners/run.py "${args[@]}"
