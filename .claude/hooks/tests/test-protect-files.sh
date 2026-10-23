#!/usr/bin/env bash
# REQ-OPS-002: protect-files.sh blocks edits to env files, key material, applied migrations,
# generated code, the eval baseline, the hooks themselves, and enforces role write-scopes.
# shellcheck source=lib-test.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib-test.sh"
H=protect-files.sh

P="$(make_temp_repo)"
mkdir -p "$P/backend/src/main/resources/db/migration" "$P/.claude/hooks"
echo "create table household(id uuid);" > "$P/backend/src/main/resources/db/migration/V1__init.sql"
(cd "$P" && git add . && git commit -q -m "feat(db): init [PHASE-0]")
echo "alter table household add col text;" > "$P/backend/src/main/resources/db/migration/V2__add_col.sql"   # untracked = new
export CLAUDE_PROJECT_DIR="$P"
C="x"

echo "-- env files"
run_hook $H "$(json_write "$P/.env" "$C")";                       assert_blocked "REQ-OPS-002 blocks .env"
run_hook $H "$(json_write "$P/.env.local" "$C")";                 assert_blocked "REQ-OPS-002 blocks .env.local"
run_hook $H "$(json_write "$P/infra/.env.prod" "$C")";            assert_blocked "REQ-OPS-002 blocks nested .env.prod"
run_hook $H "$(json_write 'C:\other\place\.env' "$C")";           assert_blocked "REQ-OPS-002 blocks .env outside the repo (backslash path)"
run_hook $H "$(json_write "$P/.env.example" "$C")";               assert_allowed "REQ-OPS-002 allows .env.example"
run_hook $H "$(json_write "$P/infra/prod.env.example" "$C")";     assert_allowed "REQ-OPS-002 allows *.env.example"
run_hook $H "$(json_edit "$P/.env" "$C")";                        assert_blocked "REQ-OPS-002 blocks Edit of .env too"

echo "-- key material"
run_hook $H "$(json_write "$P/android/release.jks" "$C")";        assert_blocked "REQ-OPS-002 blocks .jks"
run_hook $H "$(json_write "$P/infra/secrets/token.txt" "$C")";    assert_blocked "REQ-OPS-002 blocks secrets/ dir"
run_hook $H "$(json_write "$P/infra/ca.p12" "$C")";               assert_blocked "REQ-OPS-002 blocks .p12"

echo "-- flyway migrations"
run_hook $H "$(json_edit "$P/backend/src/main/resources/db/migration/V1__init.sql" "$C")"; assert_blocked "REQ-OPS-002 blocks editing an applied migration (on main)"
assert_contains "$ERR" "immutable" "REQ-OPS-002 migration block explains immutability"
run_hook $H "$(json_write "$P/backend/src/main/resources/db/migration/V2__add_col.sql" "$C")"; assert_allowed "REQ-OPS-002 allows a new (not yet on main) migration"
run_hook $H "$(json_write "$P/backend/src/main/resources/db/migration/V3__next.sql" "$C")"; assert_allowed "REQ-OPS-002 allows creating the next migration"

echo "-- generated code"
run_hook $H "$(json_write "$P/web/src/api/generated/client.ts" "$C")"; assert_blocked "REQ-OPS-002 blocks web generated client"
run_hook $H "$(json_write "$P/backend/build/generated/openapi/Api.java" "$C")"; assert_blocked "REQ-OPS-002 blocks backend generated code"
run_hook $H "$(json_write "$P/android/app/build/x.kt" "$C")";    assert_blocked "REQ-OPS-002 blocks build output"
run_hook $H "$(json_write "$P/web/src/api/client.ts" "$C")";     assert_allowed "REQ-OPS-002 allows hand-written api client"

echo "-- eval baseline"
run_hook $H "$(json_write "$P/eval/baseline.json" "$C")";                    assert_blocked "REQ-OPS-002 blocks baseline.json by default"
run_hook $H "$(json_write "$P/eval/baseline.json" "$C" ai-eval-engineer)";   assert_allowed "REQ-OPS-002 allows baseline.json for ai-eval-engineer"
MASROOF_REBASELINE=1 run_hook $H "$(json_write "$P/eval/baseline.json" "$C")"; assert_allowed "REQ-OPS-002 allows baseline.json with MASROOF_REBASELINE=1"
jq '.rebaseline_allowed=true' "$MASROOF_STATE_FILE" > "$MASROOF_STATE_FILE.tmp" && mv "$MASROOF_STATE_FILE.tmp" "$MASROOF_STATE_FILE"
run_hook $H "$(json_write "$P/eval/baseline.json" "$C")";                    assert_allowed "REQ-OPS-002 allows baseline.json when state.rebaseline_allowed"
jq '.rebaseline_allowed=false' "$MASROOF_STATE_FILE" > "$MASROOF_STATE_FILE.tmp" && mv "$MASROOF_STATE_FILE.tmp" "$MASROOF_STATE_FILE"
run_hook $H "$(json_write "$P/eval/datasets/sms/cases.jsonl" "$C")";         assert_allowed "REQ-OPS-002 allows dataset edits"

