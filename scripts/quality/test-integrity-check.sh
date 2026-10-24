#!/usr/bin/env bash
# test-integrity-check.sh <base-ref> [--allow-deletions "<justification containing STORY-NNN>"]
#
# CI / verifier version of the per-edit hook .claude/hooks/test-integrity.sh: looks at the FULL
# diff between <base-ref> and the current working tree (committed + uncommitted + untracked)
# and fails when tests were weakened (PROMPT.md S2 stage 5, S9 "Test integrity").
#
# Test files (same patterns as the hook):
#   dirs test|tests|androidTest|e2e|__tests__|spec, *.test.* / *.spec.*, *Test.java|kt, *IT.java, test_*.py
# Violations (exit 1):
#   * net loss of assertions across all changed test files, unless the whole loss sits in
#     deleted files covered by --allow-deletions with a STORY id
#   * any new skip/disable/.only marker in a test file
#   * a deleted test file without --allow-deletions
# Tolerance/approximation increases are reported as WARN (the qa-verifier judges them).
#
# Prints a table and one final line: TEST_INTEGRITY: OK | TEST_INTEGRITY: VIOLATION
# Exit codes: 0 ok, 1 violation, 2 usage / git error.
# <base-ref> may be origin/main, main, a tag or a sha; the comparison point is merge-base(<base>, HEAD).
set -uo pipefail

usage() {
  echo "usage: $(basename "$0") <base-ref> [--allow-deletions \"<justification with STORY-NNN>\"]" >&2
  exit 2
}

[ $# -ge 1 ] || usage
BASE="$1"; shift
ALLOW_DELETIONS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --allow-deletions)
      [ $# -ge 2 ] || usage
      ALLOW_DELETIONS="$2"; shift 2 ;;
    --allow-deletions=*)
      ALLOW_DELETIONS="${1#--allow-deletions=}"; shift ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done
if [ -n "$ALLOW_DELETIONS" ] && ! printf '%s' "$ALLOW_DELETIONS" | grep -Eq 'STORY-[0-9]{3,}'; then
  echo "--allow-deletions justification must reference a STORY-NNN id" >&2
  exit 2
fi

# --- regexes: KEEP IN SYNC with .claude/hooks/test-integrity.sh ---------------------------
# The last alternatives count the bash hook/script suites: assert_* calls, `expect <rc>` and expect_*.
ASSERT_RE='\b(assert[A-Za-z_]*|expect|should[A-Z]?[A-Za-z]*|verify|check|require[A-Z]?[A-Za-z]*|assertThat|assertEquals|assertTrue|assertFalse|assertNull|assertNotNull|assertThrows|expectThat|toBe[A-Za-z]*|toEqual|toHave[A-Za-z]*|toMatch[A-Za-z]*|toThrow)\s*\(|\bassert_rc [0-9]+|\bassert_[a-z_]+\b|\bexpect [0-9]+|\bexpect_(block|allow|rc)\b'
# whole-line comments do not count (a check left only in a comment is a removed check)
COMMENT_RE='^[[:space:]]*(#|//|/\*|\*)'
SKIP_RE='@Disabled|@Ignore|@Skip|\.skip\(|\bxit\(|\bxdescribe\(|\bxtest\(|test\.skip|it\.skip|describe\.skip|\.only\(|@Test\(\s*enabled\s*=\s*false|pytest\.mark\.skip|@unittest\.skip|\.todo\(|fixme\(|@Tag\("skip"\)'
TOL_RE='withinPercentage|within\(|offset\(|delta|tolerance|approximately|toBeCloseTo|isCloseTo|closeTo|assertAlmostEqual|Percentage\.withPercentage'
# ------------------------------------------------------------------------------------------

is_test_file() {
  printf '%s' "$1" | grep -Eq '(^|/)(test|tests|androidTest|e2e|__tests__|spec)/' && return 0
  printf '%s' "$1" | grep -Eq '\.(test|spec)\.(ts|tsx|js|jsx|mjs|kt|py)$' && return 0
  printf '%s' "$1" | grep -Eq '(Test|Tests|IT|Spec|Specs)\.(java|kt)$' && return 0
  printf '%s' "$1" | grep -Eq '(^|/)test_[^/]+\.py$' && return 0
  return 1
}

# count_re <regex>  (reads stdin; counts MATCHES, not lines, so several assertions on one line all count)
count_re() { local n; n="$(grep -oE "$1" 2>/dev/null | wc -l | tr -d '[:space:]')"; printf '%s' "${n:-0}"; }
# assertion identifiers with counts ("3 assert_blocked", "1 expect 0"), comments excluded: a flip such as
# assert_blocked -> assert_allowed keeps the total but changes this multiset
assert_ids() { grep -vE "$COMMENT_RE" 2>/dev/null | grep -oE "$ASSERT_RE" 2>/dev/null | sed -E 's/[[:space:]]*\($//' | sort | uniq -c; }

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "not inside a git repository" >&2; exit 2; }
cd "$ROOT" || exit 2
if ! git rev-parse --verify --quiet "${BASE}^{commit}" >/dev/null; then
  echo "cannot resolve base ref '$BASE'" >&2
  exit 2
fi
if git rev-parse --verify --quiet HEAD >/dev/null; then
  MB="$(git merge-base "$BASE" HEAD 2>/dev/null || git rev-parse "${BASE}^{commit}")"
else
  MB="$(git rev-parse "${BASE}^{commit}")"
fi

