#!/usr/bin/env bash
# PreToolUse(Edit|Write|MultiEdit|NotebookEdit): protect files that must not be edited by agents.
# Exit 2 = block.
HOOK_NAME=protect-files
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
read_input
fp="$(json_get '.tool_input.file_path')"
[ -z "$fp" ] && fp="$(json_get '.tool_input.notebook_path')"
[ -z "$fp" ] && exit 0
rel="$(rel_to_project "$fp")"
base="$(basename "$rel")"
agent="$(json_get '.agent_type')"

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
  *.jks|*.keystore|*.p12|*.pfx|*.key|id_rsa|id_ed25519) block "editing key material '$rel' is forbidden" ;;
esac
case "$rel" in
  secrets/*|*/secrets/*) block "'$rel' is under a secrets/ directory - forbidden" ;;
esac

# 3. applied Flyway migrations are immutable (anything already on main)
if printf '%s' "$rel" | grep -Eq '(^|/)db/migration/V[^/]+__[^/]+\.sql$'; then
  for ref in main origin/main; do
    if git -C "$PROJECT_DIR" cat-file -e "$ref:$rel" 2>/dev/null; then
      block "'$rel' is an applied Flyway migration (exists on $ref) and is immutable - add a new V<next>__<name>.sql (expand -> migrate -> contract)"
    fi
  done
fi

# 4. generated code is never hand-edited
if printf '%s' "$rel" | grep -Eq '(^|/)(generated|__generated__|build/generated|\.gradle|node_modules|dist|build)/' \
   || printf '%s' "$rel" | grep -Eq '^(web/src/api/generated|android/[^/]*/src/main/.*/generated|backend/src/main/java/.*/generated)/'; then
  block "'$rel' is generated/build output and is never hand-edited - change the OpenAPI contract or the generator"
fi

# 5. the eval baseline moves only through /eval --rebaseline by ai-eval-engineer
if [ "$rel" = "eval/baseline.json" ]; then
  if [ "$agent" != "ai-eval-engineer" ] && ! is_truthy "$(state_get rebaseline_allowed)" && ! is_truthy "${MASROOF_REBASELINE:-}"; then
    block "eval/baseline.json may only be updated by ai-eval-engineer via '/eval --rebaseline \"<reason>\"' (sets rebaseline_allowed)"
  fi
fi

# 6. the guardrails themselves, and the source spec
case "$rel" in
  .claude/hooks/*|.claude/settings.json|docs/PROMPT.md)
    if [ "$unlocked" != true ]; then
      block "'$rel' is protected (hooks/settings/spec). Unlock deliberately with 'make hooks-unlock' (or via /retro, /sdlc-selftest), edit, then 'make hooks-lock'. Every change must be noted in docs/DECISIONS.md."
    fi ;;
esac

# 7. role write-scopes: docs-only agents may not touch source
if [ -n "$agent" ] && [ -f "$CONTRACTS_FILE" ] && have_jq; then
  scopes="$(jq -r --arg a "$agent" '.agents[$a].write_scope // [] | .[]' "$CONTRACTS_FILE" 2>/dev/null)"
  if [ -n "$scopes" ]; then
    ok=false
    while IFS= read -r pat; do
      [ -z "$pat" ] && continue
      # shellcheck disable=SC2254
      case "$rel" in $pat) ok=true; break ;; esac
    done <<< "$scopes"
    [ "$ok" = true ] || block "agent '$agent' may only write within: $(printf '%s' "$scopes" | tr '\n' ' ')- '$rel' is out of scope. Report the needed change in your output instead."
  fi
fi

exit 0
