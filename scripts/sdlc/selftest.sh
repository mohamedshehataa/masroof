#!/usr/bin/env bash
# make selftest / /sdlc-selftest - prove every SDLC guard actually blocks (PROMPT.md S15 step 6).
#
# Each check plants a real violation (in a temp git repo, a temp file, or a backed-up copy of a real
# file), runs the REAL guard (hook script, make target, eval runner, oasdiff), records PROVEN only when
# the block/failure was observed, and reverts. Nothing here edits the guards themselves.
# Writes docs/SDLC_SELFTEST.md. Exit 0 = all PROVEN; 3 = some PENDING (module not present yet);
# 1 = a guard FAILED to block (Phase 0 is not done).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$PROJECT_DIR" || exit 1
export LC_ALL=C.UTF-8
HOOKS=.claude/hooks
REPORT=docs/SDLC_SELFTEST.md
TMP="$(mktemp -d)"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT
# isolated state so the self-test never touches the live .claude/state.json
export MASROOF_STATE_FILE="$TMP/state.json" MASROOF_STATE_DIR="$TMP/state"
mkdir -p "$MASROOF_STATE_DIR" && cp .claude/state.example.json "$MASROOF_STATE_FILE"
export MASROOF_SKIP_STOP_GATE=""  # make sure no bypass leaks in from the environment
unset MASROOF_HOOKS_UNLOCK MASROOF_REBASELINE MASROOF_RELEASE_ACTIVE 2>/dev/null || true

rows=(); failed=0; pending=0; proven=0
record() { # record <id> <guard> <violation planted> <command> <observed> <PROVEN|FAILED|PENDING>
  local obs="${5//|/\\|}"
  rows+=("| $1 | $2 | $3 | \`$4\` | $obs | **$6** |")
  case "$6" in PROVEN) proven=$((proven + 1)) ;; FAILED) failed=$((failed + 1)) ;; PENDING) pending=$((pending + 1)) ;; esac
  printf '%-8s %s %s\n' "$6" "$1" "$2"
}
RC=0; ERR=""
run_hook() { # run_hook <hook> <json>  (stdout discarded; blocks speak on stderr + exit code)
  printf '%s' "$2" | bash "$HOOKS/$1" >/dev/null 2>"$TMP/err"; RC=$?
  ERR="$(tr -d '\r' < "$TMP/err" | tr '\n' ' ' | cut -c1-220)"
}
temp_repo() {
  local d="$TMP/repo-$RANDOM"; mkdir -p "$d"
  (cd "$d" && git init -q -b main . && git config user.email t@t && git config user.name t && git config commit.gpgsign false && git config core.autocrlf false && echo x > README.md && git add . && git commit -q -m init) >/dev/null 2>&1
  printf '%s' "$d"
}
verdict() { [ "$RC" -eq "$1" ] && echo PROVEN || echo FAILED; }

echo "== SDLC self-test ($(date -u +%Y-%m-%dT%H:%M:%SZ))"

# ---------------------------------------------------------------- G1 Stop hook blocks on a failing test
plant=".claude/hooks/tests/test-zz-selftest-plant.sh"
printf '#!/usr/bin/env bash\n. "$(dirname "${BASH_SOURCE[0]}")/lib-test.sh"\nRC=1; assert_rc 0 "SELFTEST planted failing test"\nfinish\n' > "$plant"
start=$(date +%s)
run_hook stop-gate.sh '{"hook_event_name":"Stop","stop_hook_active":false,"last_assistant_message":"done"}'
dur=$(( $(date +%s) - start ))
rm -f "$plant"
record G1 "Stop hook (stop-gate.sh) blocks completion while a test fails" "added a failing hook test, dirty .claude/ => module sdlc" "stop-gate.sh <- Stop JSON (runs make check-fast MODULE=sdlc)" "exit $RC in ${dur}s; stderr: ${ERR:0:120}" "$(verdict 2)"
rm -f .claude/hooks/tests/results.tsv 2>/dev/null