# Changed paths: merge-base -> working tree (includes committed and uncommitted changes), renames detected,
# plus untracked files as additions.
changes="$(git diff --name-status -M --no-color "$MB" -- 2>/dev/null)"
untracked="$(git ls-files --others --exclude-standard 2>/dev/null)"

printf '%-8s %-60s %-12s %-10s %-10s %s\n' "status" "file" "asserts" "skips" "tol" "note"
printf '%-8s %-60s %-12s %-10s %-10s %s\n' "------" "----" "-------" "-----" "---" "----"

total_old=0; total_new=0; deleted_loss=0
violations=(); warnings=(); rows=0

analyze() { # analyze <status> <old-path|-> <new-path|->
  local st="$1" oldp="$2" newp="$3" old_a=0 old_s=0 old_t=0 new_a=0 new_s=0 new_t=0 note="" oldc=""
  if [ "$oldp" != "-" ] && oldc="$(git show "$MB:$oldp" 2>/dev/null)"; then
    old_a="$(printf '%s\n' "$oldc" | grep -vE "$COMMENT_RE" | count_re "$ASSERT_RE")"
    old_s="$(printf '%s\n' "$oldc" | count_re "$SKIP_RE")"
    old_t="$(printf '%s\n' "$oldc" | count_re "$TOL_RE")"
  fi
  if [ "$newp" != "-" ] && [ -f "$newp" ]; then
    new_a="$(grep -vE "$COMMENT_RE" "$newp" | count_re "$ASSERT_RE")"
    new_s="$(count_re "$SKIP_RE" < "$newp")"
    new_t="$(count_re "$TOL_RE" < "$newp")"
  fi
  total_old=$((total_old + old_a)); total_new=$((total_new + new_a))
  case "$st" in
    D)
      deleted_loss=$((deleted_loss + old_a))
      if [ -n "$ALLOW_DELETIONS" ]; then note="deleted (allowed: $ALLOW_DELETIONS)"
      else note="DELETED without --allow-deletions"; violations+=("$oldp: test file deleted without --allow-deletions"); fi ;;
    R*) note="renamed from $oldp" ;;
    A) note="added" ;;
  esac
  if (( new_s > old_s )); then
    note="${note:+$note; }NEW SKIP/DISABLE/ONLY MARKER"
    violations+=("${newp}: skip/disable/only markers increased $old_s -> $new_s")
  fi
  if (( new_a < old_a )) && [ "$st" != "D" ]; then note="${note:+$note; }assertions decreased"; fi
  if (( new_a >= old_a )) && [ "$oldp" != "-" ] && [ "$newp" != "-" ] && [ -f "$newp" ]; then
    local -A oc=() nc=()
    local c id o n flips=""
    while read -r c id; do [ -n "$id" ] && oc["$id"]="$c"; done < <(printf '%s\n' "$oldc" | assert_ids)
    while read -r c id; do [ -n "$id" ] && nc["$id"]="$c"; done < <(assert_ids < "$newp")
    for id in "${!oc[@]}"; do o="${oc[$id]}"; n="${nc[$id]:-0}"; (( n < o )) && flips+="${flips:+, }$id $o -> $n"; done
    if [ -n "$flips" ]; then
      note="${note:+$note; }assertion kinds changed (WARN)"
      warnings+=("${newp}: assertion kinds changed while the total did not drop ($flips) - e.g. assert_blocked flipped to assert_allowed; the verifier judges it")
    fi
  fi
  if (( new_t > old_t )); then
    note="${note:+$note; }tolerance usage increased (WARN)"
    warnings+=("${newp}: tolerance/approximation usage increased $old_t -> $new_t (possible loosened test)")
  fi
  local shown="$newp"; [ "$shown" = "-" ] && shown="$oldp"
  printf '%-8s %-60s %-12s %-10s %-10s %s\n' "$st" "$shown" "$old_a -> $new_a" "$old_s -> $new_s" "$old_t -> $new_t" "$note"
  rows=$((rows + 1))
}

while IFS=$'\t' read -r st p1 p2; do
  [ -n "${st:-}" ] || continue
  case "$st" in
    R*|C*) oldp="$p1"; newp="$p2" ;;
    D)     oldp="$p1"; newp="-" ;;
    A)     oldp="-";   newp="$p1" ;;
    *)     oldp="$p1"; newp="$p1" ;;
  esac
  if is_test_file "$oldp" || is_test_file "$newp"; then analyze "$st" "$oldp" "$newp"; fi
done <<< "$changes"

while IFS= read -r p; do
  [ -n "${p:-}" ] || continue
  if is_test_file "$p"; then analyze "A" "-" "$p"; fi
done <<< "$untracked"

[ "$rows" -eq 0 ] && echo "(no test files changed between $BASE and the working tree)"

net=$((total_old - total_new))
echo
echo "assertions: $total_old (base) -> $total_new (now); net change $((total_new - total_old)); in deleted files: $deleted_loss"
if (( net > 0 )); then
  if [ -n "$ALLOW_DELETIONS" ] && (( net - deleted_loss <= 0 )); then
    echo "net assertion loss $net is entirely in deleted files covered by --allow-deletions"
  else
    violations+=("net assertion loss of $net across changed test files")
  fi
fi

for w in "${warnings[@]+"${warnings[@]}"}"; do echo "WARN: $w"; done
for v in "${violations[@]+"${violations[@]}"}"; do echo "VIOLATION: $v"; done

if [ "${#violations[@]}" -gt 0 ]; then
  echo "TEST_INTEGRITY: VIOLATION"
  exit 1
fi
echo "TEST_INTEGRITY: OK"
exit 0
