#!/usr/bin/env bash
# PreToolUse(Bash|PowerShell): REQ-OPS-001 - block the enumerated destructive and secret-leaking command
# classes B1-B14 of STORY-001. Exit 2 = block (stderr names the rule and a safe alternative). ASCII-only.
# restore-protected.sh (REQ-OPS-011) is the deterministic backstop for protected files; this is the
# first, friendlier line.
#
# How a command is read:
#   1. heredoc bodies are dropped (they are data, REQ-OPS-001 non-goal 3; the `<<EOF` line itself stays)
#   2. quoted strings become one word: the quotes go and the separators, spaces, redirections and '#'
#      inside them are encoded (\001..\021), so `git commit -m "fix: the -n flag"` and
#      `grep -rn "--no-verify" docs/` are data, not structure. Commands over 20000 chars skip this step
#      (quotes become spaces: conservative).
#   3. the encoded text is split into pipelines (; && || & newline) and simple commands (also | ( ) `);
#      wrappers (sudo, env, VAR=x, xargs, timeout, cmd /c, ...) are skipped, and `bash|sh -c "<cmd>"`,
#      `pwsh -Command <cmd>` and `eval <cmd>` are analysed again as commands of their own.
#   4. `cd`/`pushd`/`Set-Location` move the directory later relative paths resolve against; paths are
#      canonicalised as in protect-files (lib.sh to_posix_path: \ and //, . and .., case, \\?\ and
#      \\.\, ::$DATA, 8.3 short names).
# Malformed JSON on stdin never blocks: one warning line on stderr and exit 0.
HOOK_NAME=guard-bash
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