# ---------------------------------------------------------------- G2 scan-secrets blocks a fake Anthropic key
fake_key="sk-ant-api03-$(printf 'SELFTEST%.0s' {1..6})ABCDEFGH"
run_hook scan-secrets.sh "$(J_P="$PWD/backend/src/main/resources/application.yml" J_C="anthropic.key: $fake_key" jq -n '{hook_event_name:"PreToolUse",tool_name:"Write",tool_input:{file_path:env.J_P,content:env.J_C}}')"
if [[ "$ERR" == *"$fake_key"* ]]; then leak=" (LEAKED THE KEY IN STDERR)"; RC=99; else leak=""; fi
record G2 "scan-secrets.sh blocks writing a fake Anthropic key" "Write application.yml containing sk-ant-api03-..." "scan-secrets.sh <- PreToolUse Write JSON" "exit $RC; ${ERR:0:100}$leak" "$(verdict 2)"

# ---------------------------------------------------------------- G3 protect-files blocks editing an applied migration
R="$(temp_repo)"
mkdir -p "$R/backend/src/main/resources/db/migration"
echo "create table household(id uuid primary key);" > "$R/backend/src/main/resources/db/migration/V1__init.sql"
(cd "$R" && git add . && git commit -q -m "feat(db): baseline [PHASE-0]")
real_mig="backend/src/main/resources/db/migration/V1__init.sql"
where="temp repo"
if git rev-parse --verify -q main >/dev/null 2>&1 && git cat-file -e "main:$real_mig" 2>/dev/null; then where="real repo (V1 on main)"; R="$PWD"; fi
CLAUDE_PROJECT_DIR="$R" run_hook protect-files.sh "$(J_P="$R/$real_mig" jq -n '{hook_event_name:"PreToolUse",tool_name:"Edit",tool_input:{file_path:env.J_P,old_string:"a",new_string:"b"}}')"
record G3 "protect-files.sh blocks editing an applied Flyway migration" "Edit V1__init.sql that exists on main ($where)" "protect-files.sh <- PreToolUse Edit JSON" "exit $RC; ${ERR:0:120}" "$(verdict 2)"

# ---------------------------------------------------------------- G4 ArchUnit fails on a double money field in the domain
if [ -x backend/gradlew ] && grep -rqs "archTest" backend/build.gradle.kts; then
  dom_dir="$(find backend/src/main/java -type d -name domain | head -1)"
  if [ -n "$dom_dir" ]; then
    pf="$dom_dir/SelftestPlant.java"
    pkg="$(printf '%s' "$dom_dir" | sed -E 's#backend/src/main/java/##; s#/#.#g')"
    printf 'package %s;\n\npublic final class SelftestPlant {\n  public double amount;\n  private SelftestPlant() {}\n}\n' "$pkg" > "$pf"
    (cd backend && ./gradlew --console=plain -q archTest > "$TMP/arch.log" 2>&1); RC=$?
    rm -f "$pf"
    record G4 "ArchUnit fails on a double money field in the domain" "added domain/SelftestPlant.java with 'public double amount'" "cd backend && ./gradlew archTest" "exit $RC; $(grep -m1 -iE 'double|money|violat' "$TMP/arch.log" | cut -c1-120)" "$([ "$RC" -ne 0 ] && echo PROVEN || echo FAILED)"
  else
    record G4 "ArchUnit fails on a double money field in the domain" "-" "-" "no domain package found" "FAILED"
  fi
else
  record G4 "ArchUnit fails on a double money field in the domain" "-" "cd backend && ./gradlew archTest" "backend module / archTest task not present yet (arrives with STORY-002)" "PENDING"
fi

# ---------------------------------------------------------------- G5 oasdiff fails when a response field is removed
if ! git rev-parse --verify -q main >/dev/null 2>&1 || ! git cat-file -e main:contracts/openapi.yaml 2>/dev/null; then
  record G5 "oasdiff fails on a removed response field" "-" "scripts/quality/oasdiff.sh main" "contracts/openapi.yaml is not on main yet (commit first)" "PENDING"
