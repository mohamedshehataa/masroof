#!/usr/bin/env bash
# Read-only candidate list for /next: READY stories whose depends_on are all DONE, lowest phase then lowest id.
set -o pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../../scripts/env.sh
. "$HERE/../../../scripts/env.sh"
cd "$PROJECT_DIR" 2>/dev/null || { echo "cannot cd to project dir"; exit 0; }

echo "state.story=$(bash scripts/sdlc/state.sh get story 2>/dev/null || echo unknown) · state.phase=$(bash scripts/sdlc/state.sh get phase 2>/dev/null || echo unknown)"
[ -d docs/backlog ] || { echo "docs/backlog does not exist: run /spec first"; exit 0; }

fm() { grep -m1 -E "^$2:" "$1" | sed -E "s/^$2:[[:space:]]*//; s/[[:space:]]+#.*$//"; }
done_ids=" $(grep -rlE '^status:[[:space:]]*DONE' docs/backlog --include='STORY-*.md' 2>/dev/null | while IFS= read -r f; do fm "$f" id; done | tr '\n' ' ') "
echo "DONE: ${done_ids}"
echo
echo "ELIGIBLE (READY, all depends_on DONE) — phase · id · size · modules · path:"
elig=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  id=$(fm "$f" id); ph=$(fm "$f" phase); deps=$(fm "$f" depends_on | tr -d '[]"' | tr ',' ' ')
  ok=true
  for d in $deps; do case "$done_ids" in *" $d "*) ;; *) ok=false ;; esac; done
  if $ok; then
    printf '%s\t%s\t%s\t%s\t%s\n' "${ph:-9}" "${id#STORY-}" "$(fm "$f" size)" "$(fm "$f" modules)" "$f"
    elig=$((elig + 1))
  else
    printf '%s\t%s\t%s\n' "${ph:-9}" "${id#STORY-}" "$f waiting on: $deps" >> "${TMPDIR:-/tmp}/next-waiting.$$"
  fi
done < <(grep -rlE '^status:[[:space:]]*READY' docs/backlog --include='STORY-*.md' 2>/dev/null) | sort -k1,1n -k2,2n | awk -F'\t' '{printf "- P%s · STORY-%s · %s · %s · %s\n",$1,$2,$3,$4,$5}'
echo
echo "READY but waiting on dependencies:"
if [ -f "${TMPDIR:-/tmp}/next-waiting.$$" ]; then sort -k1,1n -k2,2n "${TMPDIR:-/tmp}/next-waiting.$$" | awk -F'\t' '{printf "- P%s · STORY-%s · %s\n",$1,$2,$3}'; rm -f "${TMPDIR:-/tmp}/next-waiting.$$"; else echo "- none"; fi
echo
echo "Phase gate reports present: $(find docs/phases -maxdepth 1 -name 'phase-*-report.md' -printf '%f ' 2>/dev/null)"
echo "IN_PROGRESS: $(grep -rlE '^status:[[:space:]]*IN_PROGRESS' docs/backlog --include='STORY-*.md' 2>/dev/null | while IFS= read -r f; do fm "$f" id; done | tr '\n' ' ')"
echo "BLOCKED: $(grep -rlE '^status:[[:space:]]*BLOCKED' docs/backlog --include='STORY-*.md' 2>/dev/null | while IFS= read -r f; do fm "$f" id; done | tr '\n' ' ')"