# ---------- lazily computed state (each costs a fork) ----------
root=""; root_l=""; tmp_l=""; scratch_l=""; start_abs=""
paths_init() {
  [ -n "$root" ] && return 0
  to_posix_path_v "$PROJECT_DIR"; root="${TPP%/}"; root_l="${root,,}"
  if [ -n "$scratch" ]; then to_posix_path_v "$scratch"; scratch_l="${TPP,,}"; scratch_l="${scratch_l%/}"; fi
  to_posix_path_v "${cwd:-$PROJECT_DIR}"; start_abs="${TPP%/}"
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

# ---------- quoting: encode quoted text as one word ----------
SP=$'\001'
enc_body() { # enc_body <text> -> EB (separators inside quotes made inert)
  local s="$1"
  s="${s// /$SP}"; s="${s//$'\t'/$SP}"; s="${s//$'\n'/$SP}"; s="${s//$'\r'/$SP}"
  s="${s//;/$'\002'}"; s="${s//&/$'\003'}"; s="${s//|/$'\004'}"; s="${s//(/$'\005'}"; s="${s//)/$'\006'}"
  s="${s//\`/$'\007'}"; s="${s//>/$'\016'}"; s="${s//</$'\017'}"; s="${s//#/$'\020'}"
  [ -z "$s" ] && s=$'\021'
  EB="$s"
}
dec() { # dec <word> -> DEC (the original text)
  local s="$1"
  s="${s//$SP/ }"; s="${s//$'\002'/;}"; s="${s//$'\003'/&}"; s="${s//$'\004'/|}"; s="${s//$'\005'/(}"
  s="${s//$'\006'/)}"; s="${s//$'\007'/\`}"; s="${s//$'\016'/>}"; s="${s//$'\017'/<}"; s="${s//$'\020'/#}"; s="${s//$'\021'/}"
  DEC="$s"
}
encode() { # encode <command> -> ENC
  local s="$1" out="" pre q body part bs
  if (( ${#s} > 20000 )); then ENC="${s//\"/ }"; ENC="${ENC//\'/ }"; return 0; fi
  while :; do
    pre="${s%%[\"\']*}"
    if [ "$pre" = "$s" ]; then out+="$s"; break; fi
    out+="$pre"; s="${s:${#pre}}"; q="${s:0:1}"; s="${s:1}"
    if [ "$q" = "'" ]; then
      body="${s%%\'*}"; s="${s:${#body}}"; s="${s#\'}"
    else
      body=""
      while :; do
        if [[ "$s" != *\"* ]]; then body+="$s"; s=""; break; fi
        part="${s%%\"*}"; s="${s:${#part}+1}"
        bs="${part##*[!\\]}"   # the backslashes right before this quote: odd = the quote is escaped
        if (( ${#bs} % 2 == 1 )); then body+="${part%\\}\""; else body+="$part"; break; fi
      done
      body="${body//\\\\/\\}"
    fi
    enc_body "$body"; out+="$EB"
  done
  ENC="$out"
}
strip_heredocs() { # strip_heredocs <command> -> HD (heredoc bodies removed)
  local line out="" delim="" t re='<<-?[[:space:]]*\\?["'\'']?([A-Za-z_][A-Za-z0-9_]*)'
  if [[ "$1" != *'<<'* ]]; then HD="$1"; return 0; fi
  while IFS= read -r line || [ -n "$line" ]; do
    if [ -n "$delim" ]; then
      t="${line#"${line%%[!$'\t' ]*}"}"; t="${t%$'\r'}"
      [ "$t" = "$delim" ] && delim=""
      continue
    fi
    out+="$line"$'\n'
    if [[ "$line" != *'<<<'* ]] && [[ "$line" =~ $re ]]; then delim="${BASH_REMATCH[1]}"; fi
  done <<< "$1"
  HD="$out"
}

# ---------- paths ----------
is_abs() { case "$1" in /*|[A-Za-z]:|[A-Za-z]:[/\\]*|\\\\*) return 0 ;; esac; return 1; }
cur_abs=""; cur_unknown=""; cd_seen=""
# resolve <token> -> R_ABS (canonical absolute), R_IN=1 + R_REL when inside the repo ('.' = the root)
resolve() {
  local t="$1" a
  paths_init
  R_ABS=""; R_REL=""; R_IN=0
  # shellcheck disable=SC2088
  case "$t" in '~') t="$HOME" ;; '~/'*) t="$HOME/${t:2}" ;; esac
  if is_abs "$t"; then to_posix_path_v "$t"; R_ABS="$TPP"
  else
    [ -n "$cur_unknown" ] && return 1
    if [[ "$t" == *'~'* ]]; then to_posix_path_v "${cur_abs:-$start_abs}/$t"; R_ABS="$TPP"
    else norm_lex "${cur_abs:-$start_abs}/$t"; R_ABS="$NORM"; fi
  fi
  [[ "$R_ABS" == ?*/ ]] && R_ABS="${R_ABS%/}"
  a="${R_ABS,,}"
  if [ "$a" = "$root_l" ]; then R_IN=1; R_REL=.
  elif [[ "$a" == "$root_l"/* ]]; then R_IN=1; R_REL="${R_ABS:${#root}+1}"; fi
  return 0
}
safe_abs() { # safe_abs <lower-case canonical abs> -> 0 inside /tmp or the scratchpad
  if [ -z "$tmp_l" ]; then to_posix_path_v /tmp; tmp_l="${TPP,,}"; tmp_l="${tmp_l%/}"; fi
  case "$1" in "$tmp_l"/?*|/tmp/?*|/var/tmp/?*|/dev/shm/?*) return 0 ;; esac
  if [ -n "$scratch_l" ]; then case "$1" in "$scratch_l"/?*) return 0 ;; esac; fi
  return 1
}
# is_env_path <token> -> 0 for .env / .env.<x> (any directory, ::$DATA and trailing dots ignored) and for
# a glob that a shell would expand to .env (.env*, .e?v, .[e]nv); never for *.example
is_env_path() {
  local b="${1//\\//}"
  b="${b%/}"; b="${b##*/}"; b="${b%%:*}"; b="${b,,}"
  while [[ "$b" == *[.\ ] && "$b" =~ [^.] && "$b" != .env. ]]; do b="${b%?}"; done
  case "$b" in *.example) return 1 ;; .env|.env.*) return 0 ;; esac
  if [[ "$b" == .* && "$b" == *[\*\?\[]* ]]; then
    # shellcheck disable=SC2053
    if [[ .env == $b ]] || [[ .env.local == $b ]]; then return 0; fi
  fi
  return 1
}
# guarded_target <token> [ancestors] -> 0 when the token names a locked guardrail file of THIS checkout
# (B13 set: .claude/hooks/**, settings.json, settings.local.json, docs/PROMPT.md, eval/baseline.json);
# with "ancestors", also a directory holding them (.claude, docs, eval, the root, their globs)
guarded_target() {
  local t="${1//\\//}" tl
  tl="${t,,}"
  case "$tl" in *'$'*|*%*%*)   # a variable we cannot resolve: fall back to the (normalised) suffix
    norm_lex "$tl"; tl="$NORM"
    case "$tl" in *.claude/hooks|*.claude/hooks/*|*.claude/settings.json|*.claude/settings.local.json|*docs/prompt.md|*eval/baseline.json) return 0 ;; esac
    return 1 ;;
  esac
  if [ -z "$cd_seen" ] && [ -z "${2:-}" ]; then
    case "$tl" in *claude*|*hook*|*setting*|*prompt*|*baseline*|*'~'*|*:*) ;; *) return 1 ;; esac
  fi
  resolve "$t" || return 1
  [ "$R_IN" = 1 ] || return 1
  case "${R_REL,,}" in
    .claude/hooks|.claude/hooks/*|.claude/settings.json|.claude/settings.local.json|docs/prompt.md|eval/baseline.json) return 0 ;;
  esac
  if [ -n "${2:-}" ]; then
    case "${R_REL,,}" in .|'*'|.claude|'.claude/*'|'.*'|docs|'docs/*'|eval|'eval/*') return 0 ;; esac
  fi
  return 1
}
is_local_host() {
  case "${1,,}" in
    ''|localhost|127.0.0.1|::1|'[::1]'|github.com|ssh.github.com) return 0 ;;
  esac
  return 1
}
current_branch() { git -C "${1:-${cwd:-$PROJECT_DIR}}" symbolic-ref --short HEAD 2>/dev/null || true; }
git_dir_now() { if [ -n "$cd_seen" ] && [ -z "$cur_unknown" ] && [ -n "$cur_abs" ]; then GDIR="$cur_abs"; else GDIR="${cwd:-$PROJECT_DIR}"; fi; }

# ---------- per-command checks ----------
del_check() { # del_check <verb> <target>   (B5)
  local v="$1" a="$2" al
  [ -z "$a" ] && return 0
  case "$a" in
    *'$'*|*'`'*|*%*%*) block "refusing '$v ... $a' - the target contains a shell variable or substitution that cannot be checked before it runs. Safe alternative: spell out a literal path inside the repo, /tmp or the scratchpad" ;;
  esac
  al="${a//\\//}"
  # shellcheck disable=SC2088
  case "$al" in
    /|'/*'|[A-Za-z]:|[A-Za-z]:/|[A-Za-z]:/\*|/[A-Za-z]|/[A-Za-z]/|/[A-Za-z]/\*|'~'|'~/'|'~/*')
      block "refusing '$v ... $a' - it deletes a whole drive or your home. Safe alternative: delete a specific path inside the repo (e.g. 'rm -rf build/'), under /tmp or in the scratchpad" ;;
  esac
  if ! resolve "$a"; then
    block "refusing '$v ... $a' after '$cur_unknown' - the directory cannot be resolved, so the relative delete may hit anything. Safe alternative: use a path inside the repo, /tmp or the scratchpad without changing directory first"
  fi
  if [ "$R_IN" = 1 ]; then
    case "${R_REL,,}" in
      .|'*'|'.*'|.git|.git/*)
        block "refusing '$v ... $a' - it deletes the repository itself or its .git. Safe alternative: delete a specific path inside the repo (e.g. 'rm -rf build/')" ;;
    esac
    return 0
  fi
  safe_abs "${R_ABS,,}" && return 0
  block "refusing '$v ... $a' - '$R_ABS' is outside the repo ($root), /tmp and the scratchpad. Safe alternative: delete inside the repo or the scratchpad, or ask the owner to remove it"
}

check_rm() { # unix rm (also the PowerShell alias)
  local endopts=0 a skip=0 targets=()
  for a in "$@"; do
    if [ "$skip" = 1 ]; then skip=0; continue; fi
    if [ "$endopts" = 0 ]; then
      case "${a,,}" in
        '#'*) break ;;
        --) endopts=1; continue ;;
        -filter|-include|-exclude) skip=1; continue ;;
        -*) continue ;;
      esac
    fi
    targets+=("$a")
  done
  for a in "${targets[@]+"${targets[@]}"}"; do del_check rm "$a"; done
}

check_ps_del() { # <verb> args: Remove-Item/ri/del/erase, cmd rd/rmdir/del (/s /q)
  local v="$1" a skip=0 targets=()
  shift
  for a in "$@"; do
    if [ "$skip" = 1 ]; then skip=0; continue; fi
    case "${a,,}" in
      '#'*) break ;;
      /s|/q|/f|/a*|/p) ;;
      -filter|-include|-exclude) skip=1 ;;
      -*) ;;
      *) targets+=("$a") ;;
    esac
  done
  for a in "${targets[@]+"${targets[@]}"}"; do del_check "$v" "$a"; done
}

check_find() { # find <start...> <expr>: -delete or -exec rm (B5)
  local a i=0 n=$# del=0 filt=0 starts=() args=("$@") nxt
  while (( i < n )); do case "${args[i]}" in -H|-L|-P|-O*) ;; -D) i=$((i + 1)) ;; *) break ;; esac; i=$((i + 1)); done
  while (( i < n )); do case "${args[i]}" in -*|'!'|,|'#'*) break ;; esac; starts+=("${args[i]}"); i=$((i + 1)); done
  while (( i < n )); do
    a="${args[i],,}"
    case "$a" in
      '#'*) break ;;
      -delete) del=1 ;;
      -exec|-execdir|-ok|-okdir)
        nxt="${args[i+1]:-}"; nxt="${nxt,,}"; nxt="${nxt##*/}"
        case "$nxt" in rm|rm.exe|rmdir|del|unlink|shred|remove-item|ri) del=1 ;; esac ;;
      -name|-iname|-path|-ipath|-wholename|-regex|-iregex|-type|-newer|-mtime|-mmin|-size|-empty|-user|-perm) filt=1 ;;
    esac
    i=$((i + 1))
  done
  [ "$del" = 1 ] || return 0
  [ "${#starts[@]}" -eq 0 ] && starts=(.)
  for a in "${starts[@]}"; do
    if [ "$filt" = 1 ]; then resolve "$a" && [ "$R_IN" = 1 ] && [ "$R_REL" = . ] && continue; fi   # find . -name x -delete
    del_check "find ... -delete" "$a"
  done
}

check_cd() {
  local a="" t
  for t in "$@"; do case "$t" in '#'*) break ;; -*) continue ;; *) a="$t"; break ;; esac; done
  cd_seen=1
  [ -z "$a" ] && a='~'
  case "$a" in *'$'*|*'`'*|*%*%*|-) cur_unknown="cd $a"; return 0 ;; esac
  if is_abs "$a" || [ -z "$cur_unknown" ] || [[ "$a" == '~'* ]]; then
    cur_unknown=""
    resolve "$a" && cur_abs="$R_ABS"
  fi
}

