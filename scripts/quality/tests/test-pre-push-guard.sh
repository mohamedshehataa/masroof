#!/usr/bin/env bash
# REQ-OPS-011 local pre-push guard: tests for scripts/quality/pre-push-guard.sh in throwaway repositories.
# When QUALITY_TEST_RESULTS_TSV is set (by run.sh) each case is appended as suite<TAB>ok|fail<TAB>name<TAB>detail.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
guard="$here/../pre-push-guard.sh"
suite="test-pre-push-guard"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0; fail=0
record() { [ -n "${QUALITY_TEST_RESULTS_TSV:-}" ] && printf '%s\t%s\t%s\t%s\n' "$suite" "$1" "$2" "${3//$'\n'/ }" >> "$QUALITY_TEST_RESULTS_TSV"; return 0; }
ok()  { pass=$((pass + 1)); printf 'PASS  %s\n' "$1"; record ok "$1" ""; }
bad() { fail=$((fail + 1)); printf 'FAIL  %s\n      %s\n' "$1" "$2"; record fail "$1" "$2"; }
RC=0; OUT=""
# guard_in <stdin lines>  (run inside $W)
guard_in() { OUT="$(cd "$W" && printf '%s\n' "$1" | bash "$guard" origin "$R" 2>&1)"; RC=$?; }
expect() { # expect <rc> <substring> <name>
  if [ "$RC" -eq "$1" ] && [[ "$OUT" == *"$2"* ]]; then ok "$3"; else bad "$3" "wanted rc=$1 containing '$2'; got rc=$RC: $OUT"; fi
}
g() { git -C "$W" -c core.autocrlf=false -c user.name=t -c user.email=t@t -c commit.gpgsign=false "$@"; }
Z=0000000000000000000000000000000000000000

R="$tmp/remote.git"; W="$tmp/work"
git init -q --bare -b main "$R"
git init -q -b main "$W"
printf 'a\n' > "$W/a.txt"; g add -A; g commit -qm "a [STORY-001]"
printf 'b\n' > "$W/b.txt"; g add -A; g commit -qm "b [STORY-001]"
g remote add origin "$R"; g push -q origin main
OLD="$(g rev-parse HEAD~1)"; TIP="$(g rev-parse HEAD)"
printf 'c\n' > "$W/c.txt"; g add -A; g commit -qm "c [STORY-001]"; NEW="$(g rev-parse HEAD)"

echo "-- main"
guard_in "refs/heads/main $NEW refs/heads/main $TIP";             expect 0 "" "pre-push: a fast-forward of main is allowed"
guard_in "refs/heads/main $TIP refs/heads/main $Z";               expect 0 "" "pre-push: creating main on an empty remote is allowed"
guard_in "(delete) $Z refs/heads/main $TIP";                      expect 1 "refs/heads/main" "pre-push: deleting main is rejected and the ref is named"
guard_in "refs/heads/main $OLD refs/heads/main $TIP";             expect 1 "non-fast-forward" "pre-push: a non-fast-forward update of main is rejected"
guard_in "refs/heads/master $OLD refs/heads/master $TIP";         expect 1 "non-fast-forward" "pre-push: master is guarded too"
guard_in "refs/heads/main $NEW refs/heads/main 1234567890abcdef1234567890abcdef12345678"; expect 1 "git fetch" "pre-push: an unfetched remote sha fails closed and says git fetch"
echo "-- other refs"
guard_in "refs/heads/story/STORY-001-x $OLD refs/heads/story/STORY-001-x $TIP"; expect 0 "" "pre-push: a force push of a story branch is allowed"
guard_in "(delete) $Z refs/heads/story/STORY-001-x $TIP";         expect 0 "" "pre-push: deleting a story branch is allowed"
guard_in "";                                                      expect 0 "" "pre-push: no ref lines is allowed"

echo "-- end to end as the real git hook"
cp "$guard" "$tmp/guard.sh"
printf '#!/usr/bin/env bash\nexec bash "%s" "$@"\n' "$tmp/guard.sh" > "$W/.git/hooks/pre-push"; chmod +x "$W/.git/hooks/pre-push"
g reset -q --hard HEAD~2
OUT="$(g push -f origin main 2>&1)"; RC=$?
expect 1 "non-fast-forward" "pre-push: git reset --hard HEAD~1 + git push -f origin main is rejected by the hook"
[ "$(git -C "$R" rev-parse main)" = "$TIP" ] && ok "pre-push: the remote main did not move" || bad "pre-push: the remote main did not move" "remote main is $(git -C "$R" rev-parse main)"
OUT="$(g push origin :main 2>&1)"; RC=$?
expect 1 "deleting main" "pre-push: git push origin :main is rejected by the hook"

echo
echo "$suite: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
