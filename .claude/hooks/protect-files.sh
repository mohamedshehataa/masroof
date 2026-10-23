#!/usr/bin/env bash
# PreToolUse(Edit|Write|MultiEdit|NotebookEdit): protect files that must not be edited by agents.
# Exit 2 = block. Paths are compared case-insensitively (NTFS/APFS are case-insensitive, so
# C:/PERSONAL/MASROOF/.ENV and c:\personal\masroof\.claude\hooks\x name protected files too).
HOOK_NAME=protect-files
# shellcheck source=lib.sh
. "${BASH_SOURCE[0]%/*}/lib.sh"
read_input
fields=()
while IFS= read -r -d '' f; do fields+=("$f"); done < <(json_fields '.tool_input.file_path' '.tool_input.notebook_path' '.agent_type')
fp="${fields[1]:-}"; [ -z "$fp" ] && fp="${fields[2]:-}"
agent="${fields[3]:-}"
[ -z "$fp" ] && exit 0
rel="$(rel_to_project "$fp")"
rel_l="${rel,,}"
base="${rel_l##*/}"

unlocked=false
is_truthy "$(state_get hooks_unlocked)" && unlocked=true
is_truthy "${MASROOF_HOOKS_UNLOCK:-}" && unlocked=true

# 1. env files (anywhere), except the committed examples
case "$base" in
  .env.example|*.env.example) : ;;
  .env|.env.*) block "editing '$rel' is forbidden - secrets live only in the owner's .env; update .env.example instead" ;;
esac

# 2. key material
case "$base" in
  *.jks|*.keystore|*.p12|*.pfx|*.key|*.pem|*.ppk|*.der|id_rsa|id_ed25519|id_ecdsa) block "editing key material '$rel' is forbidden" ;;
esac
case "$rel_l" in
  secrets/*|*/secrets/*) block "'$rel' is under a secrets/ directory - forbidden" ;;
esac

# 3. applied Flyway migrations are immutable (anything already on main)
if [[ "$rel_l" =~ (^|/)db/migration/v[^/]+__[^/]+[.]sql$ ]]; then
  for ref in main origin/main; do
    # exact spelling first; a case-variant path is looked up in the tree case-insensitively
    if git -C "$PROJECT_DIR" cat-file -e "$ref:$rel" 2>/dev/null \
       || git -C "$PROJECT_DIR" ls-tree -r --name-only "$ref" 2>/dev/null | grep -i 'db/migration/' | grep -iqxF -- "$rel"; then
      block "'$rel' is an applied Flyway migration (exists on $ref) and is immutable - add a new V<next>__<name>.sql (expand -> migrate -> contract)"
    fi
  done
fi

# 4. generated code is never hand-edited
if [[ "$rel_l" =~ (^|/)(generated|__generated__|build/generated|[.]gradle|node_modules|dist|build)/ ]] \
   || [[ "$rel_l" =~ ^(web/src/api/generated|android/[^/]*/src/main/.*/generated|backend/src/main/java/.*/generated)/ ]]; then
  block "'$rel' is generated/build output and is never hand-edited - change the OpenAPI contract or the generator"
fi

# 5. the eval baseline moves only through /eval --rebaseline by ai-eval-engineer
if [ "$rel_l" = "eval/baseline.json" ]; then
  if [ "$agent" != "ai-eval-engineer" ] && ! is_truthy "$(state_get rebaseline_allowed)" && ! is_truthy "${MASROOF_REBASELINE:-}"; then
    block "eval/baseline.json may only be updated by ai-eval-engineer via '/eval --rebaseline \"<reason>\"' (sets rebaseline_allowed)"
  fi
fi

# 6. the guardrails themselves, and the source spec. settings.local.json is included because it can
#    carry disableAllHooks or env overrides (MASROOF_SKIP_STOP_GATE) that switch every guard off.
case "$rel_l" in
  .claude/hooks|.claude/hooks/*|.claude/settings.json|.claude/settings.local.json|docs/prompt.md)
    if [ "$unlocked" != true ]; then
      block "'$rel' is protected (hooks/settings/settings.local/spec). Unlock deliberately with 'make hooks-unlock' (or via /retro, /sdlc-selftest), edit, then 'make hooks-lock'. Every change must be noted in docs/DECISIONS.md."
    fi ;;
esac

# 7. role write-scopes: docs-only agents may not touch source (patterns compared lower-case too)
if [ -n "$agent" ] && [ -f "$CONTRACTS_FILE" ] && have_jq; then
  scopes="$(jq -r --arg a "$agent" '.agents[$a].write_scope // [] | .[]' "$CONTRACTS_FILE" 2>/dev/null)"
  if [ -n "$scopes" ]; then
    ok=false
    while IFS= read -r pat; do
      [ -z "$pat" ] && continue
      # shellcheck disable=SC2254
      case "$rel_l" in ${pat,,}) ok=true; break ;; esac
    done <<< "$scopes"
    [ "$ok" = true ] || block "agent '$agent' may only write within: $(printf '%s' "$scopes" | tr '\n' ' ')- '$rel' is out of scope. Report the needed change in your output instead."
  fi
fi

exit 0
