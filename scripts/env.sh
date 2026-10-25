#!/usr/bin/env bash
# Source this to get a consistent PATH on Windows (Git Bash) and Linux/macOS.
# Hooks, Makefile and CI all go through here so "one command surface" holds.
# Every hook sources this, so it avoids forking (cygpath / python probes cost ~30-50 ms each on
# Windows): drive paths are converted in pure bash and Python is found by scanning PATH.
# shellcheck shell=bash
# Git Bash only: a Windows-style PATH entry (C:/x/bin) is split at its colon by bash, so tools in it are
# never found. Rewrite `C:/` to `/c/` (pure bash, no fork).
if [ "${OSTYPE:-}" = msys ] || [ "${OSTYPE:-}" = cygwin ]; then
  # Split on ':' and rewrite only real drive entries: a one-letter element followed by an element that
  # starts with '/' AND whose /<drive> mount exists. `/usr/bin:x:/y` is therefore left untouched.
  IFS=: read -r -a __masroof_parts <<< "$PATH"
  __masroof_p=""
  __masroof_i=0
  while [ "$__masroof_i" -lt "${#__masroof_parts[@]}" ]; do
    __masroof_e="${__masroof_parts[$__masroof_i]}"
    __masroof_n="${__masroof_parts[$((__masroof_i + 1))]:-}"
    if [[ $__masroof_e =~ ^[A-Za-z]$ ]] && [[ $__masroof_n == /* ]] && [ -d "/${__masroof_e,,}" ]; then
      __masroof_e="/${__masroof_e,,}$__masroof_n"
      __masroof_i=$((__masroof_i + 1))
    fi
    __masroof_p="${__masroof_p:+$__masroof_p:}$__masroof_e"
    __masroof_i=$((__masroof_i + 1))
  done
  PATH="$__masroof_p"
  unset __masroof_p __masroof_parts __masroof_i __masroof_e __masroof_n
fi
__masroof_add_path() {
  [ -d "$1" ] || return 0
  case ":$PATH:" in *":$1:"*) ;; *) PATH="$PATH:$1" ;; esac
}
__masroof_prepend_path() {
  [ -d "$1" ] || return 0
  case ":$PATH:" in *":$1:"*) ;; *) PATH="$1:$PATH" ;; esac
}
# __masroof_to_unix <out-var> <path>: C:\x\y or C:/x/y -> /c/x/y (Git Bash drive mounts), no fork.
# Falls back to cygpath when the /<drive> mount does not exist (Cygwin's /cygdrive prefix).
__masroof_to_unix() {
  local p="${2//\\//}" d
  case "$p" in
    [A-Za-z]:/*|[A-Za-z]:)
      d="${p:0:1}"; d="${d,,}"
      if [ -d "/$d" ]; then p="/$d${p:2}"
      elif command -v cygpath >/dev/null 2>&1; then p="$(cygpath -u "$2" 2>/dev/null || printf '%s' "$2")"
      fi ;;
  esac
  printf -v "$1" '%s' "$p"
}
# __masroof_on_path <name>: sets __masroof_dir to the first PATH dir holding <name> or <name>.exe
__masroof_on_path() {
  local d dirs
  __masroof_dir=""
  IFS=: read -r -a dirs <<< "$PATH"
  for d in "${dirs[@]}"; do
    if [ -f "$d/$1" ] || [ -f "$d/$1.exe" ]; then __masroof_dir="$d"; return 0; fi
  done
  return 1
}
_lad=""; _apd=""
if [ -n "${LOCALAPPDATA:-}" ]; then
  __masroof_to_unix _lad "$LOCALAPPDATA"
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
if [ -n "${APPDATA:-}" ]; then __masroof_to_unix _apd "$APPDATA"; __masroof_add_path "$_apd/npm"; fi
export PATH
# jq.exe prints CRLF on Windows; scripts call `jqr` when they consume jq output as text.
jqr() { command jq "$@" | tr -d '\r'; }
# Pick a Python that actually runs (the Windows Store stub in WindowsApps prints an error and exits 49).
# Fast path: the first `python` on PATH is a real install, not the WindowsApps stub -> no probe.
if __masroof_on_path python && [[ "$__masroof_dir" != *[Ww]indows[Aa]pps* ]]; then export PYTHON=python
elif python -c 'import sys' >/dev/null 2>&1; then export PYTHON=python
elif python3 -c 'import sys' >/dev/null 2>&1; then export PYTHON=python3
elif py -3 -c 'import sys' >/dev/null 2>&1; then export PYTHON="py -3"
else export PYTHON=python; fi
if [ -n "${CLAUDE_PROJECT_DIR:-}" ]; then PROJECT_DIR="$CLAUDE_PROJECT_DIR"
else PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; fi
# Normalise a Windows-style project dir (C:\x or C:/x) to the POSIX form Git Bash uses.
__masroof_to_unix PROJECT_DIR "$PROJECT_DIR"
export PROJECT_DIR
# Note: MSYS argument path conversion stays ON so native tools (jq.exe, python.exe) can open
# /tmp/... and /c/... paths. Scripts that pass path-like *strings* to native tools use env vars.