check_push() { # <git dir> push-args... (B1)
  local gdir="$1" force=0 del=0 all=0 skipnext=0 t cl k ch pos=() refs=() r plus src dst cb
  shift
  for t in "$@"; do
    if [ "$skipnext" = 1 ]; then skipnext=0; continue; fi
    case "$t" in
      '#'*) break ;;
      --) continue ;;
      --mi|--mir|--mirr|--mirro|--mirror)
        block "git push --mirror force-updates every remote ref, main included (git accepts '$t' as --mirror). Safe alternative: push one story branch: 'git push -u origin story/<id>'" ;;
      --fol*) ;;   # --follow-tags
      --f|--fo|--for*) force=1 ;;
      --d|--de|--del*) del=1 ;;
      --all|--branches) all=1 ;;
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
        main|master|"") block "force-push to '${dst:-the current branch (unknown)}' is forbidden (--force or a prefix git accepts, -f in a flag cluster, a '+' refspec, or HEAD on main); main has linear history and is protected. Safe alternative: rebase a story/* branch and 'git push --force-with-lease origin story/<id>'" ;;
      esac
    fi
  done
}

check_reset() { # <git dir> reset-args... (B2)
  local gdir="$1" hard=0 t against=0 cb
  shift
  for t in "$@"; do
    case "$t" in
      '#'*) break ;;
      --h|--ha|--har|--hard) hard=1 ;;
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