echo "-- hooks, settings and the spec are locked by default"
run_hook $H "$(json_edit "$P/.claude/hooks/guard-bash.sh" "$C")";   assert_blocked "REQ-OPS-002 blocks editing a hook while locked"
run_hook $H "$(json_write "$P/.claude/hooks/tests/test-x.sh" "$C")"; assert_blocked "REQ-OPS-002 blocks adding hook tests while locked"
run_hook $H "$(json_edit "$P/.claude/settings.json" "$C")";         assert_blocked "REQ-OPS-002 blocks editing settings.json while locked"
run_hook $H "$(json_edit "$P/docs/PROMPT.md" "$C")";                assert_blocked "REQ-OPS-002 blocks editing docs/PROMPT.md while locked"
assert_contains "$ERR" "hooks-unlock" "REQ-OPS-002 block message explains how to unlock"
jq '.hooks_unlocked=true' "$MASROOF_STATE_FILE" > "$MASROOF_STATE_FILE.tmp" && mv "$MASROOF_STATE_FILE.tmp" "$MASROOF_STATE_FILE"
run_hook $H "$(json_edit "$P/.claude/hooks/guard-bash.sh" "$C")";   assert_allowed "REQ-OPS-002 allows hook edits when state.hooks_unlocked"
jq '.hooks_unlocked=false' "$MASROOF_STATE_FILE" > "$MASROOF_STATE_FILE.tmp" && mv "$MASROOF_STATE_FILE.tmp" "$MASROOF_STATE_FILE"
MASROOF_HOOKS_UNLOCK=1 run_hook $H "$(json_edit "$P/.claude/hooks/guard-bash.sh" "$C")"; assert_allowed "REQ-OPS-002 allows hook edits with MASROOF_HOOKS_UNLOCK=1"
run_hook $H "$(json_write "$P/.claude/agents/new-agent.md" "$C")";  assert_allowed "REQ-OPS-002 allows editing agents (not hooks)"
run_hook $H "$(json_write "$P/.claude/skills/x/SKILL.md" "$C")";    assert_allowed "REQ-OPS-002 allows editing skills"

echo "-- role write scopes (from output-contracts.json)"
run_hook $H "$(json_write "$P/backend/src/main/java/X.java" "$C" product-analyst)";   assert_blocked "REQ-OPS-002 product-analyst cannot write source"
run_hook $H "$(json_write "$P/docs/backlog/EPIC-01/STORY-001.md" "$C" product-analyst)"; assert_allowed "REQ-OPS-002 product-analyst can write docs"
run_hook $H "$(json_write "$P/web/src/App.tsx" "$C" docs-writer)";                   assert_blocked "REQ-OPS-002 docs-writer cannot write web source"
run_hook $H "$(json_write "$P/README.md" "$C" docs-writer)";                         assert_allowed "REQ-OPS-002 docs-writer can write README"
run_hook $H "$(json_write "$P/backend/src/main/java/Svc.java" "$C" test-designer)";  assert_blocked "REQ-OPS-002 test-designer cannot write production code"
run_hook $H "$(json_write "$P/backend/src/test/java/SvcTest.java" "$C" test-designer)"; assert_allowed "REQ-OPS-002 test-designer can write tests"
run_hook $H "$(json_write "$P/web/src/money.test.ts" "$C" test-designer)";           assert_allowed "REQ-OPS-002 test-designer can write vitest files"
run_hook $H "$(json_write "$P/backend/src/main/java/Svc.java" "$C" backend-engineer)"; assert_allowed "REQ-OPS-002 backend-engineer is unrestricted"
run_hook $H "$(json_write "$P/backend/src/main/java/Svc.java" "$C" general-purpose)"; assert_allowed "REQ-OPS-002 non-roster agents are unrestricted"
run_hook $H "$(json_write "$P/.claude/agents/code-reviewer.md" "$C" retro-facilitator)"; assert_allowed "REQ-OPS-002 retro-facilitator can edit agent prompts"
run_hook $H "$(json_write "$P/backend/src/main/java/Svc.java" "$C" retro-facilitator)"; assert_blocked "REQ-OPS-002 retro-facilitator cannot write source"

