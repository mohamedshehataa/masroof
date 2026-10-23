#!/usr/bin/env bash
# PreToolUse(Bash|PowerShell): block destructive or secret-leaking shell commands.
# Exit 2 = block (stderr names the rule and a safe alternative). ASCII-only source.
#
# The command is examined in three views:
#   raw  - newlines turned into ' ; ' (they separate commands), tabs into spaces
#   nq   - raw with every quote character replaced by a space, so text handed to `bash -c "..."`
#          or `pwsh -Command '...'` is parsed like a top-level command (conservative on purpose)
#   segs - nq split into simple commands at ; && || | & ( ) and backticks; each is tokenised and
#          leading wrappers (sudo, env, VAR=x, bash -c, xargs, timeout, ...) are skipped to find
#          the real command, which the per-command checks below inspect.
# Malformed JSON on stdin never blocks: one warning line on stderr and exit 0.
HOOK_NAME=guard-bash
# shellcheck source=lib.sh
# Runs before every shell command, so it avoids forks: string work uses bash expansions and [[ =~ ]],
# and git / cygpath / jq(state) are only called when a rule actually needs them.
_gb_dir="${BASH_SOURCE[0]%/*}"; [ "$_gb_dir" = "${BASH_SOURCE[0]}" ] && _gb_dir=.
# shellcheck source=lib.sh
. "$_gb_dir/lib.sh"
read_input
fields=()
while IFS= read -r -d '' f; do fields+=("$f"); done < <(json_fields '.tool_input.command' '.cwd' '.scratchpad_dir')
if [ "${fields[0]:-}" != ok ]; then
  printf 'guard-bash: WARNING hook input is not valid JSON; the command was not checked (never blocks on a parse error)\n' >&2
  exit 0
fi
cmd="${fields[1]:-}"; cwd="${fields[2]:-}"; scratch="${fields[3]:-}"
[ -z "$cmd" ] && exit 0

raw="${cmd//$'\r'/ }"; raw="${raw//$'\t'/ }"; raw="${raw//$'\n'/ ; } ; "
lc="${raw,,}"
nq="${raw//\"/ }"; nq="${nq//\'/ }"

# lazily computed (each costs a fork)
root=""; root_l=""; tmp_l=""; scratch_l=""
paths_init() {
  [ -n "$root" ] && return 0
  root="$(to_posix_path "$PROJECT_DIR")"; root="${root%/}"; root_l="${root,,}"
  tmp_l="$(to_posix_path /tmp)"; tmp_l="${tmp_l,,}"; tmp_l="${tmp_l%/}"
  if [ -n "$scratch" ]; then scratch_l="$(to_posix_path "$scratch")"; scratch_l="${scratch_l,,}"; scratch_l="${scratch_l%/}"; fi
}
release_active=""
release_is_active() {
  if [ -z "$release_active" ]; then
    release_active=no
    is_truthy "$(state_get release_active)" && release_active=yes
    [ -n "${MASROOF_RELEASE_ACTIVE:-}" ] && release_active=yes
  fi
  [ "$release_active" = yes ]
}
# PROD_HOST / STAGING_HOST from the project's .env (read with bash, never printed)
prod_hosts=()
if [ -f "$PROJECT_DIR/.env" ]; then
  while IFS='=' read -r _k _v; do
    case "$_k" in
      PROD_HOST|STAGING_HOST) _v="${_v//\"/}"; _v="${_v//$'\r'/}"; _v="${_v%%[[:space:]#]*}"; [ -n "$_v" ] && prod_hosts+=("$_v") ;;
    esac
  done < "$PROJECT_DIR/.env"
fi
_unlocked=""
hooks_unlocked() {
  if [ -z "$_unlocked" ]; then
    _unlocked=no
    is_truthy "$(state_get hooks_unlocked)" && _unlocked=yes
    is_truthy "${MASROOF_HOOKS_UNLOCK:-}" && _unlocked=yes
  fi
  [ "$_unlocked" = yes ]
}