check_commit_n() { # git commit args: -n (= --no-verify) anywhere among the options (B4)
  local t cl k ch skip=0
  for t in "$@"; do
    if [ "$skip" = 1 ]; then skip=0; continue; fi
    case "$t" in
      '#'*|--) return 0 ;;
      --message|--file|--reuse-message|--reedit-message|--template|--author|--date|--cleanup|--fixup|--squash|--trailer|--pathspec-from-file) skip=1 ;;
      --*) ;;
      -?*)
        cl="${t:1}"
        for (( k = 0; k < ${#cl}; k++ )); do
          ch="${cl:k:1}"
          case "$ch" in
            n) block "bypassing git hooks with 'git commit -n' is forbidden. Safe alternative: fix what the hook reports, then commit normally" ;;
            m|F|C|c|t) [ $((k + 1)) -eq ${#cl} ] && skip=1; break ;;   # the value follows: attached or the next word
          esac
        done ;;
    esac
  done
}

hookspath_block() { block "overriding core.hooksPath switches the git hooks off, which is forbidden. Safe alternative: fix what the hook reports; read the setting with 'git config --get core.hooksPath'"; }
check_git_config() { # git config args (B4: setting/unsetting core.hooksPath)
  local t tl get=0 setlike=0 skip=0 words=()
  for t in "$@"; do
    if [ "$skip" = 1 ]; then skip=0; continue; fi
    tl="${t,,}"
    case "$tl" in
      '#'*) break ;;
      --get|--get-all|--get-regexp|--get-urlmatch|--list|-l|--show-origin|--show-scope|--name-only) get=1 ;;
      --unset|--unset-all|--add|--replace-all) setlike=1 ;;
      -f|--file|--blob|--type|--default|--comment) skip=1 ;;
      -*) ;;
      *) words+=("$tl") ;;
    esac
  done
  case "${words[0]:-}" in
    set|unset|--add) setlike=1; words=("${words[@]:1}") ;;
    get|list) get=1; words=("${words[@]:1}") ;;
  esac
  [ "${words[0]:-}" = core.hookspath ] || return 0
  if [ "$setlike" = 1 ] || { [ "$get" = 0 ] && [ "${#words[@]}" -gt 1 ]; }; then hookspath_block; fi
}

check_git_paths() { # git checkout/restore: the paths it overwrites (B13)
  local sub="$1" t dd=0 skip=0 rev=0 pre=() paths=()
  shift
  for t in "$@"; do
    if [ "$skip" = 1 ]; then skip=0; continue; fi
    if [ "$dd" = 1 ]; then paths+=("$t"); continue; fi
    case "$t" in
      '#'*) break ;;
      --) dd=1 ;;
      -s|--source) skip=1; rev=1 ;;
      --source=*|-s?*) rev=1 ;;
      -b|-B|--orphan|--conflict|--pathspec-from-file) skip=1 ;;
      -*) ;;
      *) pre+=("$t") ;;
    esac
  done
  if [ "$sub" = restore ]; then paths+=("${pre[@]+"${pre[@]}"}")
  elif [ "${#pre[@]}" -gt 0 ]; then
    rev=1   # git checkout <rev> [--] <paths>: the first word is the revision
    [ "$dd" = 0 ] && paths+=("${pre[@]:1}")
  fi
  for t in "${paths[@]+"${paths[@]}"}"; do
    if [ "$rev" = 1 ]; then check_write_target "git $sub" "$t" ancestors; else check_write_target "git $sub" "$t"; fi
  done
}

