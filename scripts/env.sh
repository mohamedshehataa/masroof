#!/usr/bin/env bash
# Source this to get a consistent PATH on Windows (Git Bash) and Linux/macOS.
# Hooks, Makefile and CI all go through here so "one command surface" holds.
# shellcheck shell=bash
__masroof_add_path() {
  [ -d "$1" ] || return 0
  case ":$PATH:" in *":$1:"*) ;; *) PATH="$PATH:$1" ;; esac
}
__masroof_prepend_path() {
  [ -d "$1" ] || return 0
  case ":$PATH:" in *":$1:"*) ;; *) PATH="$1:$PATH" ;; esac
}
if [ -n "${LOCALAPPDATA:-}" ]; then
  _lad="$(cygpath -u "$LOCALAPPDATA" 2>/dev/null || printf '%s' "$LOCALAPPDATA")"
  # Real Python must beat the Windows Store "python" alias stub, so prepend.
  __masroof_prepend_path "$_lad/Programs/Python/Python312/Scripts"
  __masroof_prepend_path "$_lad/Programs/Python/Python312"
  __masroof_add_path "$_lad/Microsoft/WinGet/Links"
  if [ -d "$_lad/Microsoft/WinGet/Packages" ]; then
    for _p in "$_lad"/Microsoft/WinGet/Packages/*/; do __masroof_add_path "${_p%/}"; done
  fi
fi
__masroof_add_path "/c/Program Files (x86)/GnuWin32/bin"
__masroof_add_path "/c/Program Files/nodejs"
__masroof_add_path "/c/Program Files/GitHub CLI"
__masroof_add_path "$HOME/.local/bin"
__masroof_add_path "$(cygpath -u "${APPDATA:-}" 2>/dev/null)/npm"
export PATH
# jq.exe prints CRLF on Windows; scripts call `jqr` when they consume jq output as text.
jqr() { command jq "$@" | tr -d '\r'; }
# Pick a Python that actually runs (the Store stub prints an error and exits 49).
if python -c 'import sys' >/dev/null 2>&1; then export PYTHON=python
elif python3 -c 'import sys' >/dev/null 2>&1; then export PYTHON=python3
elif py -3 -c 'import sys' >/dev/null 2>&1; then export PYTHON="py -3"
else export PYTHON=python; fi
_env_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$_env_dir}"
# Normalise a Windows-style project dir (C:\x or C:/x) to the POSIX form Git Bash uses.
if command -v cygpath >/dev/null 2>&1; then PROJECT_DIR="$(cygpath -u "$PROJECT_DIR" 2>/dev/null || printf '%s' "$PROJECT_DIR")"; fi
export PROJECT_DIR
# Note: MSYS argument path conversion stays ON so native tools (jq.exe, python.exe) can open
# /tmp/... and /c/... paths. Scripts that pass path-like *strings* to native tools use env vars.
