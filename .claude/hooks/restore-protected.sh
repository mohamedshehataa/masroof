#!/usr/bin/env bash
# REQ-OPS-011 protected-file integrity backstop (STORY-001 loop 3). guard-bash (REQ-OPS-001 B13) stays the
# first, friendlier line; this hook makes a protected-file write that guard-bash missed harmless.
#   restore-protected.sh --snapshot   PreToolUse(Bash|PowerShell): record the pre-call state
#   restore-protected.sh              PostToolUse / PostToolUseFailure(Bash|PowerShell): undo the call's changes
# Protected set (= B13): .claude/hooks/**, .claude/settings.json, .claude/settings.local.json, docs/PROMPT.md, docs/PROMPT_DELIVERY_GATES.md,
# eval/baseline.json, .env and .env.* (repo root and one directory down; *.example is not protected).
# Files are found by globbing the real directory entries, so they always carry their canonical long name:
# the spelling a command used to write them (8.3 short name, ::$DATA, \\?\ prefix) does not matter.
# The snapshot ($STATE_DIR/protected-snapshot.json) maps each existing protected file to its SHA-256 and
# records HEAD and the HEAD reflog length. The pre-call bytes of TRACKED protected files are kept once in
# $STATE_DIR/protected-backup/<sha256>; untracked ones (.env, settings.local.json) are never copied.
# Locked = state.hooks_unlocked is not true and MASROOF_HOOKS_UNLOCK is not set; a call that started locked
# is judged locked even if it unlocks itself. While locked, only what changed DURING the call is undone:
#   tracked file changed or deleted -> its pre-call content (backup blob checked by hash, else HEAD), unstaged
#   new untracked file              -> removed (and dropped from the index when the call staged it)
#   untracked file changed/deleted  -> reported "changed (not restorable)" / "deleted (not restorable)"
# Not undone: `.env` created with exactly the content of .env.example (cp .env.example .env), and a file that
# now equals the new HEAD after HEAD moved by checkout/switch/merge/pull/rebase/reset with no commit created
# in the same call (HEAD reflog) - a branch change, not a write. Files changed before the call are left alone.
# Anything undone or reported -> {"decision":"block","reason":...} on stdout (exit 0).
# Malformed JSON / not a git work tree -> one stderr line, exit 0.
# Overrides for tests: CLAUDE_PROJECT_DIR, MASROOF_STATE_FILE, MASROOF_STATE_DIR.
HOOK_NAME=restore-protected
# shellcheck source=lib.sh
. "${BASH_SOURCE[0]%/*}/lib.sh"
mode=post; [ "${1:-}" = --snapshot ] && mode=pre
read_input
if ! json_valid; then
  printf 'restore-protected: WARNING hook input is not valid JSON; protected files were not checked\n' >&2
  exit 0