check_git() {
  local gdir j=0 n=$# sub="" args=("$@") t
  git_dir_now; gdir="$GDIR"
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
      -c) j=$((j + 1)); case "${args[j],,}" in core.hookspath*) hookspath_block ;; esac ;;
      -c?*) case "${t:2}" in [Cc][Oo][Rr][Ee].[Hh][Oo][Oo][Kk][Ss][Pp][Aa][Tt][Hh]*) hookspath_block ;; esac ;;
      --git-dir|--work-tree|--namespace|--config-env|--exec-path) j=$((j + 1)) ;;
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
    config) check_git_config "${args[@]:j+1}" ;;
    checkout|restore) check_git_paths "$sub" "${args[@]:j+1}" ;;
    update-ref|symbolic-ref)
      for t in "${args[@]:j+1}"; do
        case "${t,,}" in
          '#'*) break ;;
          main|master|refs/heads/main|refs/heads/master|heads/main|heads/master)
            block "git $sub on '$t' rewrites the main branch pointer, which is forbidden. Safe alternative: create or move a story/* branch instead" ;;
        esac
      done ;;
    branch)
      local destructive=0 names=0
      for t in "${args[@]:j+1}"; do
        case "$t" in
          '#'*) break ;;
          --forc*|--del*|--de|--mov*|--cop*) destructive=1 ;;
          --*) ;;
          -*[DdfMmCc]*) destructive=1 ;;
          main|master|refs/heads/main|refs/heads/master) names=1 ;;
        esac
      done
      if [ "$destructive" = 1 ] && [ "$names" = 1 ]; then
        block "rewriting/deleting the main branch pointer is forbidden. Safe alternative: create or rename a story/* branch instead"
      fi ;;
  esac
}

check_remote() { # <verb> args: ssh/scp/sftp/rsync to anything but github.com/localhost needs /release (B14)
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

check_write_target() { # <verb> <target> [ancestors] (B13; .env always)
  local v="$1" t="${2#of=}"
  [ -z "$t" ] && return 0
  if is_env_path "$t"; then
    block "writing '$t' with $v is forbidden - .env holds the owner's secrets. Safe alternative: edit .env.example (placeholders only) and ask the owner to update .env"
  fi
  if guarded_target "$t" "${3:-}" && ! hooks_unlocked; then
    block "'$t' is (or holds) a protected guardrail file (hooks/settings/settings.local/PROMPT/eval baseline) and $v would change it. Safe alternative: 'make hooks-unlock' (or /retro, /sdlc-selftest), change it, 'make hooks-lock', and note it in docs/DECISIONS.md"
  fi
}

check_writes() { # <verb> args: shell commands that create, overwrite, move or delete files
  local v="$1" a inplace=0 nonflag=() last k anc=""
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
    rm|del|erase|remove-item|ri|unlink|rmdir|rd|mv|move-item|mi|move|ren|rename|rename-item|rni) anc=ancestors ;;
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
        return 0   # cp .env.example .env is the documented bootstrap
      fi
      check_write_target "$v" "$last" ;;
    *)
      for a in "${nonflag[@]}"; do check_write_target "$v" "$a" "$anc"; done ;;
  esac
}

check_env_listing() { # <verb> args: printenv / env / set / export without arguments dump every secret (B10)
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
      [ "$has" = 0 ] && block "dumping the whole environment ('$v' without arguments) is forbidden - it contains secrets. Safe alternative: print one non-secret variable by name"
      for a in "$@"; do case "${a,,}" in lefthook=*|lefthook_exclude=*) lefthook_block ;; esac; done ;;
    get-childitem|gci|dir|ls|get-item|gi)
      for a in "$@"; do
        case "${a,,}" in
          env:|env:\\|env:/|'env:*'|'env:\*'|env:*key*|env:*secret*|env:*token*|env:*password*)
            block "listing Env: in PowerShell dumps secrets. Safe alternative: read one non-secret variable, e.g. \$env:PATH" ;;
        esac
      done ;;
  esac
}
lefthook_block() { block "LEFTHOOK=0 / LEFTHOOK_EXCLUDE switch the git hooks off, which is forbidden. Safe alternative: fix what the hook reports, then commit normally"; }
env_read_block() { block "reading .env files prints secrets ($1). Safe alternative: read .env.example, or reference variables by name"; }

