#!/usr/bin/env bash
# make status / /sdlc-status: one screen of SDLC state. Read-only.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$PROJECT_DIR" || exit 1
S="scripts/sdlc/state.sh"
echo "== Masroof SDLC status =="
printf 'Phase %s | story %s | stage %s | loop %s/3 | hooks_unlocked=%s | release_active=%s\n' \
  "$(bash $S get phase)" "$(bash $S get story)" "$(bash $S get stage)" "$(bash $S get loop_count)" \
  "$(bash $S get hooks_unlocked)" "$(bash $S get release_active)"
echo "Branch: $(git symbolic-ref --short HEAD 2>/dev/null || echo detached) | dirty files: $(git status --porcelain 2>/dev/null | wc -l | tr -d ' ') | HEAD: $(git rev-parse --short HEAD 2>/dev/null || echo none)"
echo
echo "-- Backlog"
if [ -d docs/backlog ]; then
  for st in DRAFT READY IN_PROGRESS BLOCKED DONE DESCOPED; do
    n=$(grep -rlE "^status:[[:space:]]*$st\b" docs/backlog --include='STORY-*.md' 2>/dev/null | grep -vE '\.(verify|review|bug)\.md$' | wc -l | tr -d ' ')
    printf '  %-12s %s\n' "$st" "$n"
  done
  echo "  next READY (by phase, id):"
  grep -rlE '^status:[[:space:]]*READY' docs/backlog --include='STORY-*.md' 2>/dev/null | grep -vE '\.(verify|review|bug)\.md$' | while read -r f; do
    ph=$(grep -E '^phase:' "$f" | head -1 | awk '{print $2}'); id=$(grep -E '^id:' "$f" | head -1 | awk '{print $2}'); t=$(grep -E '^title:' "$f" | head -1 | cut -d: -f2- | sed 's/^ *//')
    printf '%s %s %s\n' "$ph" "$id" "$t"
  done | sort -k1,1n -k2,2 | head -5 | sed 's/^/    P/'
else
  echo "  (no backlog yet)"
fi
echo
echo "-- Last gates"
[ -f .claude/state/last-check.log ] && { echo "  check-fast: $(grep -E 'check-fast (GREEN|RED)' .claude/state/last-check.log | tail -1)"; }
latest_eval=$(find eval/reports -name '*.md' 2>/dev/null | sort | tail -1)
[ -n "$latest_eval" ] && echo "  eval: $latest_eval -> $(grep -iE '^(\*\*)?(overall|result|verdict)' "$latest_eval" | head -1)"
[ -f docs/SDLC_SELFTEST.md ] && echo "  selftest: $(grep -cE '\| *PROVEN' docs/SDLC_SELFTEST.md 2>/dev/null || echo 0) guards proven"
echo
echo "-- Open escalations: $(grep -cE '^- \[ \]' docs/ESCALATIONS.md 2>/dev/null || echo 0)"
grep -E '^- \[ \]' docs/ESCALATIONS.md 2>/dev/null | cut -c1-120 | head -8 | sed 's/^/  /'
echo
echo "-- AI spend: $(grep -E '^\|[[:space:]]*AI spend \(USD\)' docs/SDLC_METRICS.md 2>/dev/null | awk -F'|' '{gsub(/ /,"",$3); print "$" $3}') (budget: SDLC_AI_BUDGET_USD=$(grep -E '^SDLC_AI_BUDGET_USD=' .env .env.example 2>/dev/null | head -1 | cut -d= -f2 | awk '{print $1}'))"
echo "-- Last PROGRESS lines:"
grep -vE '^\s*$|^#' docs/PROGRESS.md 2>/dev/null | tail -5 | cut -c1-160 | sed 's/^/  /'
