#!/usr/bin/env bash
# PreCompact: persist a machine-readable snapshot of the SDLC state before context is compacted.
HOOK_NAME=snapshot-progress
# shellcheck source=lib.sh
. "${BASH_SOURCE[0]%/*}/lib.sh"
read_input
trigger="$(json_get '.trigger')"
cd "$PROJECT_DIR" 2>/dev/null || exit 0
[ -f "$STATE_FILE" ] || cp .claude/state.example.json "$STATE_FILE" 2>/dev/null || exit 0
state_set last_compact "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
state_set last_compact_trigger "${trigger:-unknown}"
state_set branch "$(git symbolic-ref --short HEAD 2>/dev/null || echo detached)"
snap="$STATE_DIR/snapshots/state-$(date -u +%Y%m%dT%H%M%SZ).json"
if ! { mkdir -p "$STATE_DIR/snapshots" 2>/dev/null && cp "$STATE_FILE" "$snap" 2>/dev/null; }; then
  # never block compaction, but say why there is no snapshot (REQ-OPS-009 unhappy path)
  printf 'snapshot-progress: could not write the state snapshot %s (directory missing, not a directory, or not writable); state.json was still updated\n' "$snap" >&2
  log_hook "snapshot FAILED path=$snap"
  exit 0
fi
# keep the last 20 snapshots
# shellcheck disable=SC2012
ls -1t "$STATE_DIR"/snapshots/state-*.json 2>/dev/null | tail -n +21 | xargs -r rm -f 2>/dev/null || true
log_hook "snapshot trigger=${trigger:-unknown} story=$(state_get story) stage=$(state_get stage)"
exit 0
