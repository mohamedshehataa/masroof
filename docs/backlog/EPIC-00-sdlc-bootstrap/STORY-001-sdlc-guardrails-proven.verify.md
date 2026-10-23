# Verification report — STORY-001

- **Verifier:** qa-verifier (fresh context)  ·  **Date:** 2026-10-23  ·  **Base:** `7ef8a38f34d6a7e60b38b0c147d94187018f75d1`  ·  **Head:** `7b6d362f1818bd7feb1c5e6053cd31e5c9583500` (fix commit under test; during the run the branch tip moved to `d86e255b7a4caef3fbb79b13a071864c0e6138cd` with STORY-002 backend commits, which do not touch `.claude/hooks/`)
- **Loop:** 2 of 3
- **Story:** `docs/backlog/EPIC-00-sdlc-bootstrap/STORY-001-sdlc-guardrails-proven.md`

## Environment
- Git Bash on Windows 11, `source scripts/env.sh`; shellcheck 0.11.0, jq, Python 3.12, Node 24. Not installed: ruff, yamllint, ktlint, pyflakes.
- Shared checkout on `story/STORY-002-walking-skeleton-health-version`; another agent was editing `backend/` and committed during the run. All hook probes used isolated state (`MASROOF_STATE_FILE/DIR` in the scratchpad) and temp repos/projects (`CLAUDE_PROJECT_DIR` pointed at them). `make selftest` ran in a scratch `git clone`. A `.claude/hooks/tests/test-zz-selftest-plant.sh` that appeared in the shared checkout came from the other agent's concurrent `make selftest` (its process has `PWD=C:/Personal/Masroof`), not from this run.
- `make check-fast MODULE=sdlc < /dev/null` -> **GREEN in 232 s** (real 3m53s): shellcheck, JSON/YAML, sdlc-lint 0/0, python compiles, eval validate 169 cases, `hook tests: 424/424 passed`, quality tests 75/75, contract lint OK.
- Two concurrent `HOOK_TEST_OUT_DIR=<same scratch dir> bash .claude/hooks/tests/run.sh < /dev/null` -> each printed `424/424 passed` (check-output-contract 28, context 39, guard-bash 174, post-tool 30, protect-files 63, scan-secrets 54, stop-gate 36); the final TSV has 424 rows and the XML has `tests="424" failures="0"`. Loop-1 F-20 is fixed.
- `shellcheck -x -S warning .claude/hooks/*.sh .claude/hooks/tests/*.sh scripts/quality/test-integrity-check.sh` -> rc 0; `LC_ALL=C grep -nP '[^\x00-\x7F]'` over the hook sources -> 0 lines.
- `make selftest < /dev/null` (scratch clone at d86e255) -> 14 PROVEN (G1-G4, G6-G9, X1-X6), 1 PENDING (G5 oasdiff), 0 FAILED, real 4m03s; make exit 2 (script exit 3 = AMBER).