# ---------- path helpers ----------
is_abs() { case "$1" in /*|[A-Za-z]:|[A-Za-z]:[/\\]*|\\\\*) return 0 ;; esac; return 1; }
has_dotdot() { local t="/${1//\\//}/"; [[ "$t" == */../* ]]; }
# safe_abs <abs path> -> 0 when inside the repo, /tmp or the scratchpad (case-insensitive)
safe_abs() {
  local p
  paths_init
  p="$(to_posix_path "$1")"; p="${p,,}"
  case "$p" in
    "$root_l"/*|"$tmp_l"/*|/tmp/*|/var/tmp/*|/dev/shm/*) return 0 ;;
  esac
  if [ -n "$scratch_l" ]; then case "$p" in "$scratch_l"/*) return 0 ;; esac; fi
  return 1
}
# is_env_path <token> -> 0 for .env / .env.<x> / .env* (any directory), never for *.example
is_env_path() {
  local b="${1//\\//}"
  b="${b##*/}"; b="${b,,}"
  case "$b" in
    *.example) return 1 ;;
    .env|.env.*|'.env*') return 0 ;;
  esac
  return 1
}
# is_guarded_path <token> -> 0 for the locked guardrail files of THIS checkout
is_guarded_path() {
  local t="${1//\\//}" p
  case "${t,,}" in
    *.claude/hooks|*.claude/hooks/*|*.claude/settings.json|*.claude/settings.local.json|*docs/prompt.md) ;;
    *) return 1 ;;
  esac
  if is_abs "$t"; then
    paths_init
    p="$(to_posix_path "$t")"
    case "${p,,}" in "$root_l"/*) return 0 ;; *) return 1 ;; esac   # another checkout or a temp repo
  fi
  return 0
}
is_local_host() {
  case "${1,,}" in
    ''|localhost|127.0.0.1|::1|'[::1]'|github.com|ssh.github.com) return 0 ;;
  esac
  return 1
}
current_branch() { git -C "${1:-${cwd:-$PROJECT_DIR}}" symbolic-ref --short HEAD 2>/dev/null || true; }

# ---------- per-command checks ----------
outside_cd=""   # set when an earlier `cd` in this command left the repo (relative deletes are then unsafe)

del_check() { # del_check <verb> <arg>
  local v="$1" a="$2"
  [ -z "$a" ] && return 0
  # shellcheck disable=SC2016
  case "$a" in
    /|'/*'|'/.'*|'~'*|'$HOME'*|'${HOME}'*|'$USERPROFILE'*|'${USERPROFILE}'*|'$env:USERPROFILE'*|'$env:HOMEPATH'*|'$env:HOMEDRIVE'*|'%USERPROFILE%'*|'%HOMEPATH%'*|'*'|'.*'|.|./|'./*'|.git|.git/|'.git/'*|'$CLAUDE_PROJECT_DIR'*|'${CLAUDE_PROJECT_DIR}'*|'$PROJECT_DIR'*|'${PROJECT_DIR}'*|[A-Za-z]:|[A-Za-z]:/|[A-Za-z]:\\|/[A-Za-z]|/[A-Za-z]/)
      block "refusing '$v ... $a' - it deletes the repo, your home or a whole drive. Safe alternative: delete a specific path inside the repo (e.g. 'rm -rf build/'), under /tmp or in the scratchpad" ;;
  esac
  if has_dotdot "$a"; then
    block "refusing '$v ... $a' - the path climbs out with '..'. Safe alternative: name the target directly (a path inside the repo, /tmp or the scratchpad)"
  fi
  if is_abs "$a"; then
    safe_abs "$a" || block "refusing '$v ... $a' - absolute path outside the repo ($root), /tmp or the scratchpad. Safe alternative: delete inside the repo or the scratchpad, or ask the owner to remove it"
    return 0
  fi
  if [ -n "$outside_cd" ]; then
    block "refusing '$v ... $a' after 'cd $outside_cd' - a relative delete outside the repo. Safe alternative: use a path inside the repo, /tmp or the scratchpad without changing directory first"
  fi
}

check_rm() { # unix rm (also the PowerShell alias): recursive deletes only
  local recursive=0 endopts=0 a targets=()
  for a in "$@"; do
    if [ "$endopts" = 0 ]; then
      case "$a" in
        '#'*) break ;;
        --) endopts=1; continue ;;
        --recursive) recursive=1; continue ;;
        --*) continue ;;
        -*) [[ "${a:1}" == *[rR]* ]] && recursive=1; continue ;;
      esac
    fi
    targets+=("$a")
  done
  [ "$recursive" = 1 ] || return 0
  for a in "${targets[@]+"${targets[@]}"}"; do del_check rm "$a"; done
}

check_ps_del() { # <verb> args: Remove-Item/ri/del/erase -Recurse, rmdir/rd /s
  local v="$1" recursive=0 a targets=()
  shift
  for a in "$@"; do
    case "${a,,}" in
      '#'*) break ;;
      /s) recursive=1 ;;
      /q|/f|/a*|/p) ;;
      -r*) recursive=1 ;;
      -*) ;;
      *) targets+=("$a") ;;
    esac
  done
  [ "$recursive" = 1 ] || return 0
  for a in "${targets[@]+"${targets[@]}"}"; do del_check "$v" "$a"; done
}

check_cd() {
  local a="" t
  for t in "$@"; do case "$t" in '#'*) break ;; -*) continue ;; *) a="$t"; break ;; esac; done
  # shellcheck disable=SC2016
  case "$a" in
    ''|'~'*|'$HOME'*|'${HOME}'*|'$env:'*|'%'*|-) outside_cd="${a:-~}"; return 0 ;;
  esac
  if is_abs "$a"; then
    if safe_abs "${a%/}/x"; then outside_cd=""; else outside_cd="$a"; fi   # the dir itself may be /tmp
  elif has_dotdot "$a"; then
    outside_cd="$a"
  fi
}

check_push() { # <git dir> push-args...
  local gdir="$1" force=0 del=0 all=0 skipnext=0 t cl k ch pos=() refs=() r plus src dst cb
  shift
  for t in "$@"; do
    if [ "$skipnext" = 1 ]; then skipnext=0; continue; fi
    case "$t" in
      '#'*) break ;;
      --) continue ;;
      --force*) force=1 ;;
      --delete) del=1 ;;
      --all|--mirror|--branches) all=1 ;;
      --push-option|--repo|--receive-pack|--exec) skipnext=1 ;;
      --*) ;;
      -*)
        cl="${t:1}"
        for (( k = 0; k < ${#cl}; k++ )); do
          ch="${cl:k:1}"
          case "$ch" in
            f) force=1 ;;
            d) del=1 ;;
            o) [ $((k + 1)) -eq ${#cl} ] && skipnext=1; break ;;
          esac
        done ;;
      *) pos+=("$t") ;;
    esac
  done
  refs=("${pos[@]:1}")
  cb="$(current_branch "$gdir")"
  if [ "${#refs[@]}" -eq 0 ]; then
    if [ "$all" = 1 ]; then refs=(main); else refs=("${cb:-}"); fi
  fi
  for r in "${refs[@]}"; do
    plus=0
    case "$r" in +*) plus=1; r="${r#+}" ;; esac
    if [[ "$r" == *:* ]]; then src="${r%%:*}"; dst="${r#*:}"; else src="$r"; dst="$r"; fi
    dst="${dst#refs/heads/}"
    case "$dst" in HEAD|@) dst="$cb" ;; esac
    if { [ "$del" = 1 ] || { [ -z "$src" ] && [[ "$r" == :* ]]; }; } && { [ "$dst" = main ] || [ "$dst" = master ]; }; then
      block "deleting '$dst' on the remote is forbidden. Safe alternative: delete story branches only"
    fi
    if [ "$force" = 1 ] || [ "$plus" = 1 ]; then
      case "$dst" in
        main|master|"") block "force-push to '${dst:-the current branch (unknown)}' is forbidden (--force, -f in a flag cluster, a '+' refspec or HEAD on main); main has linear history and is protected. Safe alternative: rebase a story/* branch and 'git push --force-with-lease origin story/<id>'" ;;
      esac
    fi
  done
}

check_reset() { # <git dir> reset-args...
  local gdir="$1" hard=0 t against=0 cb
  shift
  for t in "$@"; do
    case "$t" in
      '#'*) break ;;
      --hard) hard=1 ;;
      -*) ;;
      *main*|*master*) against=1 ;;
    esac
  done
  [ "$hard" = 1 ] || return 0
  cb="$(current_branch "$gdir")"
  if [ "$cb" = main ] || [ "$cb" = master ] || [ "$against" = 1 ]; then
    block "git reset --hard on/against main is forbidden (current branch: ${cb:-unknown}). Safe alternative: work on a story/* branch, or 'git stash' / 'git restore <file>'"
  fi
}

check_commit_n() { # git commit args: -n (= --no-verify) only counts among the flags before the message
  local t cl k ch
  for t in "$@"; do
    case "$t" in
      '#'*|--) return 0 ;;
      --message*|--file*|--reuse-message*|--reedit-message*|--template*) return 0 ;;
      --*) ;;
      -*)
        cl="${t:1}"
        for (( k = 0; k < ${#cl}; k++ )); do
          ch="${cl:k:1}"
          case "$ch" in
            n) block "bypassing git hooks with 'git commit -n' is forbidden. Safe alternative: fix what the hook reports, then commit normally" ;;
            m|F|C|c|t) return 0 ;;
          esac
        done ;;
    esac
  done
}

check_git() {
  local gdir="${cwd:-$PROJECT_DIR}" j=0 n=$# sub="" args=("$@") t
  for t in "${args[@]}"; do
    case "$t" in
      --no-verb*) ;;
      --no-v*) block "bypassing git hooks ('$t' is git's abbreviation of --no-verify) is forbidden. Safe alternative: fix what the hook reports and commit/push normally" ;;
    esac
  done
  while (( j < n )); do
    t="${args[j]}"
    case "$t" in
      -C) j=$((j + 1)); t="${args[j]:-.}"; if is_abs "$t"; then gdir="$t"; else gdir="$gdir/$t"; fi ;;
      -c|--git-dir|--work-tree|--namespace|--config-env|--exec-path) j=$((j + 1)) ;;
      -*) ;;
      *) sub="$t"; break ;;
    esac
    j=$((j + 1))
  done
  [ -n "$sub" ] || return 0
  case "$sub" in
    push) check_push "$gdir" "${args[@]:j+1}" ;;
    reset) check_reset "$gdir" "${args[@]:j+1}" ;;
    commit) check_commit_n "${args[@]:j+1}" ;;
    branch)
      local destructive=0 names=0
      for t in "${args[@]:j+1}"; do
        case "$t" in
          '#'*) break ;;
          -D|-d|-f|-M|-m|-C|-c|--delete|--force|--move|--copy) destructive=1 ;;
          main|master) names=1 ;;
        esac
      done
      if [ "$destructive" = 1 ] && [ "$names" = 1 ]; then
        block "rewriting/deleting the main branch pointer is forbidden. Safe alternative: create or rename a story/* branch instead"
      fi ;;
  esac
}

check_remote() { # <verb> args: ssh/scp/sftp/rsync to anything but github.com/localhost needs /release
  local v="$1" a host="" skip=0 rhosts=()
  shift
  release_is_active && return 0
  case "$v" in
    ssh|mosh|sftp)
      for a in "$@"; do
        if [ "$skip" = 1 ]; then skip=0; continue; fi
        case "$a" in
          '#'*) break ;;
          -?) case "${a:1}" in [BbcDEeFIiJLlmOoPpQRSWw]) skip=1 ;; esac; continue ;;
          -*) continue ;;
          *) host="$a"; break ;;
        esac
      done
      host="${host##*@}"; host="${host%%:*}"
      rhosts+=("$host") ;;
    *)
      for a in "$@"; do
        case "$a" in '#'*) break ;; -*) continue ;; [A-Za-z]:[/\\]*) continue ;; esac
        if [[ "$a" =~ ^([^/@:]+@)?([^/:@]+): ]]; then rhosts+=("${BASH_REMATCH[2]}"); fi
      done ;;
  esac
  for host in "${rhosts[@]+"${rhosts[@]}"}"; do
    is_local_host "$host" || block "$v to the remote host '$host' is only allowed through the /release skill (make deploy-*). Safe alternative: run the make target, or ask the owner"
  done
}

check_write_target() { # <verb> <target>
  local v="$1" t="${2#of=}"
  [ -z "$t" ] && return 0
  if is_env_path "$t"; then
    block "writing '$t' with $v is forbidden - .env holds the owner's secrets. Safe alternative: edit .env.example (placeholders only) and ask the owner to update .env"
  fi
  if is_guarded_path "$t" && ! hooks_unlocked; then
    block "'$t' is a protected guardrail file (hooks/settings/settings.local/PROMPT) and $v would change it. Safe alternative: 'make hooks-unlock' (or /retro, /sdlc-selftest), change it, 'make hooks-lock', and note it in docs/DECISIONS.md"
  fi
}

check_writes() { # <verb> args: shell commands that create, overwrite, move or delete files
  local v="$1" a inplace=0 nonflag=() last k
  shift
  for a in "$@"; do
    case "$a" in
      '#'*) break ;;
      --in-place*) inplace=1 ;;
      --*) ;;
      -*) [[ "$a" =~ ^-[A-Za-z]*i ]] && inplace=1 ;;
      *) nonflag+=("$a") ;;
    esac
  done
  [ "${#nonflag[@]}" -gt 0 ] || return 0
  case "$v" in
    sed|perl) [ "$inplace" = 1 ] || return 0 ;;
  esac
  case "$v" in
    cp|install|copy-item|cpi|copy|xcopy|robocopy)
      last="${nonflag[${#nonflag[@]}-1]}"
      local all_examples=1
      for (( k = 0; k < ${#nonflag[@]} - 1; k++ )); do
        a="${nonflag[k]}"
        if is_env_path "$a"; then
          block "copying '$a' with $v spreads the owner's secrets. Safe alternative: reference variables by name, or copy .env.example"
        fi
        case "${a,,}" in *.example) ;; *) all_examples=0 ;; esac
      done
      if [ "$all_examples" = 1 ] && [ "${#nonflag[@]}" -ge 2 ] && is_env_path "$last"; then
        is_guarded_path "$last" && ! hooks_unlocked && check_write_target "$v" "$last"
        return 0   # cp .env.example .env is the documented bootstrap
      fi
      check_write_target "$v" "$last" ;;
    *)
      for a in "${nonflag[@]}"; do check_write_target "$v" "$a"; done ;;
  esac
}

check_env_listing() { # <verb> args: printenv / env / set / export without arguments dump every secret
  local v="$1" a has=0
  shift
  for a in "$@"; do case "$a" in '#'*) break ;; -*) ;; *) has=1 ;; esac; done
  case "$v" in
    printenv)
      local all="${*,,}"
      if [ "$has" = 0 ] || [[ "$all" =~ (key|secret|token|password|passwd|passphrase) ]]; then
        block "printing environment variables that may hold secrets is forbidden. Safe alternative: 'printenv HOME' (one non-secret name), or check presence with '[ -n \"\$NAME\" ] && echo set'"
      fi ;;
    set|export|declare|typeset)
      [ "$has" = 0 ] && block "dumping the whole environment ('$v' without arguments) is forbidden - it contains secrets. Safe alternative: print one non-secret variable by name" ;;
    get-childitem|gci|dir|ls|get-item|gi)
      for a in "$@"; do
        case "${a,,}" in
          env:|env:\\|env:/|'env:*'|'env:\*'|env:*key*|env:*secret*|env:*token*|env:*password*)
            block "listing Env: in PowerShell dumps secrets. Safe alternative: read one non-secret variable, e.g. \$env:PATH" ;;
        esac
      done ;;
  esac
}

# ---------- walk the simple commands ----------
segs="${nq//&&/$'\n'}"; segs="${segs//||/$'\n'}"
segs="${segs//;/$'\n'}"; segs="${segs//&/$'\n'}"; segs="${segs//|/$'\n'}"
segs="${segs//(/$'\n'}"; segs="${segs//)/$'\n'}"; segs="${segs//\`/$'\n'}"
set -f
while IFS= read -r seg; do
  # shellcheck disable=SC2206
  toks=($seg)
  n=${#toks[@]}; i=0; wrap=0; lastwrap=""; valnext=0
  while (( i < n )); do
    t="${toks[i]}"; tl="${t,,}"
    if [ "$valnext" = 1 ]; then valnext=0; i=$((i + 1)); continue; fi
    if [[ "$t" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then i=$((i + 1)); continue; fi
    case "$tl" in
      '#'*) i=$n; break ;;
      sudo|doas|exec|command|builtin|nohup|time|nice|ionice|stdbuf|env|*/env|xargs|then|do|else|elif|if|while|until|'!'|'{'|'}'|eval|timeout|watch|call|sshpass|start-process|invoke-command)
        wrap=1; lastwrap="${tl##*/}" ;;
      -u|-g|-h|-p|-c|-d|-r|-t|-s|-k|-i|-n|-l|-e|-a|-o|-T)
        [ "$wrap" = 1 ] || break
        case "$lastwrap:$tl" in
          sudo:-u|sudo:-g|sudo:-h|sudo:-p|sudo:-c|sudo:-d|sudo:-r|sudo:-t|sudo:-T) valnext=1 ;;
          xargs:-i|xargs:-n|xargs:-p|xargs:-d|xargs:-l|xargs:-e|xargs:-a|xargs:-s) valnext=1 ;;
          timeout:-s|timeout:-k|env:-u|env:-c|nice:-n|ionice:-c|ionice:-n|stdbuf:-o|stdbuf:-e|stdbuf:-i|sshpass:-p) valnext=1 ;;
        esac ;;
      -*) [ "$wrap" = 1 ] || break ;;
      [0-9]*) [ "$wrap" = 1 ] || break ;;
      bash|sh|zsh|dash|ksh|ash|*/bash|*/sh|*/zsh|*/dash|pwsh|pwsh.exe|powershell|powershell.exe|cmd|cmd.exe)
        case "${toks[i+1]:-}" in
          -c|-[a-z]*c|-[A-Za-z]*command|-NoProfile|-noprofile|-NonInteractive|/c|/C|/k|/K) wrap=1; lastwrap=shell ;;
          *) break ;;
        esac ;;
      *) break ;;
    esac
    i=$((i + 1))
  done
  if (( i >= n )); then
    [ "$lastwrap" = env ] && block "dumping the whole environment ('env' without a command) is forbidden - it contains secrets. Safe alternative: print one non-secret variable by name"
    continue
  fi
  c="${toks[i]}"; base="${c,,}"; base="${base##*/}"; base="${base%.exe}"
  args=("${toks[@]:i+1}")
  case "$base" in
    rm) check_rm "${args[@]+"${args[@]}"}" ;;
    remove-item|ri|del|erase|rd|rmdir) check_ps_del "$c" "${args[@]+"${args[@]}"}" ;;
    cd|pushd|set-location|sl|chdir) check_cd "${args[@]+"${args[@]}"}" ;;
    git) check_git "${args[@]+"${args[@]}"}" ;;
    ssh|scp|sftp|rsync|mosh) check_remote "$base" "${args[@]+"${args[@]}"}" ;;
    printenv|set|export|declare|typeset|get-childitem|gci|dir|ls|get-item|gi) check_env_listing "$base" "${args[@]+"${args[@]}"}" ;;
    chmod)
      for a in "${args[@]+"${args[@]}"}"; do
        if [[ ",$a," =~ ^,([0-7]?[0-7][0-7][2367]), ]] || [[ ",$a," =~ ,[ugo]*[ao][ugo]*[+=][rwxXst]*w ]]; then
          block "chmod '$a' makes files world-writable, which is forbidden. Safe alternative: 'chmod +x <script>' for executables, 755 for directories, 644 for files"
        fi
      done ;;
  esac
  case "$base" in
    tee|tee-object|sed|perl|cp|install|copy-item|cpi|copy|xcopy|robocopy|mv|move-item|mi|move|ren|rename|rename-item|rni|rm|del|erase|remove-item|ri|unlink|rmdir|rd|chmod|chown|chgrp|truncate|touch|ln|set-content|sc|add-content|ac|out-file|clear-content|clc|new-item|ni|shred|dd)
      check_writes "$base" "${args[@]+"${args[@]}"}" ;;
  esac
