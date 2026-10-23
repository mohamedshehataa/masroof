#!/usr/bin/env bash
# REQ: REQ-OPS-023
# Backend fast-gate probes (STORY-002). Run from anywhere: bash scripts/quality/tests/backend-gate-probes.sh
# Called by scripts/make/check.sh after the check-fast step (no `test-` prefix, so the sdlc gate's run.sh skips it).
# Plants throwaway Java files under backend/src/main/java/eg/masroof/, removes them on EXIT, and proves that
# `make check-fast` / `./gradlew checkFast` reject them. Writes JUnit XML for `make trace` to
# backend/build/test-results/gateProbes/TEST-BackendGateProbes.xml. Exit 0 only when every case passes.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../../env.sh"
cd "$PROJECT_DIR" || exit 1
export LC_ALL=C.UTF-8

plant_dir="backend/src/main/java/eg/masroof"
planted=()
logdir="$(mktemp -d)"
xml_out="backend/build/test-results/gateProbes/TEST-BackendGateProbes.xml"
case_names=(); case_ok=(); case_detail=()
# created_root: the shallowest directory of $plant_dir that did not exist yet; cleanup removes it if left empty.
created_root=""
d="$plant_dir"
while [ ! -d "$d" ] && [ "$d" != backend ] && [ "$d" != . ]; do created_root="$d"; d="$(dirname "$d")"; done
cleanup() {
  local f
  for f in "${planted[@]}"; do rm -f "$f"; done
  if [ -n "$created_root" ] && [ -d "$created_root" ]; then find "$created_root" -depth -type d -empty -delete; fi
  rm -rf "$logdir"
}
trap cleanup EXIT INT TERM

record() { # record <name> <ok|fail> <detail>
  case_names+=("$1"); case_ok+=("$2"); case_detail+=("$3")
  if [ "$2" = ok ]; then printf 'PASS  %s\n' "$1"; else printf 'FAIL  %s: %s\n' "$1" "$3"; fi
}
show_tail() { tail -n 25 "$1" | sed 's/^/      /'; }
plant() { # plant <FileName.java>  (content on stdin)
  local f="$plant_dir/$1"
  if [ -e "$f" ]; then echo "refusing to overwrite existing $f" >&2; return 1; fi
  mkdir -p "$plant_dir"; planted+=("$f"); cat > "$f"
}
unplant() { rm -f "$plant_dir/$1"; }
# `\&` keeps bash 5.2's patsub_replacement from expanding `&` to the matched text.
xml_escape() {
  local s="$1"
  s="${s//&/\&amp;}"; s="${s//</\&lt;}"; s="${s//>/\&gt;}"; s="${s//\"/\&quot;}"
  printf '%s' "$s"
}

if [ ! -x backend/gradlew ]; then
  echo "FAIL  backend/gradlew missing or not executable"; exit 1
fi
status_before="$(git status --porcelain -- backend/src 2>/dev/null)"

# Case 5, also the warm-up run for case 1: `make check-fast` with no MODULE runs the sdlc and backend gates.
REQ_OPS_023_make_check_fast_dispatches_to_backend() {
  local name="REQ-OPS-023 make check-fast without MODULE runs sdlc and backend gates and exits 0"
  local log="$logdir/all.log" rc
  env -u MODULE make check-fast > "$log" 2>&1; rc=$?
  if [ "$rc" -ne 0 ]; then record "$name" fail "exit $rc"; show_tail "$log"
  elif ! grep -q '===== check-fast: sdlc =====' "$log"; then record "$name" fail "no sdlc gate header"; show_tail "$log"
  elif ! grep -q '===== check-fast: backend =====' "$log"; then record "$name" fail "no backend gate header"; show_tail "$log"
  elif ! grep -q 'OK   backend: ./gradlew checkFast' "$log"; then
    record "$name" fail "backend checkFast step not OK"; show_tail "$log"
  else record "$name" ok ""; fi
}

# Case 1: a warm `make check-fast MODULE=backend` exits 0 in under 3 minutes (S8 budget).
REQ_OPS_023_warm_check_fast_green_under_3_min() {
  local name="REQ-OPS-023 warm check-fast green < 3 min" log="$logdir/warm.log" rc start dur
  start=$SECONDS
  make check-fast MODULE=backend > "$log" 2>&1; rc=$?
  dur=$((SECONDS - start))
  if [ "$rc" -ne 0 ]; then record "$name" fail "exit $rc after ${dur}s"; show_tail "$log"
  elif ! grep -q 'OK   backend: ./gradlew checkFast' "$log"; then
    record "$name" fail "backend checkFast step not run"; show_tail "$log"
  elif [ "$dur" -ge 180 ]; then record "$name" fail "took ${dur}s (budget 180s)"
  else record "$name" ok "${dur}s"; fi
}

