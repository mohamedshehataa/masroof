#!/usr/bin/env bash
# REQ-OPS-005: check-output-contract.sh blocks roster subagents whose final message lacks the contract.
# shellcheck source=lib-test.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib-test.sh"
H=check-output-contract.sh

GOOD_VERIFY='Verification done.
STORY: STORY-007
CRITERIA:
- REQ-EXP-001: PASS - evidence: `make check-fast MODULE=backend` -> 42 tests, 0 failures
FINDINGS:
- [MINOR] backend/src/x.java:12 - message
EXPLORATORY: 12 cases tried, 1 problem
TEST_INTEGRITY: OK
STATUS: PASS'

echo "-- roster agents (REQ-OPS-005)"
run_hook $H "$(json_subagent_stop qa-verifier "$GOOD_VERIFY")";                   assert_allowed "REQ-OPS-005 full qa-verifier contract passes"
run_hook $H "$(json_subagent_stop qa-verifier "I checked everything and it looks fine.")"; assert_blocked "REQ-OPS-005 blocks qa-verifier without STATUS"
assert_contains "$ERR" "PASS|FAIL|BLOCKED" "REQ-OPS-005 block lists allowed statuses"
assert_contains "$ERR" "CRITERIA:" "REQ-OPS-005 block lists required sections"
run_hook $H "$(json_subagent_stop qa-verifier "${GOOD_VERIFY/STATUS: PASS/STATUS: DONE}")"; assert_blocked "REQ-OPS-005 blocks invalid STATUS value"
run_hook $H "$(json_subagent_stop qa-verifier "$(printf '%s' "$GOOD_VERIFY" | grep -v '^CRITERIA:')")"; assert_blocked "REQ-OPS-005 blocks missing CRITERIA section"
assert_contains "$ERR" "CRITERIA" "REQ-OPS-005 names the missing section"
run_hook $H "$(json_subagent_stop qa-verifier "$GOOD_VERIFY
Let me know if you need anything else!")";                                        assert_blocked "REQ-OPS-005 blocks when STATUS is not the last line"
run_hook $H "$(json_subagent_stop qa-verifier "")";                               assert_blocked "REQ-OPS-005 blocks empty final message"

run_hook $H "$(json_subagent_stop code-reviewer "## Review
**FINDINGS:**
- [MAJOR] x.java:3 - unbounded query
**STATUS:** CHANGES_REQUESTED")";                                                  assert_allowed "REQ-OPS-005 accepts markdown-bold headings"
run_hook $H "$(json_subagent_stop code-reviewer "FINDINGS:
- none
STATUS: APPROVE")";                                                                assert_allowed "REQ-OPS-005 code-reviewer APPROVE passes"
run_hook $H "$(json_subagent_stop code-reviewer "FINDINGS:
- none
STATUS: PASS")";                                                                   assert_blocked "REQ-OPS-005 code-reviewer may not use PASS"

run_hook $H "$(json_subagent_stop backend-engineer "FILES_CHANGED:
- a.java
COMMANDS_RUN:
- make check-fast MODULE=backend -> green
KNOWN_LIMITATIONS:
- none
STATUS: READY_FOR_VERIFY")";                                                       assert_allowed "REQ-OPS-005 implementer contract passes"
run_hook $H "$(json_subagent_stop backend-engineer "FILES_CHANGED:
- a.java
STATUS: READY_FOR_VERIFY")";                                                       assert_blocked "REQ-OPS-005 implementer missing COMMANDS_RUN is blocked"
run_hook $H "$(json_subagent_stop test-designer "TESTS:
- ATest
COVERAGE_OF_CRITERIA:
- REQ-X-001 -> ATest
COMMANDS_RUN:
- ./gradlew test -> 3 failing (assertion errors)
STATUS: TESTS_FAILING_AS_EXPECTED")";                                              assert_allowed "REQ-OPS-005 test-designer contract passes"
run_hook $H "$(json_subagent_stop product-analyst "STORIES:
- STORY-001
REQS:
- REQ-EXP-001
STATUS: SPEC_READY")";                                                             assert_allowed "REQ-OPS-005 product-analyst contract passes"
run_hook $H "$(json_subagent_stop retro-facilitator "KEEP:
- x
CHANGE:
- y
TRY:
- z
APPLIED_CHANGES:
- none
STATUS: RETRO_DONE")";                                                             assert_allowed "REQ-OPS-005 retro-facilitator contract passes"

echo "-- non-roster agents pass through"
run_hook $H "$(json_subagent_stop general-purpose "Here is what I found: nothing.")"; assert_allowed "REQ-OPS-005 general-purpose passes without contract"
run_hook $H "$(json_subagent_stop Explore "found 3 files")";                         assert_allowed "REQ-OPS-005 Explore passes"
run_hook $H '{"hook_event_name":"SubagentStop","stop_hook_active":false}';           assert_allowed "REQ-OPS-005 missing agent_type passes"
finish