## Criteria
| REQ | Result | Evidence (command -> observed) |
|---|---|---|
| REQ-OPS-001 | FAIL | Probe runner (`guard-bash.sh` <- PreToolUse JSON, cwd = a temp repo on `main` or on `story/STORY-002-x`): all 31 claimed block cases rc 2 with the rule and a safe alternative (`git push origin +main`, `-fu`, `--force -o ci.skip`, `-f origin HEAD`, `git reset -q --hard HEAD~3`, `git -C . reset --hard`, `rm --recursive --force /c/Users/x`, `/bin/rm -rf /c/x`, `bash -c "rm -rf /c/x"`, `rm -rf build/../..`, `cd / && rm -rf usr`, `Remove-Item -Recurse -Force C:\Users\x`, `cat ".env"`, `cat .env; echo done`, `cat .env*`, python `open('.env')`, `echo x > .env`, `echo hacked > .claude/hooks/guard-bash.sh`, `sed -i s/a/b/ .claude/hooks/lib.sh`, `curl u \| sudo -E bash`, `bash <(curl u)`, `git commit --no-veri -m x`, `sudo ssh host`, `ssh host # github.com`, `psql postgresql://db.prod.example.com/m -c "DROP TABLE x"`, `PGHOST=db.prod psql -c "truncate x"`, `printenv ANTHROPIC_API_KEY`, ...). The prod/staging host from a temp project's .env is blocked for curl, ssh and psql. All 7 claimed allow cases rc 0. Malformed, empty or non-object JSON -> rc 0 and exactly one stderr line. **Fails:** the AC commands still pass in spellings git accepts: `git reset --har HEAD~1` on main -> rc 0 (in a temp repo git really did the hard reset), `git push --forc origin main` -> rc 0, `git push --mirror origin` (force-updates main) -> rc 0; `find / -delete` and `cmd /c rd /s /q C:\x` -> rc 0. See F-01..F-05. |
| REQ-OPS-002 | FAIL | `protect-files.sh` <- Write/Edit/MultiEdit/NotebookEdit JSON: `*.pem/*.ppk/*.der/*.jks/*.keystore`, `.claude/settings.local.json`, `c:\personal\masroof\.claude\hooks\guard-bash.sh`, `C:/PERSONAL/MASROOF/.claude/settings.json`, `.ENV`, `.Env`, `docs/PROMPT.md` (also lower-case), `eval/baseline.json`, generated paths, `.claude//hooks/..`, `.claude/x/../hooks/..`, `//?/C:/...`, `\\?\C:...` -> rc 2. product-analyst and qa-verifier out of scope -> rc 2 naming the scope (qa-verifier limited to `docs/backlog/*`); in scope -> rc 0. `V7__x.sql` -> rc 0. The applied-migration case is proven by the hook suite and by selftest G3. **Fails:** Windows aliases of the same files pass: `C:/Personal/Masroof/CLAUDE~1/hooks/guard-bash.sh`, `.claude/SETTIN~1.JSO`, `eval/BASELI~1.JSO`, `.claude/settings.json::$DATA`, `\\.\C:\Personal\Masroof\.claude\hooks\guard-bash.sh` -> rc 0. In a scratch copy, Node `fs.writeFileSync` through an 8.3 name and through `::$DATA` overwrote the real file (F-06). |
| REQ-OPS-003 | PASS | `scan-secrets.sh` <- generated payloads (secrets made at runtime, never printed): Anthropic key, AWS id, JWT in docs/, PEM header, `WHATSAPP_APP_SECRET=<32 hex>`, PAN plain/spaces/dots/double spaces/Arabic-Indic (also spaced), EG mobile `+20 1xx xxx xxxx`/`01xx-xxx-xxxx`/Arabic-Indic, NotebookEdit `new_source`, MultiEdit -> rc 2 naming the detector; a 12-char slice of each secret is absent from stdout and stderr. 3000 lines of 13-digit epoch-ms, `.env.example` `changeme`, `sk-ant-...` in a fence -> rc 0. 5 MB timings: clean 980/965 ms, key at the end 433/432 ms (blocked), all digits 561/547 ms. Edge problems in F-08..F-10. |
| REQ-OPS-004 | PASS | Temp repo + fake make (logs its args, prints `ExpenseTest ... FAILED`): fresh state, clean tree, red change already committed -> make runs once with `MODULE=backend sdlc web`, rc 2 `block 1/3`, `ExpenseTest` in stderr, `stop_blocks=1`; 2nd -> `block 2/3`; 3rd -> rc 0, `systemMessage`, 1 `RED` line in PROGRESS.md, 1 `GATE-` entry, `stop_blocks=0`. A green run records `last_green`; unchanged tree -> 0 make calls; docs-only -> 0; untracked root `docker-compose.yml` -> `MODULE=sdlc`; `web/` + `.claude/` -> `MODULE=sdlc web`. Gap: non-ASCII file names (F-11). |
| REQ-OPS-005 | PASS | `check-output-contract.sh`: full contract rc 0; `STATUS: PASS \| FAIL \| BLOCKED` and `PASS\|FAIL\|BLOCKED` -> rc 2 ("contract template"); all sections inside a fence with STATUS outside -> rc 2 listing the 5 sections; no STATUS / `DONE` / missing CRITERIA -> rc 2 naming it; text after STATUS -> rc 2; `general-purpose` and `my-custom` -> rc 0 with exactly one stderr line. Gap in F-12. |
| REQ-OPS-006 | PASS | Temp project (phase 2, STORY-001, implement, loop 1): stdout has `Phase: 2`, `STORY-001`, `implement`, `1/3`, PROGRESS lines 11-30 only, `Open escalations (2)` (resolved entry excluded), invariants only up to the next heading, `READY 1`, `DONE 1`, `SessionStart: resume`, 965 bytes. PROGRESS.md missing -> `PROGRESS.md: none`, rc 0. Zero open -> a single `Open escalations (0)` and no stray `0` line. 1500-char PROGRESS lines + 40 long escalations -> 8532 bytes; with long invariants added -> exactly 9500 bytes. Gap in F-13. |
| REQ-OPS-007 | PASS | Temp repo: a committed bash suite with 5 `assert_*` cut to 2 by backend-engineer -> `TEST INTEGRITY WARNING ... assertions decreased 5 -> 2` plus a log line; a scripts suite `expect <rc>` cut from 3 to 1 -> warning; `ExpenseTest.java` cut from 3 to 1 via an all-lower-case absolute path -> warning naming `backend/src/test/java/ExpenseTest.java` (compared with HEAD); non-test file -> no output and no log line. `scripts/quality/test-integrity-check.sh <base>` in the temp repo -> `5 -> 4`, `3 -> 2`, `3 -> 1`, `TEST_INTEGRITY: VIOLATION`, rc 1. Gap in F-14. |
| REQ-OPS-008 | PASS | Temp project, `.env` `SDLC_AI_BUDGET_USD=300`, `docs/SDLC_METRICS.md` row `\| AI spend (USD) \| <v> \|`: `350` -> `BUDGET EXCEEDED ... $350 > budget $300`, rc 0; `120` -> silent; `250` -> 80% info; `1,234.50` -> `$1234.50 > $300`; `$350` -> `$350 > $300`; `abc`, empty, or metrics missing -> rc 0 silent; budget `"1,000" # cap` parsed as 1000. The story names `docs/SDLC_METRICS.md` (line 58). Gap in F-17. |
| REQ-OPS-009 | PASS | `PreCompact trigger=auto` -> rc 0, `last_compact` ISO with `T`, `last_compact_trigger=auto`, `story` and `stage` kept, 1 snapshot. `snapshots` existing as a file -> rc 0, exactly one stderr line explaining, state.json still updated (`manual`). Malformed JSON -> rc 0. |
| REQ-OPS-010 | FAIL | `format-changed.sh` <- PostToolUse Write: `bad.sh` -> `scripts/bad.sh:2: info: ... [SC2086]` / `:3: error: ... [SC2045]` (file:line: message), 563 ms; `good.sh` -> nothing; `bad.py` -> `scripts/bad.py:1: SyntaxError: invalid syntax`; `bad.yaml` -> `scripts/bad.yaml:2: invalid YAML: ...`; `MASROOF_SHELLCHECK=no-such-shellcheck` -> exactly one stderr line `format-changed: shellcheck not installed, skipped`; `.kt` without ktlint -> exactly one line `format-changed: ktlint not installed, skipped`. **Fails the "no shell interpolation of file names" claim:** a file named `x#;e touch PWNED-BY-FILENAME;#.kt` (fake ktlint on PATH printing one finding) made the hook create `PWNED-BY-FILENAME` (F-07). |
| Hook sources ASCII + shellcheck | PASS | see Environment. |
| run.sh concurrent runs | PASS | see Environment. |

