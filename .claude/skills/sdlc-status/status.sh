#!/usr/bin/env bash
# Read-only data collector for /sdlc-status. Prints Markdown. Never writes anything.
set -o pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../../scripts/env.sh
. "$HERE/../../../scripts/env.sh"
cd "$PROJECT_DIR" 2>/dev/null || { echo "cannot cd to project dir"; exit 0; }
STATE="${MASROOF_STATE_FILE:-.claude/state.json}"

echo "### State (.claude/state.json)"
if [ -f "$STATE" ]; then
  for k in phase story stage loop_count hooks_unlocked release_active rebaseline_allowed last_green updated_at; do
    printf -- '- %s: %s\n' "$k" "$(jq -r --arg k "$k" 'if .[$k] == null then "null" else (.[$k]|tostring) end' "$STATE" 2>/dev/null)"
  done
else
  echo "- state file missing (run: bash scripts/sdlc/state.sh show)"
fi
echo "- branch: $(git symbolic-ref --short HEAD 2>/dev/null || echo detached) · dirty files: $(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
echo

echo "### Backlog (stories per status, from frontmatter)"
if [ -d docs/backlog ]; then
  total=0
  for s in DRAFT READY IN_PROGRESS BLOCKED DONE DESCOPED; do
    n=$(grep -rlE "^status:[[:space:]]*$s([[:space:]]|$)" docs/backlog --include='STORY-*.md' 2>/dev/null | wc -l | tr -d ' ')
    total=$((total + n)); printf -- '- %s: %s\n' "$s" "$n"
  done
  echo "- total: $total"
  echo
  echo "READY stories (id · phase · depends_on):"
  grep -rlE '^status:[[:space:]]*READY' docs/backlog --include='STORY-*.md' 2>/dev/null | sort | while IFS= read -r f; do
    id=$(grep -m1 -E '^id:' "$f" | sed 's/^id:[[:space:]]*//'); ph=$(grep -m1 -E '^phase:' "$f" | sed 's/^phase:[[:space:]]*//')
    dep=$(grep -m1 -E '^depends_on:' "$f" | sed 's/^depends_on:[[:space:]]*//')
    printf -- '- %s · P%s · %s · %s\n' "$id" "$ph" "${dep:-[]}" "$f"
  done | head -20
  echo
  echo "IN_PROGRESS / BLOCKED:"
  grep -rlE '^status:[[:space:]]*(IN_PROGRESS|BLOCKED)' docs/backlog --include='STORY-*.md' 2>/dev/null | sort | while IFS= read -r f; do
    printf -- '- %s · %s · %s\n' "$(grep -m1 -E '^id:' "$f" | sed 's/^id:[[:space:]]*//')" "$(grep -m1 -E '^status:' "$f" | sed 's/^status:[[:space:]]*//')" "$f"
  done
else
  echo "- docs/backlog does not exist yet"
fi
echo

echo "### Last gate (.claude/state/last-check.log)"
log="${MASROOF_STATE_DIR:-.claude/state}/last-check.log"
if [ -f "$log" ]; then
  echo "- modified: $(date -r "$log" '+%Y-%m-%d %H:%M' 2>/dev/null)"
  echo '```'; tail -n 15 "$log"; echo '```'
else
  echo "- no gate run recorded yet (the Stop hook writes it)"
fi
echo "- hooks.log (last 5):"; tail -n 5 "${MASROOF_STATE_DIR:-.claude/state}/hooks.log" 2>/dev/null | sed 's/^/  /' || true
echo

echo "### Latest eval report (eval/reports/*.md)"
rep=$(find eval/reports -maxdepth 1 -name '*.md' -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)
if [ -n "$rep" ]; then
  echo "- $rep"
  grep -E '^(#|\|)' "$rep" | head -25 | sed 's/^/  /'
else
  echo "- none yet (run /eval)"
fi
echo

echo "### Open escalations (docs/ESCALATIONS.md)"
if [ -f docs/ESCALATIONS.md ]; then
  echo "- open: $(grep -cE '^- \[ \]' docs/ESCALATIONS.md || true) · resolved: $(grep -cE '^- \[x\]' docs/ESCALATIONS.md || true)"
  grep -E '^- \[ \]' docs/ESCALATIONS.md | cut -c1-180
else
  echo "- file missing"
fi
echo

echo "### AI spend (docs/SDLC_METRICS.md)"
if [ -f docs/SDLC_METRICS.md ]; then
  grep -E '^\|[[:space:]]*(stories done|stories blocked|AI spend \(USD\))' docs/SDLC_METRICS.md
  budget=$( (grep -E '^SDLC_AI_BUDGET_USD=' .env 2>/dev/null || grep -E '^SDLC_AI_BUDGET_USD=' .env.example 2>/dev/null) | head -1 | cut -d= -f2 | awk '{print $1}')
  echo "- budget: ${budget:-300} USD"
  echo "- ledger rows: $(awk '/^## AI spend ledger/{f=1;next} f&&/^\|/{n++} END{print (n>2?n-2:0)}' docs/SDLC_METRICS.md)"
else
  echo "- file missing"
fi
echo

echo "### Progress (last 8 lines of docs/PROGRESS.md)"
grep -vE '^[[:space:]]*$' docs/PROGRESS.md 2>/dev/null | tail -n 8 | sed 's/^/  /'
