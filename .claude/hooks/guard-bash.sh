#!/usr/bin/env bash
# PreToolUse(Bash|PowerShell): block destructive or secret-leaking shell commands.
# Exit 2 = block. Reads tool_input.command from stdin JSON. ASCII-only source.
HOOK_NAME=guard-bash
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
read_input
cmd="$(json_get '.tool_input.command')"
[ -z "$cmd" ] && exit 0
cwd="$(json_get '.cwd')"
scratch="$(json_get '.scratchpad_dir')"
flat="$(printf '%s' "$cmd" | tr '\n\t' '  ' | tr -s ' ')"
lc="${flat,,}"

# ---------- 1. rm -rf outside the repo, on /, home, .git or parent dirs ----------
if printf '%s' "$flat" | grep -Eq '(^|[;&|(] *)(sudo +)?rm +(-[a-zA-Z]*[rR][a-zA-Z]* +(-[a-zA-Z]+ +)*|-[a-zA-Z]*[fF][a-zA-Z]*[rR][a-zA-Z]* +)'; then
  root="$(to_posix_path "$PROJECT_DIR")"
  while IFS= read -r seg; do
    args="$(printf '%s' "$seg" | sed -E 's/^[;&|( ]*(sudo +)?rm +//' | tr ' ' '\n' | grep -vE '^-' || true)"
    while IFS= read -r a; do
      [ -z "$a" ] && continue
      a="${a%\"}"; a="${a#\"}"; a="${a%\'}"; a="${a#\'}"
      # shellcheck disable=SC2016,SC2088
      case "$a" in
        /|'/*'|'~'|'~/'|'~/*'|'$HOME'*|'${HOME}'*|'*'|.|./|..|'../'*|.git|.git/|'.git/'*|'$CLAUDE_PROJECT_DIR'*|'$PROJECT_DIR'*|/c|/c/|'C:\'|'C:/')
          block "refusing 'rm -rf $a' - it deletes the repo, your home, or everything" ;;
        /*|[A-Za-z]:*)
          pa="$(to_posix_path "$a")"
          tmpc="$(to_posix_path /tmp)"
          case "$pa" in
            "$root"/*|"$tmpc"/*|/tmp/*|/var/tmp/*|/dev/shm/*) : ;;
            *)
              if [ -n "$scratch" ] && [[ "$pa" == "$(to_posix_path "$scratch")"* ]]; then :; else
                block "refusing 'rm -rf $a' - absolute path outside the repo ($root), /tmp or the scratchpad"
              fi ;;
          esac ;;
      esac
    done <<< "$args"
  done < <(printf '%s' "$flat" | grep -oE '(^|[;&|(] *)(sudo +)?rm +(-[a-zA-Z]+ +)+[^;&|)]*')
fi

# ---------- 2. force-push / history rewrite on main ----------
current_branch() { git -C "${cwd:-$PROJECT_DIR}" symbolic-ref --short HEAD 2>/dev/null || true; }
if printf '%s' "$flat" | grep -Eq '(^|[;&|] *)git( +-[A-Za-z-]+( +[^ ]+)?)* +push( +.*)?( --force| -f | -f$| --force-with-lease)'; then
  pushargs="$(printf '%s' "$flat" | grep -oE 'git( +-[A-Za-z-]+( +[^ ]+)?)* +push[^;&|]*' | head -1 | sed -E 's/^git.*push +//')"
  refs="$(printf '%s' "$pushargs" | tr ' ' '\n' | grep -vE '^-' | tail -n +2 || true)"   # drop the remote
  target=""
  if [ -n "$refs" ]; then target="$(printf '%s' "$refs" | head -1 | sed -E 's/^\+//; s/^.*://; s#^refs/heads/##')"
  else target="$(current_branch)"; fi
  case "$target" in
    main|master|"") block "force-push to '${target:-current branch (unknown)}' is forbidden; main has linear history and is protected. Rebase a story branch instead." ;;
  esac
fi
if printf '%s' "$flat" | grep -Eq '(^|[;&|] *)git +push[^;&|]* (--delete +(main|master)|:(main|master)( |$))'; then
  block "deleting main/master on the remote is forbidden"
fi
if printf '%s' "$flat" | grep -Eq '(^|[;&|] *)git +reset +--hard'; then
  cb="$(current_branch)"
  if [ "$cb" = main ] || [ "$cb" = master ] || printf '%s' "$flat" | grep -Eq 'reset +--hard +[^ ]*(main|master)'; then
    block "git reset --hard on/against main is forbidden (current branch: ${cb:-unknown}); use a story branch"
  fi
fi
if printf '%s' "$flat" | grep -Eq '(^|[;&|] *)git +branch +(-D|-d|-f|--force|-M|-m) +[^ ]* *(main|master)( |$)|(^|[;&|] *)git +branch +(-D|-d) +(main|master)( |$)'; then
  block "rewriting/deleting the main branch pointer is forbidden"
fi

# ---------- 3. hook bypass ----------
if printf '%s' "$flat" | grep -Eq -- '--no-verify|(^|[;&| ])git +commit[^;&|]* -[a-zA-Z]*n[a-zA-Z]*( |$)|core\.hooksPath|LEFTHOOK=0|LEFTHOOK_EXCLUDE'; then
  block "bypassing git hooks (--no-verify / -n / core.hooksPath / LEFTHOOK=0) is forbidden; fix the hook failure instead"
fi

# ---------- 4. pipe-to-shell ----------
if printf '%s' "$lc" | grep -Eq '(curl|wget|iwr|invoke-webrequest)[^|;&]*\| *(sudo +)?(ba|z|da|k)?sh( |$|-)|\| *bash +-s|\| *sh +-s|iex *\(|invoke-expression'; then
  block "piping a download into a shell is forbidden; download to a file, inspect, then run"
fi

# ---------- 5. world-writable ----------
if printf '%s' "$flat" | grep -Eq '(^|[;&| ])chmod +(-R +)?(0?777|a\+rwx|o\+w|ugo\+rwx)'; then
  block "chmod 777 / world-writable permissions are forbidden"
fi

# ---------- 6. DROP/TRUNCATE against a non-local database ----------
if printf '%s' "$lc" | grep -Eq '(^|[^a-z])(drop +(table|database|schema|index)|truncate)([^a-z]|$)'; then
  hosts="$(printf '%s' "$flat" | grep -oE '(postgres(ql)?://[^ /]*@|(-h|--host[= ]) *)[A-Za-z0-9_.-]+' | sed -E 's/^.*@//; s/^(-h|--host[= ]) *//' || true)"
  envfile="$PROJECT_DIR/.env"
  prod_host=""; [ -f "$envfile" ] && prod_host="$(grep -E '^(PROD_HOST|STAGING_HOST)=' "$envfile" | cut -d= -f2- | tr -d '"' | grep -v '^$' || true)"
  while IFS= read -r h; do
    [ -z "$h" ] && continue
    case "$h" in localhost|127.0.0.1|::1|postgres|db|host.docker.internal) : ;; *) block "DROP/TRUNCATE against non-local database host '$h' is forbidden" ;; esac
  done <<< "$hosts"
  for ph in $prod_host; do [[ "$flat" == *"$ph"* ]] && block "DROP/TRUNCATE mentions a production/staging host ($ph)"; done