# stage_cmd <encoded simple command> -> SC_BASE (lower-case basename, no .exe) and SC_ARGS (decoded)
stage_cmd() {
  local toks t tl i=0 n wrap=0 val=0
  # shellcheck disable=SC2206
  toks=($1)
  n=${#toks[@]}; SC_BASE=""; SC_ARGS=()
  while (( i < n )); do
    t="${toks[i]}"; tl="${t,,}"
    if [ "$val" = 1 ]; then val=0; i=$((i + 1)); continue; fi
    if [[ "$t" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then i=$((i + 1)); continue; fi
    case "$tl" in
      sudo|doas|env|command|exec|nice|time|nohup|stdbuf|timeout|xargs) wrap=1 ;;
      -u|-g|-h|-p|-c|-t|-s|-k|-n) [ "$wrap" = 1 ] || break; val=1 ;;
      -*|[0-9]*) [ "$wrap" = 1 ] || break ;;
      *) break ;;
    esac
    i=$((i + 1))
  done
  (( i < n )) || return 0
  SC_BASE="${toks[i],,}"; SC_BASE="${SC_BASE//\\//}"; SC_BASE="${SC_BASE##*/}"; SC_BASE="${SC_BASE%.exe}"
  for t in "${toks[@]:i+1}"; do dec "$t"; SC_ARGS+=("$DEC"); done
}