## Findings
| ID | Severity | Location | Problem | Reproduce |
|---|---|---|---|---|
| F-01 | MAJOR | `.claude/hooks/guard-bash.sh:196-198,236-246,278-280` | git accepts unique prefixes of long options, but only `--no-v*` is handled. `--hard` and `--force*` are matched literally, and `--mirror` (which force-updates every remote ref, main included) is not treated as a force. | Temp repo on main: `git reset --har HEAD~1`, `git push --forc origin main`, `git push --mirror origin` -> rc 0. In a scratch copy, `git reset --har HEAD~1` printed `HEAD is now at ...` and removed a committed file. |
| F-02 | MAJOR | `guard-bash.sh:254-272` | `check_commit_n` stops at the first `-m`, so a `-n` after the message is never seen. This is the loop-1 F-06(b) false-positive fix opening a bypass. | `git commit -m x -n`, `git commit -mx -n` -> rc 0. In a scratch repo with a failing pre-commit hook, a plain commit gave rc 1 and `git commit --allow-empty -m x -n` gave rc 0 (hook skipped). |
| F-03 | MAJOR | `guard-bash.sh:293-309` | The main branch pointer can be rewritten with `update-ref`, while the equivalent `git branch -f main` is blocked. | `git update-ref refs/heads/main HEAD~1` and `git update-ref -d refs/heads/main` -> rc 0 (the first moved main in a scratch repo). |
| F-04 | MAJOR | `guard-bash.sh:119-137,436-440,449-468` | Deletes outside the worktree, or of the repo itself, still pass: (a) `find` deletes are not inspected; (b) the `cmd /c` wrapper is not skipped (`/c` is taken as the command); (c) absolute or `./` spellings of the repo root / `.git` pass `safe_abs`, although `.`, `*` and `.git` are blocked; (d) `$PWD` and variables pass. | `find / -delete`; `find /c/Users -name x -exec rm -rf {} +`; PowerShell `cmd /c rd /s /q C:\x`; `rm -rf /c/Personal/Masroof/`; `rm -rf /c/Personal/Masroof/.git`; `rm -rf ./.git`; `rm -rf /c/Personal/Masroof/*`; `rm -rf $PWD`; `D=/c/x; rm -rf $D`; `Get-ChildItem C:\Users\x -Recurse \| Remove-Item -Recurse -Force` -> all rc 0. |
| F-05 | MAJOR | `guard-bash.sh:95-107,340-386,466` | Shell writes to the locked guardrail files are still possible. Path matching is a string pattern on the raw token, so `//`, `x/../` and an earlier `cd` defeat it. `git checkout/restore`, interpreters and .NET APIs are not covered. Reading .env through a glob or .NET also passes. | `echo x > .claude//hooks/guard-bash.sh`; `echo x > .claude/x/../hooks/guard-bash.sh`; `cd .claude && echo x > hooks/guard-bash.sh`; `git checkout HEAD~1 -- .claude/hooks/guard-bash.sh`; `python -c "open('.claude/hooks/guard-bash.sh','w')..."`; PowerShell `[IO.File]::WriteAllText('.claude/settings.local.json','{}')`; `[IO.File]::ReadAllText('.env')`; `cat .e?v`; `cat .[e]nv`; `echo x > CLAUDE~1/hooks/guard-bash.sh`; `sed -i s/a/b/ .claude/SETTIN~1.JSO` -> all rc 0. |
| F-06 | MAJOR | `.claude/hooks/protect-files.sh:14-16` and `lib.sh:130-156` | Paths are canonicalised only for case and separators. NTFS 8.3 short names (this checkout has `CLAUDE~1` for `.claude`, plus `SETTIN~1.JSO` and `BASELI~1.JSO`), the `::$DATA` stream suffix and the \\.\ device prefix all name a protected file but skip every rule. | Write JSON with `file_path` = `C:/Personal/Masroof/CLAUDE~1/hooks/guard-bash.sh`, `C:/Personal/Masroof/.claude/SETTIN~1.JSO`, `C:/Personal/Masroof/eval/BASELI~1.JSO`, `C:/Personal/Masroof/.claude/settings.json::$DATA`, `\\.\C:\Personal\Masroof\.claude\hooks\guard-bash.sh` -> rc 0. Scratch check: Node `fs.writeFileSync('<dir>/sub/SETTIN~1.JSO')` and `('<dir>/sub/settings.json::$DATA')` each replaced the content of `settings.json` (`cmd /c dir /x` lists the short names). |
| F-07 | MAJOR | `.claude/hooks/format-changed.sh:61,69,78,87,100` | The project-relative path is interpolated into a sed script (`sed -E "s#...#$rel:\1: #"`). A file name containing `#;e <cmd>;#` ends the `s` command, and GNU sed's `e` runs `<cmd>` in a shell. This happens whenever ruff, ktlint, eslint or yamllint (or shellcheck without jq) produce a finding. | Temp project: create `backend/x#;e touch PWNED-BY-FILENAME;#.kt`, put a fake `ktlint` printing `"$1:1:1: Unexpected blank line"` first on PATH, send a PostToolUse Write for it -> `PWNED-BY-FILENAME` is created in the hook's cwd and the feedback reads `backend/xUnexpected blank line`. |
| F-08 | MINOR | `.claude/hooks/scan-secrets.sh:119-146` | Card false positives: every window of space/dot/dash-joined digit groups is Luhn-checked, so ordinary amount lists trip it. False negatives for other separators. | 11 of 40 one-line records like `\| 2026-10-15 \| spent 12783.55 19914.97 79.89 EGP \|` -> rc 2 `card-number(luhn)`. Also blocked: lines of space-separated amounts, Arabic-Indic amount lists, `t=1729700000000.123`, digit-only UUIDs. Not blocked: a real PAN joined by NBSP, U+2009, tab or `/`, a PAN in full-width digits, mobile `(010) 1234-5678`. The 189 committed eval/fixtures/docs files replay cleanly, except `docs/TRACEABILITY.md`, which contains the public test PAN 5105105105105100 (not in `TEST_PANS`). |
| F-09 | MINOR | `scan-secrets.sh:78,88` | `ASSIGN` backtracks quadratically on a long line of keyword text. Once the run passes the 20 s hook timeout the write is not blocked, so a `*_SECRET=` on the next line goes unscanned. | One 100 KB line of `secret` repeated, then `WHATSAPP_APP_SECRET=<hex>`: 16.4 s; 200 KB: 77.8 s. |
| F-10 | MINOR | `scan-secrets.sh:121-146` | The window loop is O(groups x 19) in Python. | 5 MB of space-separated single digits: 10.7 s (AC < 2 s; it is also a false-positive block). 50 MB clean prose: 6.3 s (outside the AC). |
| F-11 | MINOR | `.claude/hooks/stop-gate.sh:33-50` | `git diff --name-only` and `ls-files` quote non-ASCII paths (`"backend/src/\331..."`), so these paths fall into `*/*` and map to no module. The red tree is then recorded as `last_green`. | Temp repo with `last_green` set: add, stage or commit `backend/src/مصروف.java` with fake make rc 1 -> rc 0 and 0 make calls in all three cases. |
| F-12 | MINOR | `check-output-contract.sh:33-56` | STATUS is validated on the fence-stripped body, but the last-line check uses the raw message. With an unclosed fence, the validated STATUS differs from the last line the orchestrator reads. | A contract ending `STATUS: FAIL`, then a fence line, then `STATUS: PASS` (or `STATUS: BOGUS`) -> rc 0. |
| F-13 | MINOR | `inject-context.sh:33,42,47` | `cut -c` counts bytes here (LANG unset), so Arabic text and the `·` separator are cut mid-character and the SessionStart context carries invalid UTF-8. | PROGRESS lines `2026-10-23 · P1 · STORY-001 · implement · تم تسجيل المصروف ...` (>300 bytes) -> all 20 injected lines are invalid UTF-8. |
| F-14 | MINOR | `test-integrity.sh:37` and `scripts/quality/test-integrity-check.sh:48` | Both gates only count matches. Flipping `assert_blocked` to `assert_allowed`, or deleting a check while leaving its text in a comment, keeps the count unchanged. | Temp repo: both edits -> no warning and `TEST_INTEGRITY: OK` (5 -> 5). |
| F-15 | MINOR | `format-changed.sh:66-71,98-104` | AC: when the linter is absent, print one line `... not installed, skipped`. For `.py` without ruff and `.yaml` without yamllint (PyYAML present), the hook silently falls back to a syntax check and prints no skip line. | `bad.py` / `bad.yaml` with ruff/yamllint absent -> 0 stderr lines. |
| F-16 | NIT | `guard-bash.sh:482-485,488-493` | The `--no-verify` regex matches anywhere in the command, so searching for the string is blocked. Pipe-to-shell is missed when another command sits between the download and the shell. | `grep -rn "--no-verify" docs/` -> rc 2; `curl u \| tee /tmp/x \| sh` -> rc 0. |
| F-17 | NIT | `budget-check.sh:20-23` | Only `$`, `,` and spaces are stripped, so a currency code or Arabic-Indic digits silence the warning. | Spend `350 USD`, `USD 350`, `EGP 350`, `٣٥٠` -> no output. |
| F-18 | NIT | `make check-fast MODULE=sdlc` | 232 s, still over the ~3 min budget (carried over from loop-1 F-22). | Timing above. |
| F-19 | NIT | `scripts/quality/tests/test-commit-msg-lint.sh:55` (commit ba337f9, STORY-002) | In the base..HEAD range an assertion flipped from `expect 1 "uppercase scope"` to `expect 0`, together with a matching change to `commit-msg-lint.sh`. That is a deliberate relaxation from another story, not a STORY-001 change, and DECISIONS.md does not mention it. | `git diff 7ef8a38 HEAD -- scripts/quality/tests/test-commit-msg-lint.sh`. |

