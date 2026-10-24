#!/usr/bin/env bash
# REQ-OPS-151: protect main. Re-runnable; prints the applied protection.
# Usage: bash scripts/sdlc/apply-branch-protection.sh [owner/repo] [branch]
set -euo pipefail
REPO="${1:-mohamedshehataa/masroof}"
BRANCH="${2:-main}"
# "PR gate" is the check-run name of pr.yml's aggregate job `gate`.
CHECK="${MASROOF_REQUIRED_CHECK:-PR gate}"

body=$(cat <<JSON
{
  "required_status_checks": {"strict": true, "checks": [{"context": "$CHECK"}]},
  "enforce_admins": true,
  "required_pull_request_reviews": {"required_approving_review_count": 0, "dismiss_stale_reviews": false, "require_code_owner_reviews": false},
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": false
}
JSON
)

if ! out=$(printf '%s' "$body" | gh api -X PUT "repos/$REPO/branches/$BRANCH/protection" \
    -H "Accept: application/vnd.github+json" --input - 2>&1); then
  echo "apply-branch-protection: GitHub refused: $out" >&2
  exit 1
fi
gh api "repos/$REPO/branches/$BRANCH/protection" --jq '{
  checks: [.required_status_checks.checks[].context], strict: .required_status_checks.strict,
  enforce_admins: .enforce_admins.enabled, approvals: .required_pull_request_reviews.required_approving_review_count,
  linear: .required_linear_history.enabled, force_pushes: .allow_force_pushes.enabled, deletions: .allow_deletions.enabled}'