# ---------- analyse one command string (wrapped commands are queued and analysed the same way) ----------
QUEUE=(); ENC_ALL=""
re_out='>>?[[:space:]]*([^[:space:]<>]+)'
re_in='(^|[^<])<[[:space:]]*([^[:space:]<>]+)'
analyze() {
  local c="$1" enc pipes pl stages st segs seg s toks n i t tl wrap lastwrap valnext base args a j o found gci_paths have_dl m0 m1 m2
  strip_heredocs "$c"; encode "$HD"
  enc="${ENC//$'\r'/ }"; enc="${enc//$'\t'/ }"; enc="${enc//$'\n'/ ; }"
  enc="${enc//>|/> }"; enc="${enc//&>/ >}"; enc="${enc//>&/>}"; enc="${enc//|&/|}"
  ENC_ALL+="$enc ; "
  cur_abs=""; cur_unknown=""; cd_seen=""

  # --- pipelines: download | ... | shell (B6), Get-ChildItem/find | Remove-Item (B5) ---
  if [[ "$enc" == *'|'* ]]; then
    pipes="${enc//||/$'\n'}"; pipes="${pipes//&&/$'\n'}"; pipes="${pipes//;/$'\n'}"; pipes="${pipes//&/$'\n'}"
    while IFS= read -r pl; do
      [[ "$pl" == *'|'* ]] || continue
      IFS='|' read -r -a stages <<< "$pl"
      have_dl=0; gci_paths=()
      for st in "${stages[@]}"; do
        stage_cmd "$st"
        case "$SC_BASE" in
          curl|wget|iwr|irm|invoke-webrequest|invoke-restmethod) have_dl=1 ;;
          base64) for a in "${SC_ARGS[@]+"${SC_ARGS[@]}"}"; do case "$a" in -d|-D|--decode|-[a-z]*d) have_dl=1 ;; esac; done ;;
          sh|bash|zsh|dash|ksh|ash|pwsh|powershell|iex|invoke-expression|python|python3|py|perl|ruby|node|php|source|.)
            [ "$have_dl" = 1 ] && block "piping a download (or base64 -d) into a shell or interpreter is forbidden. Safe alternative: download to a file, inspect it, then run it" ;;
          get-childitem|gci|ls|dir|get-item|gi|find)
            gci_paths=()
            for a in "${SC_ARGS[@]+"${SC_ARGS[@]}"}"; do case "$a" in -*|'!'|'#'*) [ "$SC_BASE" = find ] && break ;; *) gci_paths+=("$a") ;; esac; done
            [ "${#gci_paths[@]}" -eq 0 ] && gci_paths=(.) ;;
          remove-item|ri|rm|del|erase|rd|rmdir)
            local has_t=0
            for a in "${SC_ARGS[@]+"${SC_ARGS[@]}"}"; do case "$a" in -*|/s|/q) ;; *) has_t=1 ;; esac; done
            if [ "$has_t" = 0 ]; then for a in "${gci_paths[@]+"${gci_paths[@]}"}"; do del_check "... | $SC_BASE" "$a"; done; fi ;;
        esac
      done
    done <<< "$pipes"
  fi

  # --- simple commands ---
  segs="${enc//&&/$'\n'}"; segs="${segs//||/$'\n'}"
  segs="${segs//;/$'\n'}"; segs="${segs//&/$'\n'}"; segs="${segs//|/$'\n'}"
  segs="${segs//(/$'\n'}"; segs="${segs//)/$'\n'}"; segs="${segs//\`/$'\n'}"
  set -f
  while IFS= read -r seg; do
    # redirections: output targets are writes, `< file` is a read
    if [[ "$seg" == *[\<\>]* ]]; then
      s="$seg"
      # (BASH_REMATCH is copied first: the checks run [[ =~ ]] themselves and would clobber it)
      while [[ "$s" =~ $re_out ]]; do
        m0="${BASH_REMATCH[0]}"; m1="${BASH_REMATCH[1]}"
        seg="${seg/"$m0"/ }"; s="${s#*"$m0"}"
        dec "$m1"; check_write_target "redirection" "$DEC"
      done
      s="$seg"
      while [[ "$s" =~ $re_in ]]; do
        m0="${BASH_REMATCH[0]}"; m1="${BASH_REMATCH[1]}"; m2="${BASH_REMATCH[2]}"
        seg="${seg/"$m0"/$m1 }"; s="${s#*"$m0"}"
        dec "$m2"; is_env_path "$DEC" && env_read_block "< $DEC"
      done
    fi
    # shellcheck disable=SC2206
    toks=($seg)
    n=${#toks[@]}; i=0; wrap=0; lastwrap=""; valnext=0
    while (( i < n )); do
      t="${toks[i]}"; tl="${t,,}"
      if [ "$valnext" = 1 ]; then valnext=0; i=$((i + 1)); continue; fi
      if [[ "$t" =~ ^([A-Za-z_][A-Za-z0-9_]*)= ]]; then
        case "${BASH_REMATCH[1],,}" in lefthook|lefthook_exclude) lefthook_block ;; esac
        i=$((i + 1)); continue
      fi
      case "$tl" in
        '#'*) i=$n; break ;;
        sudo|doas|exec|command|builtin|nohup|time|nice|ionice|stdbuf|env|*/env|xargs|then|do|else|elif|if|while|until|'!'|'{'|'}'|timeout|watch|call|sshpass|start-process|invoke-command)
          wrap=1; lastwrap="${tl##*/}" ;;
        eval)
          o=""; for t in "${toks[@]:i+1}"; do dec "$t"; o+="$DEC "; done
          QUEUE+=("$o"); i=$n; break ;;
        -u|-g|-h|-p|-c|-d|-r|-t|-s|-k|-i|-n|-l|-e|-a|-o|-T)
          [ "$wrap" = 1 ] || break
          case "$lastwrap:$tl" in
            sudo:-u|sudo:-g|sudo:-h|sudo:-p|sudo:-c|sudo:-d|sudo:-r|sudo:-t|sudo:-T) valnext=1 ;;
            xargs:-i|xargs:-n|xargs:-p|xargs:-d|xargs:-l|xargs:-e|xargs:-a|xargs:-s) valnext=1 ;;
            timeout:-s|timeout:-k|env:-u|env:-c|nice:-n|ionice:-c|ionice:-n|stdbuf:-o|stdbuf:-e|stdbuf:-i|sshpass:-p) valnext=1 ;;
          esac ;;
        -*) [ "$wrap" = 1 ] || break ;;
        [0-9]*) [ "$wrap" = 1 ] || break ;;
        bash|sh|zsh|dash|ksh|ash|*/bash|*/sh|*/zsh|*/dash|bash.exe|pwsh|pwsh.exe|powershell|powershell.exe|*/pwsh|*/powershell.exe)
          j=$((i + 1)); found=0
          while (( j < n )); do
            o="${toks[j],,}"
            case "$o" in
              -c|-[a-z]*c|-com|-comm|-comma|-comman|-command) found=1; break ;;
              -*) j=$((j + 1)) ;;
              *) break ;;
            esac
          done
          if [ "$found" = 1 ]; then
            o=""
            case "$tl" in
              *pwsh*|*powershell*) for t in "${toks[@]:j+1}"; do dec "$t"; o+="$DEC "; done ;;
              *) dec "${toks[j+1]:-}"; o="$DEC" ;;
            esac
            QUEUE+=("$o")
          fi
          i=$n; break ;;
        cmd|cmd.exe|*/cmd.exe)
          case "${toks[i+1]:-}" in
            /c|/C|/k|/K) i=$((i + 2)); wrap=1; lastwrap=cmd; continue ;;
          esac
          break ;;
        *) break ;;
      esac
      i=$((i + 1))
    done
    if (( i >= n )); then
      [ "$lastwrap" = env ] && block "dumping the whole environment ('env' without a command) is forbidden - it contains secrets. Safe alternative: print one non-secret variable by name"
      continue
    fi
    c="${toks[i]}"; base="${c,,}"; base="${base//\\//}"; base="${base##*/}"; base="${base%.exe}"
    args=()
    for t in "${toks[@]:i+1}"; do dec "$t"; args+=("$DEC"); done
    case "$base" in
      rm) check_rm "${args[@]+"${args[@]}"}" ;;
      remove-item|ri|del|erase|rd|rmdir) check_ps_del "$c" "${args[@]+"${args[@]}"}" ;;
      find) check_find "${args[@]+"${args[@]}"}" ;;
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
    case "$base" in   # B12: a reader whose argument is (or globs to) .env
      cat|tac|less|more|head|tail|nl|grep|egrep|fgrep|rg|ag|awk|gawk|sed|cut|sort|uniq|strings|xxd|od|hexdump|base64|diff|cmp|source|.|type|gc|get-content|bat|batcat|select-string|sls|findstr|tar|zip|7z|curl|scp|rsync|jq|yq)
        for a in "${args[@]+"${args[@]}"}"; do
          a="${a#@}"; a="${a##*=}"
          is_env_path "$a" && env_read_block "$base $a"
        done ;;
    esac
    case "$base" in   # B13
      tee|tee-object|sed|perl|cp|install|copy-item|cpi|copy|xcopy|robocopy|mv|move-item|mi|move|ren|rename|rename-item|rni|rm|del|erase|remove-item|ri|unlink|rmdir|rd|chmod|chown|chgrp|truncate|touch|ln|set-content|sc|add-content|ac|out-file|clear-content|clc|new-item|ni|shred|dd)
        check_writes "$base" "${args[@]+"${args[@]}"}" ;;
    esac
  done <<< "$segs"
  set +f
}

