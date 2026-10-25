#!/usr/bin/env bash
# STORY-006 gates over contracts/openapi.yaml, run in throwaway copies of the repo's scripts and contract.
#   REQ-OPS-051 contract lint + oasdiff breaking-change gate (incl. the breaking-change label + ADR exception)
#   REQ-OPS-052 `make docs-api` regenerates docs/API.md; `make check` fails on a stale committed file
# Appends suite<TAB>ok|fail<TAB>name<TAB>detail to $QUALITY_TEST_RESULTS_TSV when set (run.sh does).
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
# shellcheck source=../../env.sh
. "$repo/scripts/env.sh"
suite="test-contract-gates"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0; fail=0
record() { [ -n "${QUALITY_TEST_RESULTS_TSV:-}" ] && printf '%s\t%s\t%s\t%s\n' "$suite" "$1" "$2" "${3//$'\n'/ }" >> "$QUALITY_TEST_RESULTS_TSV"; return 0; }
ok()  { pass=$((pass + 1)); printf 'PASS  %s\n' "$1"; record ok "$1" ""; }
bad() { fail=$((fail + 1)); printf 'FAIL  %s\n      %s\n' "$1" "$2"; record fail "$1" "$2"; }

g() { local d="$1"; shift; git -C "$d" -c core.autocrlf=false -c user.name=t -c user.email=t@t -c commit.gpgsign=false "$@"; }
# new_repo <dir>: git repo with the real oasdiff gate script and the real contract committed on main.
new_repo() {
  local d="$1"
  mkdir -p "$d/scripts/quality" "$d/contracts" "$d/docs/adr"
  cp "$repo/scripts/quality/oasdiff.sh" "$d/scripts/quality/"
  cp "$repo/contracts/openapi.yaml" "$repo/contracts/redocly.yaml" "$d/contracts/"
  git -C "$d" init -q -b main
  g "$d" add -A; g "$d" commit -qm "base [STORY-006]"
}
# remove_health_version <contract>: drops the Health.version property and its `required` entry (a breaking response change)
remove_health_version() {
  awk '/^    Health:/{h=1} /^    AndroidRelease:/{h=0}
       h && /^        version:$/ {skip=3}
       skip>0 {skip--; next} {print}' "$1" | sed 's/\[status, version, gitSha/[status, gitSha/' > "$1.new" && mv "$1.new" "$1"
}
# add_health_optional <contract>: adds an optional response property `instanceId` to Health (non-breaking)
add_health_optional() {
  awk '{print} /^          description: Server time now \(UTC\)\.$/ && !d {print "        instanceId:"; print "          type: string"; print "          description: Optional instance identifier."; d=1}' "$1" > "$1.new" && mv "$1.new" "$1"
}

echo "-- REQ-OPS-051"
W="$tmp/diff"; new_repo "$W"

g "$W" checkout -q -b additive
add_health_optional "$W/contracts/openapi.yaml"
g "$W" commit -qam "add optional [STORY-006]"
OUT="$(cd "$W" && bash scripts/quality/oasdiff.sh main 2>&1)"; RC=$?
if [ "$RC" -eq 0 ]; then ok "REQ-OPS-051 contract-diff exits 0 when only an optional property was added"
else bad "REQ-OPS-051 contract-diff exits 0 when only an optional property was added" "rc=$RC: $OUT"; fi

g "$W" checkout -q main; g "$W" checkout -q -b removed
remove_health_version "$W/contracts/openapi.yaml"
g "$W" commit -qam "remove Health.version [STORY-006]"
OUT="$(cd "$W" && bash scripts/quality/oasdiff.sh main 2>&1)"; RC=$?
if [ "$RC" -eq 1 ] && [[ "$OUT" == *version* ]]; then ok "REQ-OPS-051 contract-diff exits non-zero and names Health.version when it was removed"
else bad "REQ-OPS-051 contract-diff exits non-zero and names Health.version when it was removed" "want rc=1 naming 'version'; got rc=$RC: $OUT"; fi

# Exception: label `breaking-change` (env PR_LABELS) AND a new docs/adr/NNNN-*.md that references the changed path.
run_diff() { (cd "$W" && PR_LABELS="$1" bash scripts/quality/oasdiff.sh main 2>&1); }
printf '# 0099. Remove Health.version\n\nThe `/health` response no longer carries `version`; clients read it elsewhere.\n' > "$W/docs/adr/0099-remove-health-version.md"
g "$W" add -A; g "$W" commit -qm "adr [STORY-006]"
OUT="$(run_diff breaking-change)"; RC=$?
POS=$RC
LOWER="$(printf '%s' "$OUT" | tr '[:upper:]' '[:lower:]')"
if [ "$RC" -eq 0 ] && [[ "$LOWER" == *warn* ]]; then ok "REQ-OPS-051 breaking change with label breaking-change and an ADR referencing the path exits 0 with a warning"
else bad "REQ-OPS-051 breaking change with label breaking-change and an ADR referencing the path exits 0 with a warning" "want rc=0 and a warning; got rc=$RC: $OUT"; fi

OUT="$(run_diff "")"; RC1=$?
OUT="$(run_diff "documentation")"; RC2=$?
g "$W" rm -q docs/adr/0099-remove-health-version.md; g "$W" commit -qm "drop adr [STORY-006]"
OUT="$(run_diff breaking-change)"; RC3=$?
mkdir -p "$W/docs/adr"
printf '# 0098. Unrelated\n\nSomething about /expenses only.\n' > "$W/docs/adr/0098-unrelated.md"
g "$W" add -A; g "$W" commit -qm "unrelated adr [STORY-006]"
OUT="$(run_diff breaking-change)"; RC4=$?
if [ "$POS" -eq 0 ] && [ "$RC1" -eq 1 ] && [ "$RC2" -eq 1 ] && [ "$RC3" -eq 1 ] && [ "$RC4" -eq 1 ]; then
  ok "REQ-OPS-051 the exception needs both the label and an ADR that references the changed path"
else bad "REQ-OPS-051 the exception needs both the label and an ADR that references the changed path" \
  "want rc=0 with label+ADR ($POS) and rc=1 for no label ($RC1), other label ($RC2), label without ADR ($RC3), label with unrelated ADR ($RC4)"; fi

echo "-- lint"
L="$tmp/lint"; mkdir -p "$L/scripts/make" "$L/contracts"
cp "$repo/scripts/make/contract.sh" "$repo/scripts/make/stubs.sh" "$L/scripts/make/"
cp "$repo/scripts/env.sh" "$L/scripts/"
cp "$repo/contracts/redocly.yaml" "$L/contracts/"
cp "$repo/contracts/openapi.yaml" "$L/contracts/openapi.yaml"
OUT="$(cd "$L" && bash scripts/make/contract.sh lint 2>&1)"; RC=$?
if [ "$RC" -eq 0 ]; then ok "REQ-OPS-051 contract lint passes with zero errors on contracts/openapi.yaml"
else bad "REQ-OPS-051 contract lint passes with zero errors on contracts/openapi.yaml" "rc=$RC: $(printf '%s' "$OUT" | tail -n 15)"; fi
grep -v '^      operationId: getHealth$' "$repo/contracts/openapi.yaml" > "$L/contracts/openapi.yaml"
OUT="$(cd "$L" && bash scripts/make/contract.sh lint 2>&1)"; RC=$?
if [ "$RC" -ne 0 ] && [[ "$OUT" == *operation-operationId* ]]; then ok "REQ-OPS-052 an operation without operationId fails contract lint (operation-operationId)"
else bad "REQ-OPS-052 an operation without operationId fails contract lint (operation-operationId)" "want rc!=0 naming operation-operationId; got rc=$RC: $(printf '%s' "$OUT" | tail -n 15)"; fi

echo "-- REQ-OPS-052"
D="$tmp/docs"; mkdir -p "$D/docs"
cp -r "$repo/scripts" "$repo/contracts" "$repo/Makefile" "$D/"
git -C "$D" init -q -b main
OUT="$(cd "$D" && make docs-api 2>&1)"; RC=$?
if [ "$RC" -eq 0 ] && [ -s "$D/docs/API.md" ]; then
  api="$D/docs/API.md"; missing=""
  while IFS= read -r t; do grep -qF "$t" "$api" || missing="$missing tag:$t"; done \
    < <(grep -E '^  - name: ' "$D/contracts/openapi.yaml" | sed 's/^  - name: //')
  for op in $(grep -oE '^      operationId: [A-Za-z0-9_]+' "$D/contracts/openapi.yaml" | awk '{print $2}'); do
    grep -qF "$op" "$api" || missing="$missing op:$op"
  done
  for req in $(grep -E '^      x-req: ' "$D/contracts/openapi.yaml" | grep -oE 'REQ-[A-Z]+-[0-9]{3}' | sort -u); do
    grep -qF "$req" "$api" || missing="$missing $req"
  done
  grep -qF '/health' "$api" || missing="$missing path:/health"
  grep -qF 'gitSha' "$api" || missing="$missing schema:Health.gitSha"
  if [ -z "$missing" ]; then ok "REQ-OPS-052 make docs-api writes docs/API.md with tags, operations, schemas and x-req ids"
  else bad "REQ-OPS-052 make docs-api writes docs/API.md with tags, operations, schemas and x-req ids" "docs/API.md lacks:$missing"; fi
  cp "$api" "$tmp/API.first"
  (cd "$D" && make docs-api >/dev/null 2>&1)
  if cmp -s "$tmp/API.first" "$api"; then ok "REQ-OPS-052 make docs-api is deterministic (a second run changes nothing)"
  else bad "REQ-OPS-052 make docs-api is deterministic (a second run changes nothing)" "docs/API.md differs between two runs"; fi
  g "$D" add -A; g "$D" commit -qm "api [STORY-006]"
  add_health_optional "$D/contracts/openapi.yaml"
  (cd "$D" && make docs-api >/dev/null 2>&1)
  if ! git -C "$D" diff --exit-code docs/API.md >/dev/null 2>&1; then ok "REQ-OPS-052 a contract change makes the committed docs/API.md stale (git diff --exit-code fails)"
  else bad "REQ-OPS-052 a contract change makes the committed docs/API.md stale (git diff --exit-code fails)" "docs/API.md did not change after a schema change"; fi
else
  for n in "make docs-api writes docs/API.md with tags, operations, schemas and x-req ids" \
           "make docs-api is deterministic (a second run changes nothing)" \
           "a contract change makes the committed docs/API.md stale (git diff --exit-code fails)"; do
    bad "REQ-OPS-052 $n" "make docs-api rc=$RC, docs/API.md missing: $(printf '%s' "$OUT" | tail -n 4)"
  done
fi

if grep -q 'docs-api' "$repo/scripts/make/check.sh" && grep -q 'git diff --exit-code docs/API.md' "$repo/scripts/make/check.sh"; then
  ok "REQ-OPS-052 make check regenerates docs/API.md and fails on git diff --exit-code docs/API.md"
else bad "REQ-OPS-052 make check regenerates docs/API.md and fails on git diff --exit-code docs/API.md" "scripts/make/check.sh has no docs-api step followed by git diff --exit-code docs/API.md"; fi
if [ -s "$repo/docs/API.md" ]; then ok "REQ-OPS-052 docs/API.md is committed"
else bad "REQ-OPS-052 docs/API.md is committed" "docs/API.md does not exist"; fi

printf '\n%s: %d passed, %d failed\n' "$suite" "$pass" "$fail"
[ "$fail" -eq 0 ]
