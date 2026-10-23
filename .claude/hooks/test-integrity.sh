#!/usr/bin/env bash
# PostToolUse(Edit|Write|MultiEdit): when a test file is changed by anyone other than
# test-designer, detect removed assertions, added skips/disables or loosened tolerances.
# Non-blocking: feeds a warning back and logs it for the verifier. CI runs the full diff check.
HOOK_NAME=test-integrity
# shellcheck source=lib.sh
. "${BASH_SOURCE[0]%/*}/lib.sh"
read_input
agent="$(json_get '.agent_type')"
[ "$agent" = "test-designer" ] && exit 0
fp="$(json_get '.tool_input.file_path')"
[ -z "$fp" ] && exit 0
rel="$(rel_to_project "$fp")"
abs="$(to_posix_path "$fp")"
[ -f "$abs" ] || exit 0

# NTFS is case-insensitive: c:/.../expensetest.java is backend/.../ExpenseTest.java. Resolve the
# spelling git knows (tracked or untracked) so the HEAD comparison and the name patterns still work.
case "$rel" in
  /*|[A-Za-z]:*) : ;;   # outside the project
  *)
    if ! git -C "$PROJECT_DIR" cat-file -e "HEAD:$rel" 2>/dev/null; then
      real="$(git -C "$PROJECT_DIR" ls-files --cached --others --exclude-standard 2>/dev/null | grep -ixF -- "$rel")"
      if [ -n "$real" ] && ! printf '%s\n' "$real" | grep -qxF -- "$rel"; then rel="$(printf '%s\n' "$real" | head -1)"; fi
    fi ;;
esac

is_test=false
printf '%s' "$rel" | grep -Eq '(^|/)(test|tests|androidTest|e2e|__tests__|spec)/' && is_test=true
printf '%s' "$rel" | grep -Eq '\.(test|spec)\.(ts|tsx|js|jsx|mjs|kt|py)$' && is_test=true
printf '%s' "$rel" | grep -Eq '(Test|Tests|IT|Spec|Specs)\.(java|kt)$' && is_test=true
printf '%s' "$rel" | grep -Eq '(^|/)test_[^/]+\.py$' && is_test=true
[ "$is_test" = true ] || exit 0

# --- regexes: KEEP IN SYNC with scripts/quality/test-integrity-check.sh ---------------------
# The last alternatives count the bash hook/script suites: assert_* calls, `expect <rc>` and expect_*.
ASSERT_RE='\b(assert[A-Za-z_]*|expect|should[A-Z]?[A-Za-z]*|verify|check|require[A-Z]?[A-Za-z]*|assertThat|assertEquals|assertTrue|assertFalse|assertNull|assertNotNull|assertThrows|expectThat|toBe[A-Za-z]*|toEqual|toHave[A-Za-z]*|toMatch[A-Za-z]*|toThrow)\s*\(|\bassert_[a-z_]+\b|\bexpect [0-9]|\bexpect_(block|allow|rc)\b'
SKIP_RE='@Disabled|@Ignore|@Skip|\.skip\(|\bxit\(|\bxdescribe\(|\bxtest\(|test\.skip|it\.skip|describe\.skip|\.only\(|@Test\(\s*enabled\s*=\s*false|pytest\.mark\.skip|@unittest\.skip|\.todo\(|fixme\(|@Tag\("skip"\)'
TOL_RE='withinPercentage|within\(|offset\(|delta|tolerance|approximately|toBeCloseTo|isCloseTo|closeTo|assertAlmostEqual|Percentage\.withPercentage'
# ------------------------------------------------------------------------------------------

# count MATCHES (several assertions can share one line), never lines
count_matches() { grep -oE "$1" 2>/dev/null | wc -l | tr -d ' '; }
new_a="$(count_matches "$ASSERT_RE" < "$abs")"
new_s="$(count_matches "$SKIP_RE" < "$abs")"
new_t="$(count_matches "$TOL_RE" < "$abs")"

old=""; old_a=0; old_s=0; old_t=0
if old="$(git -C "$PROJECT_DIR" show "HEAD:$rel" 2>/dev/null)"; then
  old_a="$(printf '%s\n' "$old" | count_matches "$ASSERT_RE")"
  old_s="$(printf '%s\n' "$old" | count_matches "$SKIP_RE")"
  old_t="$(printf '%s\n' "$old" | count_matches "$TOL_RE")"
  existed=true
else
  existed=false
fi

problems=()
if [ "$existed" = true ] && (( new_a < old_a )); then problems+=("assertions decreased $old_a -> $new_a"); fi
if (( new_s > old_s )); then problems+=("skip/disable/only markers increased $old_s -> $new_s"); fi
if [ "$existed" = true ] && (( new_t > old_t )); then problems+=("tolerance/approximation usage increased $old_t -> $new_t (possible loosened test)"); fi

if (( ${#problems[@]} > 0 )); then
  line="$(date -u +%Y-%m-%dT%H:%M:%SZ) agent=${agent:-main} file=$rel ${problems[*]}"
  printf '%s\n' "$line" >> "$STATE_DIR/test-integrity.log" 2>/dev/null || true
  feedback "WARNING: TEST INTEGRITY WARNING for $rel: ${problems[*]}. Tests written by test-designer may not be weakened. If this change is legitimate, state the reason explicitly in your report under KNOWN_LIMITATIONS - the qa-verifier reads .claude/state/test-integrity.log and CI runs scripts/quality/test-integrity-check.sh."
fi
exit 0