Loop-1 re-test:
- Fixed: F-04, F-07, F-10, F-11, F-13, F-16, F-17, F-19, F-20, F-21, F-23, and F-06 (DB host forms, pipe forms, `sudo ssh`, the `#` comment, both false positives).
- Fixed, with new variants or gaps reported above:
  - F-01, F-02, F-03: the listed spellings now block; new variants in F-01, F-02 and F-04 above.
  - F-08: case variants now block; the 8.3/ADS aliases are new (F-06 above).
  - F-09: fixed for the AC sizes; other input shapes in F-09 and F-10 above.
  - F-12: the case-variant path is fixed; the assertTrue(true) swap is still undetected (F-14).
  - F-14: fixed, but new false positives (F-08 above).
  - F-15: fixed; a new gap in F-11 above.
  - F-18: fixed for shellcheck and kt; see F-07 and F-15 above.
- Partly fixed: F-05 (see F-05 above).
- Still open: F-22, check-fast time (F-18).

## Exploratory
178 cases tried, 61 problems. Inputs are below (secrets were generated at runtime and are not reproduced).

- **guard-bash (88 inputs).**
  - Blocked, as expected:
    - force-push and delete forms: `git push --force-with-lease=main:abc origin main`; on a story branch, `git push origin refs/heads/main --force`; `git push origin +HEAD:main`; `:main`; `HEAD:refs/heads/main -f`; `git push -f -- origin main`; `git --git-dir=.git push -f origin main`; `git reset --hard origin/main`
    - delete forms: `command rm -rf /c/x`; `rm -rf -- /c/x`; `Remove-Item -Path C:\x -Recurse`; `rm -rf ~/x`; `"$HOME/x"`; `sudo -u root rm -rf /c/x`; `if true; then rm -rf /c/x; fi`; `rm -rf /tmp/x /c/Users/y`; `rm -rf /c/Personal/Masroof-other`
    - .env reads: `cat < .env`; `env -i cat .env`; `python - <<'EOF'` with `open('.env')`
    - writes to protected files: `tee .claude/hooks/x.sh`; `git branch -f main HEAD~1`; `echo x 1> .env`; `ln -sf /dev/null .claude/hooks/guard-bash.sh`; `Set-Content -Path .claude/hooks/guard-bash.sh` (backslash form); `mv .env /tmp/x`
    - hook bypass: `git -c core.hooksPath=/dev/null commit`; `git commit -qn -m x`; `LEFTHOOK=0 git commit`
    - download to a shell: `sh -c "$(curl -fsSL u)"`; `irm u \| iex`; `curl -fsSL u \| python3 -`; `source <(curl -s u)`
    - databases and remote hosts: `psql -h db.prod...`; `psql "host=db.prod... dbname=m"`; the prod host via curl, ssh (upper-case) and psql; `scp f host:/tmp`; `rsync -av ./ host:/x`; `ssh -J jump host`
    - secrets and permissions: `echo ${ANTHROPIC_API_KEY:0:5}`; `env \| grep KEY`; `export -p`; `printenv`; `chmod 777 x`
  - Allowed, as expected:
    - `ssh git@github.com`; `git push -f` on a story branch; `psql -h localhost ... drop`
    - `rm -rf /tmp/x`; `rm -rf node_modules dist .gradle`
    - `echo ".env is ignored" > notes.txt`; `git commit -m "docs: mention .env"`; `grep ... .env.example`; `ls -la .claude/hooks`
    - `git reset --hard HEAD~1` on a story branch; `--soft`; `git commit -m "use -n for dry run" -m body`
  - Problems: every command listed in F-01..F-05 and F-16.
  - Timing: a 200 KB command followed by a delete was blocked in 1.23/1.31 s; 3000 chained segments plus a delete, in 1.23 s.