elif ! command -v oasdiff >/dev/null 2>&1 && ! docker info >/dev/null 2>&1; then
  record G5 "oasdiff fails on a removed response field" "-" "scripts/quality/oasdiff.sh main" "neither oasdiff binary nor docker available" "PENDING"
else
  cp contracts/openapi.yaml "$TMP/openapi.bak"
  "$PYTHON" - <<'PY'
import yaml, io
p = "contracts/openapi.yaml"
d = yaml.safe_load(open(p, encoding="utf-8"))
# find the first response schema with a required property and drop one property
def walk(obj):
    if isinstance(obj, dict):
        if obj.get("type") == "object" and isinstance(obj.get("properties"), dict) and obj.get("required"):
            prop = obj["required"][0]
            if prop in obj["properties"]:
                del obj["properties"][prop]
                obj["required"].remove(prop)
                return prop
        for v in obj.values():
            r = walk(v)
            if r: return r
    elif isinstance(obj, list):
        for v in obj:
            r = walk(v)
            if r: return r
    return None
schemas = d.get("components", {}).get("schemas", {})
removed = None
for name in ("Health", "HealthResponse", "Expense", "Money"):
    if name in schemas:
        removed = walk(schemas[name])
        if removed: print(f"removed property '{removed}' from schema {name}"); break
if not removed:
    removed = walk(d); print(f"removed property '{removed}'")
yaml.safe_dump(d, open(p, "w", encoding="utf-8"), allow_unicode=True, sort_keys=False)
PY
  bash scripts/quality/oasdiff.sh main > "$TMP/oas.log" 2>&1; RC=$?
  cp "$TMP/openapi.bak" contracts/openapi.yaml
  record G5 "oasdiff fails on a removed response field" "removed a required property from a response schema in contracts/openapi.yaml" "scripts/quality/oasdiff.sh main" "exit $RC; $(grep -m1 -iE 'breaking|removed|error' "$TMP/oas.log" | cut -c1-120)" "$(verdict 1)"
fi

# ---------------------------------------------------------------- G6 eval gate fails when Arabic-Indic digits break
rp=eval/runners/reference_parser.py
if [ -f "$rp" ]; then
  "$PYTHON" eval/runners/run.py --adapter reference --quiet > "$TMP/eval0.log" 2>&1; rc0=$?
  cp "$rp" "$TMP/rp.bak"
  sed -i 's/"٢": "2", *//' "$rp"
  "$PYTHON" eval/runners/run.py --adapter reference --suite whatsapp_text --quiet > "$TMP/eval1.log" 2>&1; RC=$?
  cp "$TMP/rp.bak" "$rp"
  obs="baseline run exit $rc0; broken run exit $RC; $(grep -m1 -E 'FAIL amount|arabic|wa-0' "$TMP/eval1.log" | cut -c1-110)"
  v=PROVEN; { [ "$rc0" -ne 0 ] || [ "$RC" -ne 1 ]; } && v=FAILED
  record G6 "eval gate fails when Arabic-Indic digits break" "removed the Arabic-Indic '2' from DIGIT_MAP in the reference parser" "python eval/runners/run.py --adapter reference --suite whatsapp_text" "$obs" "$v"
else
  record G6 "eval gate fails when Arabic-Indic digits break" "-" "-" "eval runner not present" "PENDING"
fi

# ---------------------------------------------------------------- G7 adversarial gate fails when the parser obeys an injected instruction
if [ -f "$rp" ]; then
  cp "$rp" "$TMP/rp.bak"
  # plant: the parser stops recognising instructions and treats the word "income" as an income signal
  sed -i -E 's/^INSTRUCTION_PATTERNS = \[re\.compile\(p, re\.IGNORECASE\) for p in \(/INSTRUCTION_PATTERNS = [re.compile(p, re.IGNORECASE) for p in () if False] or [re.compile(p, re.IGNORECASE) for p in (/' "$rp"
  "$PYTHON" - "$rp" <<'PY'
