#!/usr/bin/env bash
# commit-msg-lint.sh <commit-msg-file>
#
# Enforces the Masroof commit convention (PROMPT.md S7/S12) on the subject line:
#
#   <type>(<scope>)?!?: <description> [<TAG>]
#
#   type   : feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert
#   scope  : optional, [A-Za-z0-9._/-] (story ids like STORY-002 are fine)
#   !      : optional breaking-change marker
#   TAG    : STORY-NNN (3+ digits), PHASE-N or SDLC, in square brackets, last on the line
#   length : whole subject line <= 72 characters
#   period : description must not end with "."
#
# Allowed as-is: merge commits ("Merge ..."), git-generated reverts ('Revert "..."'),
# and autosquash prefixes ("fixup! ", "squash! ", "amend! ").
#
# Exit codes: 0 ok, 1 message rejected, 2 usage error.
# Used by lefthook (commit-msg) and tested by scripts/quality/tests/test-commit-msg-lint.sh.
set -euo pipefail

TYPES='feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert'
TAG_RE='\[(STORY-[0-9]{3,}|PHASE-[0-9]+|SDLC)\]'
MAX_LEN=72

usage() {
  echo "usage: $(basename "$0") <commit-msg-file>" >&2
  exit 2
}

fail() {
  # $1 = reason; the rest are hints.
  printf 'commit-msg-lint: REJECTED - %s\n' "$1" >&2
  shift
  for hint in "$@"; do printf '  %s\n' "$hint" >&2; done
  cat >&2 <<EOF

  Expected: <type>(<scope>)?: <description> [STORY-NNN|PHASE-N|SDLC]
  Types:    ${TYPES//|/, }
  Examples: feat(sms): parse CIB debit templates [STORY-014]
            fix(dedup)!: tighten amount tolerance to 1 EGP [STORY-021]
            chore(sdlc): add nightly metrics workflow [SDLC]
            docs: phase 0 gate report [PHASE-0]
EOF
  exit 1
}

[ $# -eq 1 ] || usage
msg_file="$1"
[ -r "$msg_file" ] || { echo "commit-msg-lint: cannot read '$msg_file'" >&2; exit 2; }

# Subject = first line that is neither empty nor a git comment. Strip CR for Windows editors.
subject="$(grep -v '^#' "$msg_file" | sed -e 's/\r$//' -e '/^[[:space:]]*$/d' | head -n 1 || true)"

[ -n "$subject" ] || fail "empty commit message"

# Pass-through cases.
case "$subject" in
  "Merge "*) exit 0 ;;
  "Revert \""*) exit 0 ;;
  "fixup! "* | "squash! "* | "amend! "*) exit 0 ;;
esac

len=${#subject}
if [ "$len" -gt "$MAX_LEN" ]; then
  fail "subject line is ${len} characters; maximum is ${MAX_LEN}" "subject: ${subject}"
fi

header_re="^(${TYPES})(\([A-Za-z0-9][A-Za-z0-9._/-]*\))?(!)?: (.+)$"
uppercase_type_re='^[A-Z][a-z]+[(:!]'
any_type_re='^([a-z]+)(\([^)]*\))?!?: '
if ! [[ "$subject" =~ $header_re ]]; then
  if [[ "$subject" =~ $uppercase_type_re ]]; then
    fail "type must be lowercase" "subject: ${subject}"
  fi
  if [[ "$subject" =~ $any_type_re ]]; then
    fail "unknown type '${BASH_REMATCH[1]}'" "subject: ${subject}"
  fi
  fail "subject does not match '<type>(<scope>)?: <description>'" "subject: ${subject}"
fi
description="${BASH_REMATCH[4]}"

tag_end_re="${TAG_RE}\$"
other_tag_re='\[[A-Za-z]+-?[0-9]*\]$'
if ! [[ "$description" =~ $tag_end_re ]]; then
  if [[ "$description" =~ $other_tag_re ]]; then
    fail "tag '${BASH_REMATCH[0]}' is not one of [STORY-NNN] (3+ digits), [PHASE-N] or [SDLC]" "subject: ${subject}"
  fi
  fail "missing trailing tag [STORY-NNN], [PHASE-N] or [SDLC]" "subject: ${subject}"
fi

# Description without the tag, trimmed.
body="${description%\[*\]}"
body="${body%"${body##*[![:space:]]}"}"
[ -n "$body" ] || fail "description is empty (only a tag)" "subject: ${subject}"
case "$body" in
  *.) fail "description must not end with a period" "subject: ${subject}" ;;
esac

exit 0
