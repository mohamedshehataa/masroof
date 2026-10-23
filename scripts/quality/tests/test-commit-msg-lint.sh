#!/usr/bin/env bash
# Tests for scripts/quality/commit-msg-lint.sh. Run: bash scripts/quality/tests/test-commit-msg-lint.sh
# Exit 0 when every case behaves as expected, 1 otherwise. Part of `make check-fast MODULE=sdlc`.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
lint="${here}/../commit-msg-lint.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

pass=0
fail=0

# expect <0|1> <name> <message...>  (message may contain \n)
expect() {
  local want="$1" name="$2" msg="$3" file got
  file="${tmp}/${name// /_}.txt"
  printf '%b' "$msg" > "$file"
  if bash "$lint" "$file" >/dev/null 2>"${file}.err"; then got=0; else got=$?; fi
  if [ "$got" -eq "$want" ]; then
    pass=$((pass + 1))
    printf 'PASS  %-45s (exit %s)\n' "$name" "$got"
  else
    fail=$((fail + 1))
    printf 'FAIL  %-45s wanted %s got %s\n' "$name" "$want" "$got"
    sed 's/^/      /' "${file}.err"
  fi
}

# ---- good --------------------------------------------------------------------
expect 0 "feat with scope and story tag"        'feat(sms): parse CIB debit templates [STORY-014]\n'
expect 0 "chore without scope, phase tag"       'chore: bootstrap SDLC skeleton [PHASE-0]\n'
expect 0 "ci with sdlc tag"                     'ci(sdlc): add nightly workflow [SDLC]\n'
expect 0 "breaking marker"                      'fix(api)!: drop legacy amount field [STORY-020]\n'
expect 0 "scope with slash and dot"             'refactor(backend/money.v2): extract rounding [STORY-007]\n'
expect 0 "body and comment lines ignored"       '# comment from template\n\nfeat(web): RTL layout for review inbox [STORY-031]\n\nLonger body here.\n# Please enter the commit message\n'
expect 0 "crlf line endings"                    'docs(adr): record money representation [STORY-002]\r\n'
expect 0 "merge commit"                         'Merge branch '"'"'story/STORY-014-cib'"'"' into main\n'
expect 0 "git revert commit"                    'Revert "feat(sms): parse CIB debit templates [STORY-014]"\n\nThis reverts commit abc123.\n'
expect 0 "fixup prefix"                         'fixup! feat(sms): parse CIB debit templates\n'
expect 0 "squash prefix"                        'squash! anything goes here\n'
expect 0 "exactly 72 chars"                     'feat(sms): parse CIB debit templates and InstaPay transfers [STORY-014]\n'

# ---- bad ---------------------------------------------------------------------
expect 1 "no type"                              'Add parser [STORY-014]\n'
expect 1 "missing tag"                          'feat(sms): parse CIB debit templates\n'
expect 1 "trailing period"                      'feat(sms): parse CIB debit templates. [STORY-014]\n'
expect 1 "too long (73 chars)"                  'feat(sms): parse CIB debit templates and InstaPay transfers. [STORY-014]\n'
expect 1 "unknown type"                         'feature(sms): parse CIB templates [STORY-014]\n'
expect 1 "uppercase type"                       'Feat(sms): parse CIB templates [STORY-014]\n'
expect 1 "story tag with 2 digits"              'feat(sms): parse CIB templates [STORY-14]\n'
expect 1 "wrong tag family"                     'feat(sms): parse CIB templates [TICKET-123]\n'
expect 1 "tag not last"                         'feat(sms): [STORY-014] parse CIB templates\n'
expect 1 "only a tag"                           'chore: [SDLC]\n'
expect 1 "uppercase scope"                      'feat(SMS): parse CIB templates [STORY-014]\n'
expect 1 "missing space after colon"            'feat(sms):parse CIB templates [STORY-014]\n'
expect 1 "empty message"                        '\n\n# only comments\n'

# ---- usage -------------------------------------------------------------------
if bash "$lint" >/dev/null 2>&1; then
  fail=$((fail + 1)); echo "FAIL  no argument should exit 2"
else
  rc=$?
  if [ "$rc" -eq 2 ]; then pass=$((pass + 1)); echo "PASS  no argument exits 2"
  else fail=$((fail + 1)); echo "FAIL  no argument exited $rc, wanted 2"; fi
fi

echo
echo "commit-msg-lint tests: ${pass} passed, ${fail} failed"
[ "$fail" -eq 0 ]