run_hook $H '{"hook_event_name":"PreToolUse","tool_name":"Write","tool_input":{}}'; assert_allowed "REQ-OPS-002 missing file_path passes"

echo "-- key material from the AC (STORY-001 verify F-07)"
run_hook $H "$(json_write "$P/certs/server.pem" "$C")";           assert_blocked "REQ-OPS-002 blocks *.pem"
run_hook $H "$(json_write "$P/infra/tls/private.PEM" "$C")";      assert_blocked "REQ-OPS-002 blocks *.PEM (any case)"
run_hook $H "$(json_write "$P/keys/deploy.ppk" "$C")";            assert_blocked "REQ-OPS-002 blocks *.ppk"
run_hook $H "$(json_write "$P/keys/ca.der" "$C")";                assert_blocked "REQ-OPS-002 blocks *.der"
run_hook $H "$(json_write "$P/android/release.keystore" "$C")";   assert_blocked "REQ-OPS-002 blocks *.keystore"

echo "-- settings.local.json is a guardrail file (F-10)"
run_hook $H "$(json_write "$P/.claude/settings.local.json" '{"disableAllHooks":true}')"; assert_blocked "REQ-OPS-002 blocks settings.local.json while locked"
MASROOF_HOOKS_UNLOCK=1 run_hook $H "$(json_write "$P/.claude/settings.local.json" '{}')"; assert_allowed "REQ-OPS-002 allows settings.local.json when unlocked"

echo "-- case-variant paths (F-08)"
# the Windows form (C:/...) of the temp repo when cygpath exists, else the POSIX path
PW="$(cygpath -m "$P" 2>/dev/null || printf '%s' "$P")"
PL="${PW,,}"; PU="${PW^^}"
run_hook $H "$(json_edit "$PL/.claude/hooks/guard-bash.sh" "$C")";   assert_blocked "REQ-OPS-002 blocks a lower-cased hook path"
run_hook $H "$(json_edit "${PL//\//\\}\\.claude\\hooks\\guard-bash.sh" "$C")"; assert_blocked "REQ-OPS-002 blocks a lower-cased backslash hook path"
run_hook $H "$(json_edit "$PU/.claude/settings.json" "$C")";         assert_blocked "REQ-OPS-002 blocks an upper-cased settings.json path"
run_hook $H "$(json_edit "$PW/.claude/HOOKS/guard-bash.sh" "$C")";   assert_blocked "REQ-OPS-002 blocks .claude/HOOKS"
run_hook $H "$(json_edit "$PL/docs/prompt.md" "$C")";                assert_blocked "REQ-OPS-002 blocks a lower-cased docs/PROMPT.md"
run_hook $H "$(json_write "$PW/.ENV" "$C")";                         assert_blocked "REQ-OPS-002 blocks .ENV"
run_hook $H "$(json_write "$PL/eval/baseline.json" "$C")";           assert_blocked "REQ-OPS-002 blocks a lower-cased eval/baseline.json"
run_hook $H "$(json_edit "$PL/backend/src/main/resources/db/migration/v1__init.sql" "$C")"; assert_blocked "REQ-OPS-002 blocks a case-variant applied migration"
run_hook $H "$(json_write "$PU/BACKEND/SRC/MAIN/JAVA/X.JAVA" "$C" product-analyst)"; assert_blocked "REQ-OPS-002 role scope holds for an upper-cased path"
run_hook $H "$(json_write "$PU/DOCS/BACKLOG/EPIC-01/STORY-009.MD" "$C" product-analyst)"; assert_allowed "REQ-OPS-002 role scope allows an upper-cased docs path"

echo "-- qa-verifier writes only its verify report (F-23)"
run_hook $H "$(json_write "$P/backend/src/main/java/X.java" "$C" qa-verifier)"; assert_blocked "REQ-OPS-002 qa-verifier cannot write source"
run_hook $H "$(json_write "$P/docs/backlog/EPIC-00/STORY-001-x.verify.md" "$C" qa-verifier)"; assert_allowed "REQ-OPS-002 qa-verifier can write its verify report"
finish