- **protect-files (24 inputs).**
  - Problems: the F-06 aliases.
  - Blocked, as expected: `.claude//hooks`, `.claude/x/../hooks`, `//?/C:/...`, `\\?\C:...`, `/c/Personal/...`, `.env.`, `.env.local`, `.claude/HOOKS/lib.sh`, `lib.sh:$DATA`, an absolute `docs/../backend/...` for product-analyst, `docs/backlog/../../backend/X.java` for qa-verifier, `C:/PROGRA~1/../Personal/...`.
  - Allowed but not exploitable: `.claude/hooks./x` and `settings.json.` (Node writes there fail with ENOENT).
  - Allowed, harmless: a Cyrillic `о` in `settings.jsоn` (a different file); a relative `docs/../backend/X.java` for product-analyst (Claude Code sends absolute paths).
- **scan-secrets (27 inputs).**
  - Problems: the F-08..F-10 inputs.
  - Allowed, as expected: `+201000000012` / `01000000012` (anonymised), Stripe `4242 4242 4242 4242`.
  - Blocked, as expected: Persian-digit PAN and mobile, a mixed-separator PAN, a `0020` mobile; 50 MB with a key at the end (613 ms); 5 MB of keyword-heavy lines with a secret at the end (721 ms).
  - Replay of 189 committed files: 2 blocked, both expected (the loop-1 report quotes PANs on purpose; TRACEABILITY.md holds a test PAN).