done <<< "$segs"
set +f

# ---------- redirections onto protected files or .env ----------
if [[ "$nq" == *'>'* ]]; then
  s="$nq"; re_redir='>>?\|? *([^ ;&|<>()]+)'
  while [[ "$s" =~ $re_redir ]]; do
    check_write_target "redirection" "${BASH_REMATCH[1]}"
    s="${s#*"${BASH_REMATCH[0]}"}"
  done
fi

# ---------- hook bypass via config / env ----------
re_bypass='core\.hookspath|lefthook=0|lefthook_exclude|--no-verify'
if [[ "$lc" =~ $re_bypass ]]; then
  block "bypassing git hooks (--no-verify / core.hooksPath / LEFTHOOK=0) is forbidden. Safe alternative: fix what the hook reports, then commit normally"
fi

# ---------- pipe-to-shell ----------
dl='(curl|wget|iwr|irm|invoke-webrequest|invoke-restmethod)'
shl='(sudo( +-[a-z-]+)* +)?(env +([a-z_]+=[^ ]* +)*)?([^ |;&]*/)?((ba|z|da|k|a)?sh|python[0-9.]*|perl|ruby|node|php)'
re_pipe="$dl"'[^|;&]*\| *'"$shl"'( |$|-|;)|\| *(ba)?sh +-s|((ba|z|da|k)?sh|source|\.)( +-[a-z]+)* +<\( *'"$dl"'|((ba|z|da|k)?sh|eval)( +-[a-z]+)* +["'"'"']?\$\( *'"$dl"'|iex *\(|\| *iex( |$|;)|invoke-expression'
if [[ "$lc" =~ $re_pipe ]]; then
  block "piping a download into a shell or interpreter is forbidden. Safe alternative: download to a file, inspect it, then run it"
