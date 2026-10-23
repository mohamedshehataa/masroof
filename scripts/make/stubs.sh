#!/usr/bin/env bash
# Shared "module not present yet" behaviour for targets whose real implementation arrives with the
# module (Phase 1+). Each target delegates to the module's own script when it exists.
# shellcheck shell=bash
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$PROJECT_DIR" || exit 1
delegate() { # delegate <script-path> [args...]  -> runs it if present, else prints skip and exits 0
  local s="$1"; shift
  if [ -f "$s" ]; then bash "$s" "$@"; else echo "skip: $s not present yet (arrives with its module/phase)"; exit 0; fi
}