# expect_gate_rejects <case name> <planted file> <log> <ERE the output must match> <command...>
expect_gate_rejects() {
  local name="$1" file="$2" log="$3" re="$4" rc
  shift 4
  "$@" > "$log" 2>&1; rc=$?
  if [ "$rc" -eq 0 ]; then record "$name" fail "gate exited 0 with $file planted"; show_tail "$log"
  elif ! grep -qF "$file" "$log"; then record "$name" fail "exit $rc but output does not name $file"; show_tail "$log"
  elif ! grep -Eq "$re" "$log"; then record "$name" fail "exit $rc but output does not match /$re/"; show_tail "$log"
  else record "$name" ok "exit $rc"; fi
}

# Case 2: a tab-indented (otherwise valid) Java file fails the gate, naming the file and the task.
REQ_OPS_023_tab_indentation_rejected() {
  local name="REQ-OPS-023 tab indentation rejected"
  if ! printf 'package eg.masroof;\n\n/** Gate probe: tab indentation. */\nfinal class GateProbeTabs {\n\tprivate GateProbeTabs() {}\n}\n' \
      | plant GateProbeTabs.java; then
    record "$name" fail "could not plant"; return
  fi
  expect_gate_rejects "$name" GateProbeTabs.java "$logdir/tabs.log" \
    'spotless|checkstyleMain|compileJava' make check-fast MODULE=backend
  unplant GateProbeTabs.java
}

# Case 3: an unused import fails compileJava (Error Prone RemoveUnusedImports), naming the file.
REQ_OPS_023_unused_import_rejected() {
  local name="REQ-OPS-023 unused import rejected"
  if ! plant GateProbeImport.java <<'JAVA'
package eg.masroof;

import java.util.List;

/** Gate probe: unused import. */
final class GateProbeImport {
  private GateProbeImport() {}
}
JAVA
  then record "$name" fail "could not plant"; return; fi
  expect_gate_rejects "$name" GateProbeImport.java "$logdir/import.log" \
    'compileJava' make check-fast MODULE=backend
  unplant GateProbeImport.java
}

# Case 4: dereferencing a @Nullable parameter fails compilation with [NullAway] and File.java:<line>.
REQ_OPS_023_nullaway_rejects_nullable_dereference() {
  local name="REQ-OPS-023 NullAway rejects nullable dereference" log="$logdir/null.log" last
  if ! plant GateProbeNull.java <<'JAVA'
package eg.masroof;

import org.jspecify.annotations.Nullable;

/** Gate probe: nullable dereference. */
final class GateProbeNull {
  private GateProbeNull() {}

  static int len(@Nullable String s) {
    return s.length();
  }
}
JAVA
  then record "$name" fail "could not plant"; return; fi
  expect_gate_rejects "$name" GateProbeNull.java "$log" 'GateProbeNull\.java:[0-9]+' \
    bash -c 'cd backend && ./gradlew --console=plain -q checkFast'
  last=$((${#case_ok[@]} - 1))
  if [ "${case_ok[$last]}" = ok ] && ! grep -qF '[NullAway]' "$log"; then
    case_ok[last]=fail; case_detail[last]="no [NullAway] diagnostic"
    printf 'FAIL  %s: no [NullAway] diagnostic\n' "$name"; show_tail "$log"
  fi
  unplant GateProbeNull.java
}

REQ_OPS_023_make_check_fast_dispatches_to_backend
REQ_OPS_023_warm_check_fast_green_under_3_min
REQ_OPS_023_tab_indentation_rejected
REQ_OPS_023_unused_import_rejected
REQ_OPS_023_nullaway_rejects_nullable_dereference

# The probes leave backend/src exactly as they found it.
cleanup_name="REQ-OPS-023 gate probes leave backend/src unchanged"
for f in "${planted[@]}"; do rm -f "$f"; done
if [ "$(git status --porcelain -- backend/src 2>/dev/null)" = "$status_before" ]; then record "$cleanup_name" ok ""
else record "$cleanup_name" fail "git status of backend/src changed"; fi

# JUnit XML for `make trace`.
failures=0
for ok in "${case_ok[@]}"; do [ "$ok" = ok ] || failures=$((failures + 1)); done
mkdir -p "$(dirname "$xml_out")"
{
  printf '<?xml version="1.0" encoding="UTF-8"?>\n'
  printf '<testsuite name="BackendGateProbes" tests="%s" failures="%s" errors="0" skipped="0">\n' \
    "${#case_names[@]}" "$failures"
  for i in "${!case_names[@]}"; do
    printf '  <testcase classname="BackendGateProbes" name="%s"' "$(xml_escape "${case_names[$i]}")"
    if [ "${case_ok[$i]}" = ok ]; then printf '/>\n'
    else printf '>\n    <failure message="%s"/>\n  </testcase>\n' "$(xml_escape "${case_detail[$i]}")"; fi
  done
  printf '</testsuite>\n'
} > "$xml_out"

printf '\nbackend gate probes: %s case(s), %s failed\n' "${#case_names[@]}" "$failures"
[ "$failures" -eq 0 ]