QUEUE=("$cmd"); qi=0
while (( qi < ${#QUEUE[@]} && qi < 8 )); do analyze "${QUEUE[qi]}"; qi=$((qi + 1)); done
enc_l="${ENC_ALL,,}"

# ---------- hook bypass via PowerShell env (B4) ----------
re_psenv='\$env:lefthook(_exclude)?[[:space:]]*='
[[ "$lc" =~ $re_psenv ]] && lefthook_block

# ---------- download fed to a shell without a pipe (B6) ----------
dl='(curl|wget|iwr|irm|invoke-webrequest|invoke-restmethod)'
re_pipe='((ba|z|da|k)?sh|source|\.)( +-[a-z]+)* +<\( *'"$dl"'|((ba|z|da|k)?sh|eval)( +-[a-z]+)* +["'"'"']?\$\( *'"$dl"'|iex *\(.*(downloadstring|'"$dl"')'
if [[ "$lc" =~ $re_pipe ]]; then
  block "piping a download into a shell or interpreter is forbidden. Safe alternative: download to a file, inspect it, then run it"
fi

# ---------- DROP/TRUNCATE against a non-local database (B8; needs a DB client in the command) ----------
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

# ---------- docker destruction (B9) ----------
re_docker='docker +system +prune[^;&|]* (-[a-z]*a[a-z]*|--all)( |$)|docker +volume +prune|docker +volume +rm'
if [[ "$enc_l" =~ $re_docker ]]; then
  block "docker system prune -a / volume prune / volume rm destroy local data for every project. Safe alternative: 'make down' or remove one named container"
fi

# ---------- printing secrets (B11) ----------
re_echo='(echo|printf|write-host|write-output) +[^;&|]*(\$env:|\$\{?)[a-z0-9_]*(key|secret|token|password|passwd|passphrase)[a-z0-9_]*|getenvironmentvariables *\('
if [[ "$enc_l" =~ $re_echo ]]; then
  block "echoing a variable whose name looks like a secret is forbidden. Safe alternative: check presence only, e.g. '[ -n \"\$NAME\" ] && echo set'"
fi

# ---------- interpreter one-liners and .NET calls that write a protected file (B13) ----------
re_wcall='open *\(([^()]|\([^()]*\))*, *(mode *= *)?[rb]?["'"'"'][rbt]*[wax+]|write_text|write_bytes|writefilesync|appendfilesync|createwritestream|\[(system\.)?io\.file\]::(write|append|copy|move|replace|delete)|shutil\.(copy|move)|os\.(replace|rename|remove|unlink)|copyfilesync|renamesync|unlinksync|rmsync'
if [[ "$lc" =~ $re_wcall ]]; then
  s="$raw"; re_tok='[A-Za-z0-9_./\\:~$-]*(claude|hook|setting|prompt|baseline|\.env|~)[A-Za-z0-9_./\\:~$-]*'
  while [[ "$s" =~ $re_tok ]]; do
    m0="${BASH_REMATCH[0]}"; s="${s#*"$m0"}"
    check_write_target "an interpreter write call" "$m0"
  done
fi

# ---------- .env readers in code and quoted forms (B12) ----------
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
  re_reader='(^|[^a-z0-9_.-])(cat|tac|less|more|head|tail|grep|egrep|fgrep|rg|ag|awk|gawk|sed|cut|bat|batcat|type|strings|xxd|od|hexdump|base64|tee|nl|paste|sort|uniq|diff|cmp|python[0-9.]*|py|node|deno|bun|perl|ruby|php|get-content|gc|select-string|sls|findstr|curl|wget|nc|jq|yq|source)([^a-z0-9_-]|$)|open *\(|\[(system\.)?io\.file\]::|readall(text|lines|bytes)'
  if [ "$envfound" = 1 ] && [[ "$lc" =~ $re_reader ]]; then
    env_read_block ".env named in a reader or in code"
  fi
fi

# ---------- production / remote hosts outside /release (B14) ----------
for ph in "${prod_hosts[@]+"${prod_hosts[@]}"}"; do
  if [[ "$lc" == *"${ph,,}"* ]] && ! release_is_active; then
    block "command targets the production/staging host '$ph' but no /release is active. Safe alternative: run it through the /release skill (make deploy-*)"
  fi
done

exit 0