import sys, re
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
s = s.replace('INCOME_KEYWORDS = ("received",', 'INCOME_KEYWORDS = ("income", "as income", "دخل", "received",', 1)
s = re.sub(r"INSTRUCTION_PATTERNS = \[.*?\n\)\]", "INSTRUCTION_PATTERNS = []", s, count=1, flags=re.S)
open(p, "w", encoding="utf-8").write(s)
PY
  "$PYTHON" eval/runners/run.py --adapter reference --suite adversarial --quiet > "$TMP/eval2.log" 2>&1; RC=$?
  cp "$TMP/rp.bak" "$rp"
  record G7 "adversarial gate fails when an injected instruction is obeyed" "disabled INSTRUCTION_PATTERNS and made 'income' an income keyword in the reference parser" "python eval/runners/run.py --adapter reference --suite adversarial" "exit $RC; $(grep -m1 -iE 'obeyed|adversarial|FAIL' "$TMP/eval2.log" | cut -c1-120)" "$(verdict 1)"
fi
"$PYTHON" eval/runners/run.py --adapter reference --quiet > "$TMP/eval3.log" 2>&1 && echo "  (reference parser restored: eval green again)" || echo "  WARNING: eval not green after restore - inspect $rp"

# ---------------------------------------------------------------- G8 SubagentStop blocks a reply without STATUS
run_hook check-output-contract.sh "$(jq -n '{hook_event_name:"SubagentStop",agent_type:"qa-verifier",stop_hook_active:false,last_assistant_message:"All criteria look fine to me."}')"
record G8 "SubagentStop (check-output-contract.sh) blocks a subagent reply without STATUS" "qa-verifier final message with no STATUS line" "check-output-contract.sh <- SubagentStop JSON" "exit $RC; ${ERR:0:120}" "$(verdict 2)"

# ---------------------------------------------------------------- G9 guard-bash blocks git push --force to main
run_hook guard-bash.sh "$(jq -n '{hook_event_name:"PreToolUse",tool_name:"Bash",tool_input:{command:"git push --force origin main"},cwd:"."}')"
record G9 "guard-bash.sh blocks git push --force to main" "Bash: git push --force origin main" "guard-bash.sh <- PreToolUse Bash JSON" "exit $RC; ${ERR:0:120}" "$(verdict 2)"

# ---------------------------------------------------------------- G10 a shell write to a hook is undone (REQ-OPS-011)
R="$(temp_repo)"
mkdir -p "$R/.claude/hooks" && printf '#!/usr/bin/env bash\necho guard\n' > "$R/.claude/hooks/guard-bash.sh"
(cd "$R" && git add . && git commit -q -m "feat: guard [PHASE-0]")
printf '%s' '{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"x"}}' | CLAUDE_PROJECT_DIR="$R" bash "$HOOKS/restore-protected.sh" --snapshot >/dev/null 2>&1
printf 'exit 0\n' > "$R/.claude/hooks/guard-bash.sh"   # the write guard-bash would have blocked, done directly
post_out="$(printf '%s' '{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"x"}}' | CLAUDE_PROJECT_DIR="$R" bash "$HOOKS/restore-protected.sh" 2>/dev/null)"
if [[ "$post_out" == *'"decision":"block"'* ]] && grep -q 'echo guard' "$R/.claude/hooks/guard-bash.sh"; then v=PROVEN; else v=FAILED; fi
record G10 "restore-protected.sh undoes a shell write to a hook while locked" "overwrote .claude/hooks/guard-bash.sh in a temp repo between the PreToolUse snapshot and the PostToolUse check" "restore-protected.sh --snapshot / restore-protected.sh <- Bash JSON" "decision: $(printf '%s' "$post_out" | cut -c1-90); file restored: $(grep -c 'echo guard' "$R/.claude/hooks/guard-bash.sh")" "$v"

