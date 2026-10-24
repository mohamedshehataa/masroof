#!/usr/bin/env bash
# pre-push-guard.sh <remote-name> <remote-url>   (lefthook pre-push, use_stdin; REQ-OPS-011)
# Local guard for main/master: reads git's pre-push lines "<local ref> <local sha> <remote ref> <remote sha>"
# and rejects, for refs/heads/main or refs/heads/master:
#   - a deletion (local sha all zeros: `git push origin :main`, `git push --delete origin main`);
#   - a non-fast-forward update (the remote sha is not an ancestor of the local sha, e.g. after
#     `git reset --hard HEAD~1` then `git push -f origin main`);
#   - an update whose remote sha is not present locally: ancestry cannot be checked, so it fails closed
#     ("run git fetch" first).
# A fast-forward, the creation of main on an empty remote, and any other ref (story/* force pushes) pass.
# Exit 0 = allowed, 1 = rejected (message on stderr). The remote guard is GitHub branch protection.
set -euo pipefail
remote="${1:-origin}"
zero_re='^0+$'
rc=0
reject() { printf 'pre-push-guard: REJECTED %s -> %s: %s\n' "$1" "$remote" "$2" >&2; rc=1; }
while read -r local_ref local_sha remote_ref remote_sha; do
  [ -n "${remote_ref:-}" ] || continue
  case "$remote_ref" in
    refs/heads/main|refs/heads/master) ;;
    *) continue ;;
  esac
  if [[ "$local_sha" =~ $zero_re ]]; then
    reject "$remote_ref" "deleting ${remote_ref#refs/heads/} on the remote is forbidden"
    continue
  fi
  [[ "$remote_sha" =~ $zero_re ]] && continue   # creating main on an empty remote
  if ! git cat-file -e "${remote_sha}^{commit}" 2>/dev/null; then
    reject "$remote_ref" "the remote ${remote_ref#refs/heads/} is at ${remote_sha:0:12}, which is not in this clone; run 'git fetch $remote' first (cannot prove the push is a fast-forward)"
    continue
  fi
  if ! git merge-base --is-ancestor "$remote_sha" "$local_sha" 2>/dev/null; then
    reject "$remote_ref" "non-fast-forward update of ${remote_ref#refs/heads/} (${remote_sha:0:12} is not an ancestor of ${local_sha:0:12} from $local_ref); main has linear, protected history - rebase your story branch instead"
  fi
done
exit "$rc"