fi

# ---------- 7. docker destruction ----------
if printf '%s' "$lc" | grep -Eq 'docker +system +prune[^;&|]* (-a|--all)|docker +volume +prune|docker +volume +rm'; then
  block "docker system prune -a / volume prune / volume rm destroy local data for every project; use 'make down' or target one container"
fi

# ---------- 8. printing secrets ----------
if printf '%s' "$flat" | grep -Eq '(^|[;&| ])printenv( |$)'; then
  if ! printf '%s' "$flat" | grep -Eq '(^|[;&| ])printenv +[A-Za-z_]+' || printf '%s' "$flat" | grep -Eiq 'printenv +[A-Za-z0-9_]*(key|secret|token|password|passwd|passphrase)'; then
    block "printing environment variables that may hold secrets is forbidden"
  fi
fi
if printf '%s' "$flat" | grep -Eq '(^|[;&|] *)env *($|[;&|])|(^|[;&| ])(set|export|declare -x|Get-ChildItem +Env:|gci +env:|dir +env:) *($|[;&|])'; then
  block "dumping the whole environment is forbidden (it contains secrets)"
fi
# shellcheck disable=SC2016
if printf '%s' "$flat" | grep -Eiq '(echo|printf|write-host|write-output) +[^;&|]*\$(\{)?[A-Za-z0-9_]*(KEY|SECRET|TOKEN|PASSWORD|PASSWD|PASSPHRASE)[A-Za-z0-9_]*'; then
  block "echoing a variable whose name looks like a secret is forbidden"
fi
envtoks="$(printf '%s' "$flat" | tr ' ' '\n' | grep -E '(^|/)\.env(\.[A-Za-z0-9_-]+)?$' | grep -v 'example$' || true)"
if [ -n "$envtoks" ] && printf '%s' "$flat" | grep -Eq '(^|[;&|(] *)(cat|less|more|head|tail|grep|egrep|fgrep|rg|awk|sed|cut|bat|type|strings|xxd|od|base64|Get-Content|gc|tee|nl|paste)( |$)'; then
  block "reading .env files prints secrets; use .env.example or reference variables by name"
fi

# ---------- 9. production / remote hosts outside /release ----------
release_active="$(state_get release_active)"
[ -n "${MASROOF_RELEASE_ACTIVE:-}" ] && release_active=true
if ! is_truthy "$release_active"; then
  envfile="$PROJECT_DIR/.env"
  if [ -f "$envfile" ]; then
    while IFS= read -r ph; do
      [ -z "$ph" ] && continue
      [[ "$flat" == *"$ph"* ]] && block "command targets the production/staging host '$ph' but no /release is active"
    done < <(grep -E '^(PROD_HOST|STAGING_HOST)=' "$envfile" | cut -d= -f2- | tr -d '"' | grep -v '^$' || true)
  fi
  if printf '%s' "$flat" | grep -Eq '(^|[;&|] *)(ssh|scp|sftp|rsync) ' && ! printf '%s' "$flat" | grep -Eq '(ssh|scp|sftp|rsync) [^;&|]*(github\.com|localhost|127\.0\.0\.1)'; then
    block "ssh/scp/rsync to remote hosts is only allowed through the /release skill (make deploy-*)"
  fi
fi

exit 0