fi

# ---------- DROP/TRUNCATE against a non-local database (needs a DB client in the command) ----------
re_drop='(^|[^a-z])(drop +(table|database|schema|index|owned|role|user)|truncate)([^a-z]|$)'
re_dbc='(^|[^a-z0-9_-])(psql|pgcli|mysql|mariadb|sqlite3|sqlcmd|mongosh|cqlsh|clickhouse-client)([^a-z0-9_-]|$)'
if [[ "$lc" =~ $re_drop ]] && [[ "$lc" =~ $re_dbc ]]; then
  hosts="$(
    printf '%s' "$raw" | grep -oE '(^| )(-h|--host)(=| *)[^ ;&|"'"'"']+' | sed -E 's/^ ?(-h|--host)(=| *)//' || true
    printf '%s' "$raw" | grep -oE '(postgres(ql)?|mysql|mariadb)://[^ ;&|"'"'"'/?]+' | sed -E 's#^[a-z]+://##; s#^.*@##' || true
    printf '%s' "$raw" | grep -oE '(^|[ "'"'"'])host=[^ ;&|"'"'"']+' | sed -E 's/^.*host=//' || true
    printf '%s' "$raw" | grep -oE '(PGHOST|MYSQL_HOST|DB_HOST|DATABASE_HOST)=[^ ;&|"'"'"']+' | sed -E 's/^[A-Z_]+=//' || true
    if [[ "$lc" =~ (^|[^a-z])sqlcmd([^a-z]|$) ]]; then
      printf '%s' "$raw" | grep -oE '(^| )-S *[^ ;&|"'"'"']+' | sed -E 's/^ ?-S *//' || true
    fi
  )"
  while IFS= read -r h; do
    h="${h%%:*}"; h="${h%%,*}"
    [ -z "$h" ] && continue
    case "${h,,}" in
      localhost|127.0.0.1|::1|'[::1]'|0.0.0.0|postgres|db|host.docker.internal|/*) : ;;
      *) block "DROP/TRUNCATE against the non-local database host '$h' is forbidden. Safe alternative: run it against localhost / the compose 'db' service, or write a Flyway migration" ;;
    esac
  done <<< "$hosts"
  for ph in "${prod_hosts[@]+"${prod_hosts[@]}"}"; do
    [[ "$lc" == *"${ph,,}"* ]] && block "DROP/TRUNCATE mentions a production/staging host ($ph). Safe alternative: use a local database"
  done
fi

# ---------- docker destruction ----------
re_docker='docker +system +prune[^;&|]* (-a|--all)|docker +volume +prune|docker +volume +rm'
if [[ "$lc" =~ $re_docker ]]; then
  block "docker system prune -a / volume prune / volume rm destroy local data for every project. Safe alternative: 'make down' or remove one named container"
fi

# ---------- printing secrets ----------
nq_l="${nq,,}"
re_echo='(echo|printf|write-host|write-output) +[^;&|]*(\$env:|\$\{?)[a-z0-9_]*(key|secret|token|password|passwd|passphrase)[a-z0-9_]*|getenvironmentvariables *\('
if [[ "$nq_l" =~ $re_echo ]]; then
  block "echoing a variable whose name looks like a secret is forbidden. Safe alternative: check presence only, e.g. '[ -n \"\$NAME\" ] && echo set'"
fi
# .env readers: any .env / .env.<x> / .env* token (quoted, globbed or inside code) plus a reader command
if [[ "$lc" == *.env* ]]; then
  envscan="$raw"
  re_cpex='(^|[;&|( ])cp +(-[A-Za-z]+ +)*[^ ;&|]*\.env\.example +[^ ;&|]*\.env[^ ;&|]*'
  while [[ "$envscan" =~ $re_cpex ]]; do envscan="${envscan/"${BASH_REMATCH[0]}"/ }"; done
  envfound=0
  re_envtok='(^|[^A-Za-z0-9_.$-])(\.[Ee][Nn][Vv](\.[A-Za-z0-9_-]+)*(\.?\*)?)([^A-Za-z0-9_-]|$)'
  while [[ "$envscan" =~ $re_envtok ]]; do
    tok="${BASH_REMATCH[2],,}"
    case "$tok" in *.example) ;; *) envfound=1; break ;; esac
    envscan="${envscan#*"${BASH_REMATCH[0]}"}"
  done
  re_reader='(^|[^a-z0-9_.-])(cat|less|more|head|tail|grep|egrep|fgrep|rg|ag|awk|gawk|sed|cut|bat|batcat|type|strings|xxd|od|hexdump|base64|tee|nl|paste|sort|uniq|diff|cmp|python[0-9.]*|py|node|deno|bun|perl|ruby|php|get-content|gc|select-string|sls|findstr|curl|wget|nc|jq|yq)([^a-z0-9_-]|$)|open *\('
  if [ "$envfound" = 1 ] && [[ "$lc" =~ $re_reader ]]; then
    block "reading .env files prints secrets. Safe alternative: read .env.example, or reference variables by name"
  fi
fi

# ---------- production / remote hosts outside /release ----------
for ph in "${prod_hosts[@]+"${prod_hosts[@]}"}"; do
  if [[ "$lc" == *"${ph,,}"* ]] && ! release_is_active; then
    block "command targets the production/staging host '$ph' but no /release is active. Safe alternative: run it through the /release skill (make deploy-*)"
  fi
done

exit 0