- **stop-gate (5 inputs).** Problem: a non-ASCII name, untracked, staged or committed (F-11). OK: a name with a space (`MODULE=web`), root `NOTES.MD` (no gate).
- **check-output-contract (10 inputs).**
  - Allowed: bold `**STATUS:** PASS`, `STATUS: PASS (with notes)`, CRLF line endings.
  - Blocked: lower-case `status: pass`, `PASS/FAIL`, a `~~~` fence, an empty message.
  - Other: an empty agent (rc 0), malformed JSON (rc 0).
  - Problem: unclosed fence, 2 inputs (F-12).
- **inject-context (3 inputs).** OK: long ASCII lines, the 9500-byte cap. Problem: Arabic and `·` lines (F-13).
- **test-integrity (3 inputs).** Problems: a flipped assertion, an assertion kept only in a comment (F-14). OK: a non-test file.
- **budget-check (12 inputs).** Problems: `٣٥٠`, `350 USD`, `USD 350`, `EGP 350` (F-17). OK: `-50`, `1e3`, `300.00`, `300.01`, `$ 1,234`, a quoted budget with a comment.
- **snapshot (2 inputs).**
  - icacls deny-write on the snapshots dir: inconclusive, because the owner could still write.
  - Malformed JSON: OK.
- **format-changed (3 inputs).** Problems: the file-name injection (F-07); py/yaml fallback without a skip line (F-15).
- **run.sh (1 input).** Two concurrent runs: OK.