# ---------------------------------------------------------------- extras that the spec implies
run_hook protect-files.sh "$(J_P="$PWD/.env" jq -n '{hook_event_name:"PreToolUse",tool_name:"Write",tool_input:{file_path:env.J_P,content:"X=1"}}')"
record X1 "protect-files.sh blocks writing .env" "Write .env" "protect-files.sh <- Write JSON" "exit $RC; ${ERR:0:100}" "$(verdict 2)"
run_hook protect-files.sh "$(J_P="$PWD/.claude/hooks/guard-bash.sh" jq -n '{hook_event_name:"PreToolUse",tool_name:"Edit",tool_input:{file_path:env.J_P,old_string:"a",new_string:"b"}}')"
record X2 "protect-files.sh blocks editing the hooks while locked" "Edit .claude/hooks/guard-bash.sh" "protect-files.sh <- Edit JSON" "exit $RC; ${ERR:0:100}" "$(verdict 2)"
run_hook protect-files.sh "$(J_P="$PWD/backend/src/main/java/X.java" jq -n '{hook_event_name:"PreToolUse",tool_name:"Write",tool_input:{file_path:env.J_P,content:"x"},agent_type:"product-analyst"}')"
record X3 "protect-files.sh enforces role write scopes" "product-analyst writes backend source" "protect-files.sh <- Write JSON (agent_type product-analyst)" "exit $RC; ${ERR:0:100}" "$(verdict 2)"
run_hook guard-bash.sh "$(jq -n '{hook_event_name:"PreToolUse",tool_name:"Bash",tool_input:{command:"cat .env"},cwd:"."}')"
record X4 "guard-bash.sh blocks printing .env" "Bash: cat .env" "guard-bash.sh <- Bash JSON" "exit $RC; ${ERR:0:100}" "$(verdict 2)"
run_hook guard-bash.sh "$(jq -n '{hook_event_name:"PreToolUse",tool_name:"Bash",tool_input:{command:"git commit --no-verify -m x"},cwd:"."}')"
record X5 "guard-bash.sh blocks --no-verify" "Bash: git commit --no-verify" "guard-bash.sh <- Bash JSON" "exit $RC; ${ERR:0:100}" "$(verdict 2)"
if [ -f scripts/quality/commit-msg-lint.sh ]; then
  printf 'fixed stuff\n' > "$TMP/msg"; bash scripts/quality/commit-msg-lint.sh "$TMP/msg" > "$TMP/cm.log" 2>&1; RC=$?
  record X6 "commit-msg-lint.sh rejects a commit without Conventional Commits + story tag" "message 'fixed stuff'" "scripts/quality/commit-msg-lint.sh <msgfile>" "exit $RC; $(head -1 "$TMP/cm.log" | cut -c1-100)" "$([ "$RC" -ne 0 ] && echo PROVEN || echo FAILED)"
fi

# ---------------------------------------------------------------- report
{
  echo "# SDLC self-test"
  echo
  echo "Proof that every guard actually blocks (PROMPT.md S15 step 6). Generated by \`make selftest\` (\`scripts/sdlc/selftest.sh\`) on $(date -u +%Y-%m-%dT%H:%M:%SZ), commit \`$(git rev-parse --short HEAD 2>/dev/null || echo none)\`."
  echo "Each row plants a violation, runs the real guard, records what was observed, and reverts. PROVEN = the block/failure was observed. PENDING = the module the guard protects does not exist yet (re-run after it lands). FAILED = the guard did not block."
  echo
  echo "| # | guard | violation planted | command | observed | result |"
  echo "|---|---|---|---|---|---|"
  printf '%s\n' "${rows[@]}"
  echo
  echo "**Summary:** $proven PROVEN, $pending PENDING, $failed FAILED."
  if [ "$failed" -gt 0 ]; then echo; echo "Verdict: **RED** - a guard failed to block. Phase 0 is not done.";
  elif [ "$pending" -gt 0 ]; then echo; echo "Verdict: **AMBER** - every present guard is proven; re-run once the pending modules exist.";
  else echo; echo "Verdict: **GREEN** - every guard is proven."; fi
} > "$REPORT"
echo
echo "report: $REPORT ($proven proven, $pending pending, $failed failed)"
[ "$failed" -gt 0 ] && exit 1
[ "$pending" -gt 0 ] && exit 3
exit 0