fi
event=PostToolUse
[[ "$HOOK_INPUT" =~ \"hook_event_name\"[[:space:]]*:[[:space:]]*\"([A-Za-z]+)\" ]] && event="${BASH_REMATCH[1]}"
cd "$PROJECT_DIR" 2>/dev/null || { printf 'restore-protected: WARNING project dir not found; protected files were not checked\n' >&2; exit 0; }
SNAP="$STATE_DIR/protected-snapshot.json"
BACKUP="$STATE_DIR/protected-backup"
shopt -s nullglob dotglob globstar
umask 077

if command -v sha256sum >/dev/null 2>&1; then HASH=(sha256sum -z --); HASH1=(sha256sum); ZERO=1
elif command -v shasum >/dev/null 2>&1; then HASH=(shasum -a 256 --); HASH1=(shasum -a 256); ZERO=0
else printf 'restore-protected: WARNING no sha256sum/shasum on PATH; protected files were not checked\n' >&2; exit 0
fi

# ---------- git facts without forking (each fork costs ~30 ms on Windows) ----------
GI_DIR=""; GI_COMMON=""
git_dirs() {
  local l=""
  if [ -d .git ]; then GI_DIR=.git
  elif [ -f .git ]; then IFS= read -r l < .git || true; l="${l%$'\r'}"; GI_DIR="${l#gitdir: }"
  else return 1; fi
  [ -f "$GI_DIR/HEAD" ] || return 1
  GI_COMMON="$GI_DIR"
  if [ -f "$GI_DIR/commondir" ]; then
    IFS= read -r l < "$GI_DIR/commondir" || true; l="${l%$'\r'}"
    case "$l" in /*|[A-Za-z]:*) GI_COMMON="$l" ;; *) GI_COMMON="$GI_DIR/$l" ;; esac
  fi
}
# read_ref <name> -> REF_SHA (follows symbolic refs; loose refs, then packed-refs)
read_ref() {
  local name="$1" d l n=0 sha name2
  REF_SHA=""
  while (( n < 5 )); do
    n=$((n + 1)); l=""
    for d in "$GI_DIR" "$GI_COMMON"; do
      if [ -f "$d/$name" ]; then IFS= read -r l < "$d/$name" || true; l="${l%$'\r'}"; break; fi
    done
    if [ -z "$l" ] && [ -f "$GI_COMMON/packed-refs" ]; then
      while read -r sha name2; do [ "$name2" = "$name" ] && { l="$sha"; break; }; done < "$GI_COMMON/packed-refs"
    fi
    case "$l" in
      'ref: '*) name="${l#ref: }" ;;
      *) REF_SHA="$l"; return 0 ;;
    esac
  done
}
if ! git_dirs; then
  printf 'restore-protected: WARNING %s is not a git work tree; protected files were not checked\n' "$PROJECT_DIR" >&2
  exit 0
fi
read_ref HEAD; C_HEAD="$REF_SHA"
RL=(); [ -f "$GI_DIR/logs/HEAD" ] && mapfile -t RL < "$GI_DIR/logs/HEAD"
C_REFLOG=${#RL[@]}

# ---------- the protected set (real directory entries = canonical names) ----------
P=()
for f in .claude/hooks/**; do [ -f "$f" ] && P+=("$f"); done
for f in .claude/settings.json .claude/settings.local.json docs/PROMPT.md docs/PROMPT_DELIVERY_GATES.md eval/baseline.json; do [ -f "$f" ] && P+=("$f"); done
for f in .env .env.* */.env */.env.*; do
  case "${f,,}" in *.example) continue ;; esac
  [ -f "$f" ] && P+=("$f")
done

declare -A CUR=() OLD=() TRACKED=()
if (( ${#P[@]} > 0 )); then
  if [ "$ZERO" = 1 ]; then
    while IFS= read -r -d '' l; do CUR["${l:66}"]="${l:0:64}"; done < <("${HASH[@]}" "${P[@]}" 2>/dev/null)
  else
    while IFS= read -r l; do CUR["${l:66}"]="${l:0:64}"; done < <("${HASH[@]}" "${P[@]}" 2>/dev/null)
  fi
fi
# tracked <paths...> -> fills TRACKED[path]=1 for paths in the index (one git call)
tracked() {
  (( $# > 0 )) || return 0
  local l
  while IFS= read -r -d '' l; do TRACKED["$l"]=1; done < <(git -c core.quotePath=false ls-files -z -- "$@" 2>/dev/null)
}
hash_of() { local l=""; l="$("${HASH1[@]}" < "$1" 2>/dev/null)"; HASH_OUT="${l:0:64}"; }
# head_hash <path> -> HASH_OUT = SHA-256 of HEAD:<path>, empty when HEAD has no such file
head_hash() {
  local l=""; HASH_OUT=""
  git cat-file -e "HEAD:$1" 2>/dev/null || return 0
  l="$(git show "HEAD:$1" 2>/dev/null | "${HASH1[@]}" 2>/dev/null)"; HASH_OUT="${l:0:64}"
}

# ---------- snapshot I/O (one entry per line, so bash reads it without jq) ----------
esc() { local s="${1//\\/\\\\}"; s="${s//\"/\\\"}"; s="${s//$'\n'/\\n}"; s="${s//$'\r'/\\r}"; ESC="${s//$'\t'/\\t}"; }
unesc() {
  local s="$1" o="" k ch
  if [[ "$s" != *\\* ]]; then UNESC="$s"; return 0; fi
  for (( k = 0; k < ${#s}; k++ )); do
    ch="${s:k:1}"
    if [ "$ch" = '\' ]; then
      k=$((k + 1)); ch="${s:k:1}"
      case "$ch" in n) ch=$'\n' ;; r) ch=$'\r' ;; t) ch=$'\t' ;; esac
    fi
    o+="$ch"
  done
  UNESC="$o"
}
S_OK=0; S_LOCKED=false; S_HEAD=""; S_REFLOG=0
load_snapshot() {
  local l re_f='^    "(([^"\\]|\\.)*)": "([0-9a-f]{64})",?$'
  [ -f "$SNAP" ] || return 1
  while IFS= read -r l || [ -n "$l" ]; do
    l="${l%$'\r'}"
    if [[ "$l" =~ $re_f ]]; then unesc "${BASH_REMATCH[1]}"; OLD["$UNESC"]="${BASH_REMATCH[3]}"
    elif [[ "$l" =~ ^\ \ \"version\":\ 1, ]]; then S_OK=1
    elif [[ "$l" =~ ^\ \ \"locked\":\ (true|false) ]]; then S_LOCKED="${BASH_REMATCH[1]}"
    elif [[ "$l" =~ ^\ \ \"head\":\ \"([0-9a-f]*)\" ]]; then S_HEAD="${BASH_REMATCH[1]}"
    elif [[ "$l" =~ ^\ \ \"reflog\":\ ([0-9]+) ]]; then S_REFLOG="${BASH_REMATCH[1]}"
    fi
  done < "$SNAP"
  [ "$S_OK" = 1 ]
}
write_snapshot() {
  local p sep="" now tmp="$SNAP.$$"
  TZ=UTC0 printf -v now '%(%Y-%m-%dT%H:%M:%SZ)T' -1
  {
    printf '{\n  "version": 1,\n  "created_at": "%s",\n  "mode": "%s",\n  "locked": %s,\n  "head": "%s",\n  "reflog": %s,\n  "files": {\n' \
      "$now" "$mode" "$locked_now" "$C_HEAD" "$C_REFLOG"
    for p in "${!CUR[@]}"; do esc "$p"; printf '%s    "%s": "%s"' "$sep" "$ESC" "${CUR[$p]}"; sep=$',\n'; done
    printf '\n  }\n}\n'
  } > "$tmp" 2>/dev/null && mv -f "$tmp" "$SNAP"
}
# keep the pre-call bytes of tracked protected files (content-addressed, once per version)
backup_tracked() {
  local p b blobs del=() keep
  (( ${#CUR[@]} > 0 )) || return 0
  tracked "${!CUR[@]}"
  [ -d "$BACKUP" ] || mkdir -p "$BACKUP" 2>/dev/null || return 0
  for p in "${!CUR[@]}"; do
    [ -n "${TRACKED[$p]-}" ] || continue
    [ -f "$BACKUP/${CUR[$p]}" ] || cp -p -- "$p" "$BACKUP/${CUR[$p]}" 2>/dev/null
  done
  blobs=("$BACKUP"/*)
  if (( ${#blobs[@]} > 200 )); then
    keep=" ${CUR[*]} "
    for b in "${blobs[@]}"; do [[ "$keep" == *" ${b##*/} "* ]] || del+=("$b"); done
    rm -f -- "${del[@]}"
  fi
}

# ---------- lock state ----------
locked_now=true
if [ -f "$STATE_FILE" ]; then
  st=""; IFS= read -r -d '' st < "$STATE_FILE" || true
  [[ "$st" =~ \"hooks_unlocked\"[[:space:]]*:[[:space:]]*true ]] && locked_now=false
fi
is_truthy "${MASROOF_HOOKS_UNLOCK:-}" && locked_now=false

if [ "$mode" = pre ]; then
  write_snapshot
  backup_tracked
  exit 0
fi

# ---------- post: undo what changed during the call ----------
load_snapshot || { write_snapshot; exit 0; }   # no pre-call state recorded: nothing to compare with
{ [ "$locked_now" = true ] || [ "$S_LOCKED" = true ]; } || { write_snapshot; exit 0; }
changed=()
for p in "${!OLD[@]}"; do [ "${CUR[$p]-}" = "${OLD[$p]}" ] || changed+=("$p"); done
for p in "${!CUR[@]}"; do [ -n "${OLD[$p]+x}" ] || changed+=("$p"); done
(( ${#changed[@]} > 0 )) || { write_snapshot; exit 0; }

head_moved=0; commit_in_cmd=0
if [ -n "$C_HEAD" ] && [ "$C_HEAD" != "$S_HEAD" ]; then
  head_moved=1
  if (( C_REFLOG <= S_REFLOG )); then commit_in_cmd=1   # reflog missing or rewritten: cannot tell
  else
    for (( k = S_REFLOG; k < C_REFLOG; k++ )); do
      msg="${RL[k]#*$'\t'}"
      case "$msg" in
        'checkout: '*|'merge '*|pull*|rebase*|'reset: '*|'branch: '*) ;;
        *) commit_in_cmd=1 ;;
      esac
    done
  fi
fi
tracked "${changed[@]}"
restored=(); reported=(); failed=(); example_hash=""
for p in "${changed[@]}"; do
  new="${CUR[$p]-}"; old="${OLD[$p]-}"
  if [ -z "$old" ] && [ "$p" = .env ] && [ -f .env.example ]; then   # cp .env.example .env / make bootstrap
    [ -z "$example_hash" ] && { hash_of .env.example; example_hash="$HASH_OUT"; }
    [ "$new" = "$example_hash" ] && continue
  fi
  head_hash "$p"; in_head="$HASH_OUT"
  if [ "$head_moved" = 1 ] && [ "$commit_in_cmd" = 0 ] && [ "$new" = "$in_head" ]; then continue; fi   # branch change
  is_tracked=0; { [ -n "${TRACKED[$p]-}" ] || [ -n "$in_head" ]; } && is_tracked=1
  if [ -z "$old" ]; then   # appeared during the call
    [ -n "$in_head" ] && [ "$new" = "$in_head" ] && continue   # re-created exactly as committed
    rm -f -- "$p" 2>/dev/null
    [ -z "$in_head" ] && [ -n "${TRACKED[$p]-}" ] && git rm -q --cached --ignore-unmatch -- "$p" >/dev/null 2>&1
    if [ -e "$p" ]; then failed+=("$p"); else unset 'CUR[$p]'; restored+=("$p (new file removed)"); fi
    continue
  fi
  if [ "$is_tracked" = 0 ]; then   # .env, settings.local.json: never copied, so never restorable
    if [ -n "$new" ]; then reported+=("$p (changed (not restorable))"); else reported+=("$p (deleted (not restorable))"); fi
    continue
  fi
  ok=0
  [[ "$p" == */* ]] && { [ -d "${p%/*}" ] || mkdir -p "${p%/*}"; }
  if [ -f "$BACKUP/$old" ]; then
    hash_of "$BACKUP/$old"
    [ "$HASH_OUT" = "$old" ] && cp -p -- "$BACKUP/$old" "$p" 2>/dev/null && ok=1
  fi
  if [ "$ok" = 0 ] && [ -n "$in_head" ]; then git show "HEAD:$p" > "$p" 2>/dev/null && ok=1 && old="$in_head"; fi
  if [ "$ok" = 1 ]; then
    # the call may have staged its version (git checkout <rev> -- p, git add): put the index back to HEAD
    [ -n "${TRACKED[$p]-}" ] && git reset -q -- "$p" >/dev/null 2>&1
    CUR["$p"]="$old"
    if [ -n "$new" ]; then restored+=("$p (content restored)"); else restored+=("$p (deleted file restored)"); fi
  else
    failed+=("$p")
  fi
done
write_snapshot

if (( ${#restored[@]} + ${#reported[@]} + ${#failed[@]} > 0 )); then
  reason="restore-protected: this call changed protected guardrail files while hooks are locked."
  (( ${#restored[@]} > 0 )) && reason+=" Undone: $(printf '%s; ' "${restored[@]}")"
  (( ${#reported[@]} > 0 )) && reason+=" Untracked, left as they are now: $(printf '%s; ' "${reported[@]}")"
  (( ${#failed[@]} > 0 )) && reason+=" COULD NOT RESTORE: ${failed[*]} - tell the owner."
  if [ "$head_moved" = 1 ] && [ "$commit_in_cmd" = 1 ]; then
    reason+=" HEAD moved by a commit (or an unrecognised ref update) in the same call; the working tree was restored, so it now differs from HEAD - undo that commit, e.g. 'git reset --soft HEAD~1'."
  fi
  reason+=" Do not retry the write another way. Hook/settings edits need 'make hooks-unlock' as its own command (or /retro, /sdlc-selftest), then 'make hooks-lock' and a docs/DECISIONS.md line; agents never write .env."
  log_hook "RESTORE ${restored[*]:-} | reported ${reported[*]:-} | failed ${failed[*]:-}"
  if have_jq; then
    jq -cn --arg r "$reason" --arg e "$event" 'if $e == "PostToolUseFailure" then {decision:"block",reason:$r,hookSpecificOutput:{hookEventName:$e,additionalContext:$r}} else {decision:"block",reason:$r} end'
  else
    "$PYTHON" -c 'import json,sys; r,e=sys.argv[1:3]; o={"decision":"block","reason":r}
if e=="PostToolUseFailure": o["hookSpecificOutput"]={"hookEventName":e,"additionalContext":r}
print(json.dumps(o,separators=(",",":")))' "$reason" "$event"
  fi
fi
exit 0