## Test integrity
OK.
- `bash scripts/quality/test-integrity-check.sh 7ef8a38f34d6a7e60b38b0c147d94187018f75d1` -> `assertions: 323 (base) -> 549 (now)`, no new skip markers, no deletions, `TEST_INTEGRITY: OK`, rc 0.
- The count went up in every hook suite: guard-bash 63 -> 165, protect-files 44 -> 63, scan-secrets 32 -> 54, stop-gate 27 -> 36, contract 20 -> 28, context 29 -> 40, post-tool 26 -> 42. The untracked STORY-002 backend tests from the concurrent session are additions only.
- `git diff <base> HEAD` of the test dirs removes only three kinds of lines:
  - plumbing: the json_bash comment/env lines and the run.sh TSV paths;
  - the `+main` assertion, now `assert_blocked "REQ-OPS-001 blocks a +main refspec ..."`, which turns an asserted bypass into a block (stronger);
  - the commit-msg-lint `uppercase scope` flip from STORY-002 (F-19). This is a documented behaviour change in another story, not a STORY-001 weakening.
- The checker is count-based and cannot see flips like these (F-14).
- `.claude/state/test-integrity.log` has no new entries since loop 1; the last 5 are the 18:35-18:36Z fixture plants in `test-test-integrity-check.sh`.

STATUS: FAIL
