---
id: STORY-001
epic: EPIC-00
title: SDLC guardrails are proven
phase: 0
status: IN_PROGRESS
size: M
modules: [sdlc]
depends_on: []
reqs: [REQ-OPS-001, REQ-OPS-002, REQ-OPS-003, REQ-OPS-004, REQ-OPS-005, REQ-OPS-006, REQ-OPS-007, REQ-OPS-008, REQ-OPS-009, REQ-OPS-010, REQ-OPS-011]
implements: ["PROMPT.md S7", "PROMPT.md S15 item 6", "PROMPT.md S2 (loop limit)", "PROMPT.md S16"]
owner_agent: devops-engineer
---

# STORY-001 — SDLC guardrails are proven

## Goal
Every hook wired in `.claude/settings.json` demonstrably blocks or warns on the violation it exists for, so the product can be built through the SDLC with the guards as the approval (PROMPT S7, S15 item 6). Evidence is the hook test suite (`make hooks-test`) plus `docs/SDLC_SELFTEST.md`.

**Bounded contract (spec fix, loop 3).** The pre-execution guards (REQ-OPS-001, REQ-OPS-002) defend a *closed, enumerated* set of command classes and path spellings. A command outside that set is out of scope for REQ-OPS-001 and is not a verification failure of it. The residual risk is named under "Non-goals" and is covered by deterministic after-the-fact backstops: REQ-OPS-011 (protected files are restored after every shell call, whatever spelling wrote them) and the lefthook `pre-push` guard for `main` (CI branch protection is the remote guard, STORY-016). A verifier proves REQ-OPS-001 wrong by showing a command **inside** a listed class that passes, or an allow-list command that is blocked.

## Requirements and acceptance criteria
### REQ-OPS-001 — guard-bash blocks an enumerated set of destructive and secret-leaking command classes
Scope: `PreToolUse` events for the `Bash` and `PowerShell` tools. "Blocked" means exit 2 and one stderr message that names the matched rule and a `Safe alternative:`. "Allowed" means exit 0 with no stderr.

**Normalisation (applies to every class).** Before matching, the hook:
- (a) splits the command on `;`, `&&`, `||`, `|`, `&` and newlines, and checks every segment;
- (b) removes quotes (`cat ".env"` ≡ `cat .env`) and ignores a trailing `# comment`;
- (c) skips wrappers: `sudo [-E]`, `env VAR=x`, `command`, `exec`, `nice`, `time`, `nohup`, `bash|sh -c "<cmd>"`, `cmd /c <cmd>`, `powershell|pwsh -Command <cmd>`, leading `VAR=value` assignments, absolute binary paths (`/bin/rm`, `/usr/bin/git`) and git global options (`-C <dir>`, `-c k=v`, `--git-dir`);
- (d) tracks `cd`/`pushd`/`Set-Location` within the same command, so later relative paths resolve against the new directory;
- (e) resolves paths with the canonicalisation of REQ-OPS-002 (separators, `//`, `.`/`..`, case, `\\?\`/`\\.\` prefix, `::$DATA` suffix, 8.3 short names of existing components).

"main" below means `main` or `master` in any spelling git accepts (`main`, `refs/heads/main`, `HEAD:main`, `+main`). A long option counts in **every abbreviation git accepts** (git takes any unambiguous prefix).

**Given** a temp repo with `main` checked out **When** the command is in one of these classes **Then** it is blocked:
- **B1 force-push to main.** `git push` whose destination is main (an explicit refspec, or no refspec / `HEAD` while main is checked out) with any of: `--force`, `--force-with-lease[=…]`, `--force-if-includes` or an accepted prefix (`--forc`, `--force-w`); `-f` alone or in a short cluster (`-fu`, `-uf`); a `+` refspec (`+main`, `+HEAD:main`, `+refs/heads/main`). `--mirror` or a prefix of it (`--mirr`) is blocked from any branch, because it force-updates main. Examples: `git push --force origin main`, `git push --forc origin main`, `git push -f origin HEAD`, `git push --force -o ci.skip`, `git push origin +main`, `git push --mirror origin`.
- **B2 hard reset of main.** `git reset` with `--hard` or an accepted prefix (`--har`, `--ha`) anywhere among its options, while the directory the command targets (after `cd` / `-C`) has main checked out. Examples: `git reset --hard`, `git reset -q --hard HEAD~3`, `git reset --har HEAD~1`, `git -C . reset --hard`.
- **B3 rewriting the main ref.** `git update-ref` (incl. `-d`) or `git symbolic-ref` whose arguments name main. `git branch` with `-D`, `-f`/`--force` or `-M` (alone or clustered, e.g. `-Df`) whose arguments name main. Examples: `git update-ref refs/heads/main HEAD~1`, `git update-ref -d refs/heads/main`, `git branch -f main HEAD~1`, `git branch -D main`.
- **B4 hook bypass.** `--no-verify` or an accepted prefix (`--no-v`, `--no-veri`) as an option of any `git` subcommand. `-n` as an option of `git commit`, alone or in a short cluster (`-an`, `-nm x`), **anywhere** in the option list, including after `-m <msg>` (`git commit -m x -n`, `git commit -mx -n`). Setting or overriding `core.hooksPath` (`git -c core.hooksPath=/dev/null commit …`, `git config core.hooksPath x`, `git config --unset core.hooksPath`). `LEFTHOOK=0` as a prefix assignment, `export LEFTHOOK=0` or `$env:LEFTHOOK=0`.
- **B5 deletes outside the safe area.** `rm`, `rmdir`, PowerShell `Remove-Item`/`ri`/`del`/`rm`, `cmd` `rd`/`rmdir`/`del` (incl. `/s /q`), `find … -delete`, `find … -exec|-execdir rm …`, and `Get-ChildItem … | Remove-Item`, when any target is:
  - `/`, `/*` or a drive root (`C:\`, `/c`);
  - `~`, `$HOME`, or anything under them that is outside the repo;
  - the repo root in any spelling (`.`, `./`, `*`, `/c/Personal/Masroof`, `/c/Personal/Masroof/`, `/c/Personal/Masroof/*`, `C:\Personal\Masroof`, `$PWD` at the root);
  - `.git` or anything under it (`.git`, `./.git`, `<repo>/.git`);
  - a path that leaves the repo after `..` is resolved (`..`, `build/../..`, `cd build && rm -rf ../..`);
  - a target that contains an unresolved shell variable or substitution (`$PWD`, `$D`, `${X}`, `$(…)`, backticks), always;
  - any absolute path that is not strictly inside the repo, `/tmp` (or `/var/tmp`) or the session scratchpad.

  Examples: `rm -rf /c/Users/x`, `rm --recursive --force /c/Users/x`, `cd / && rm -rf usr`, `rm -rf /c/Personal/Masroof/.git`, `rm -rf $PWD`, `D=/c/x; rm -rf $D`, `find / -delete`, `find /c/Users -name x -exec rm -rf {} +`, `cmd /c rd /s /q C:\x`, `Remove-Item -Recurse -Force C:\Users\x`.
- **B6 pipe-to-shell.** A pipeline in which a later stage is a shell (`sh`, `bash`, `zsh`, `dash`, `pwsh`, `powershell`, `iex`/`Invoke-Expression`) and an earlier stage is a downloader (`curl`, `wget`, `Invoke-WebRequest`/`iwr`, `Invoke-RestMethod`/`irm`) or `base64 -d`/`--decode`, with any stages in between (`curl u | tee f | sh`). Also process substitution of a downloader as a shell's script (`bash <(curl u)`, `sh <(wget -qO- u)`). Examples: `curl u | sh`, `curl u | sudo -E bash`, `echo aGk= | base64 -d | sh`.
- **B7 world-writable.** `chmod` (incl. `-R`) with mode `777`, `0777`, `a+rwx` or `ugo+rwx`.
- **B8 destructive SQL on a non-local DB.** A segment containing a DB client (`psql`, `mysql`, `mariadb`, `sqlcmd`, `mongosh`) and `DROP TABLE|DATABASE|SCHEMA` or `TRUNCATE` (case-insensitive), whose host is not local. The host comes from `-h`/`--host`, `PGHOST=`/`MYSQL_HOST=` or a connection URI. Local hosts are `localhost`, `127.0.0.1`, `::1`, `host.docker.internal` and the service names in the project's compose file. Examples: `psql postgresql://db.prod.example.com/m -c "DROP TABLE x"`, `PGHOST=db.prod psql -c "truncate x"`.
- **B9 docker data loss.** `docker system prune` with `-a`/`--all` (incl. `-af`, `-fa`), `docker volume prune`, `docker volume rm`.
- **B10 environment dumps.** Bare `printenv` or `env` (no command to run, alone or piped); `printenv <NAME>` where NAME matches B11; PowerShell `Get-ChildItem env:`/`gci env:`/`ls env:`/`dir env:`. Examples: `printenv`, `printenv ANTHROPIC_API_KEY`, `bash -c "printenv"`.
- **B11 echoing secrets.** `echo`, `printf`, `Write-Output` or `Write-Host` whose arguments reference a variable whose name contains `KEY`, `SECRET`, `TOKEN` or `PASSWORD` (case-insensitive): `$ANTHROPIC_API_KEY`, `${WHATSAPP_APP_SECRET}`, `$env:JWT_TOKEN`.
- **B12 reading `.env`.** A reader whose argument resolves (after normalisation and glob expansion against the working directory) to `.env` or `.env.<x>`, other than a name ending in `.example`. Readers: `cat`, `tac`, `less`, `more`, `head`, `tail`, `nl`, `grep`/`egrep`/`fgrep`/`rg`, `awk`, `sed` (without `-i`), `cut`, `sort`, `uniq`, `strings`, `xxd`, `od`, `hexdump`, `base64`, `diff`, `cmp`, `source`/`.`, input redirection `< .env`, `cp`/`mv`/`scp`/`rsync`/`tar`/`zip` with it as a source, `curl` with `@.env` data, PowerShell `Get-Content`/`gc`/`type`/`cat` and `[IO.File]::Read*`. Also an interpreter one-liner (`python[3] -c`, `node -e`, `perl -e`, `ruby -e`, `pwsh -c`) whose code contains a string literal naming such a file. Examples: `cat .env`, `cat ".env"`, `cat .env; echo done`, `cat .env*`, `cat .e?v`, `cat .[e]nv`, `python -c "print(open('.env').read())"`, `[IO.File]::ReadAllText('.env')`.
- **B13 shell writes to protected paths while hooks are locked.** "Locked" means neither `.claude/state.json` `hooks_unlocked: true` nor `MASROOF_HOOKS_UNLOCK=1` is set.
  - Protected set: `.claude/hooks/**`, `.claude/settings.json`, `.claude/settings.local.json`, `docs/PROMPT.md`, `eval/baseline.json`, `.env`, `.env.*`.
  - Writers, with the path as the destination: redirection (`>`, `>>`, `>|`, `1>`, `&>`), `tee [-a]`, `sed -i`/`perl -i`, `cp`/`mv`/`install`/`ln`/`dd of=`/`truncate`/`touch`, `git checkout [<rev>] -- <path>`, `git restore [--source …] <path>`.
  - Interpreter one-liners whose code names the path in a write call (`open(…,'w'|'a')`, `write_text`, `fs.writeFileSync`, `fs.appendFileSync`).
  - PowerShell `Set-Content`/`Add-Content`/`Out-File`/`New-Item -Force`/`Copy-Item`/`Move-Item` and `[IO.File]::Write*`/`AppendAll*`.

  Examples: `echo x > .claude/hooks/guard-bash.sh`, `echo x > .claude//hooks/guard-bash.sh`, `echo x > .claude/x/../hooks/guard-bash.sh`, `cd .claude && echo x > hooks/guard-bash.sh`, `sed -i s/a/b/ .claude/hooks/lib.sh`, `git checkout HEAD~1 -- .claude/hooks/guard-bash.sh`, `python -c "open('.claude/hooks/guard-bash.sh','w')"`, `[IO.File]::WriteAllText('.claude/settings.local.json','{}')`, `echo x > .env`.
- **B14 remote hosts.** Outside an active `/release` (`.claude/state.json` `release_active` is not true): `ssh`, `scp`, `sftp` or `rsync` with a remote host (`host:path`, `user@host`, `-e ssh`) other than `github.com`/`ssh.github.com`. Also any command naming the value of `PROD_HOST` or `STAGING_HOST` read from the project's `.env` (the value is never printed). Examples: `ssh deploy@1.2.3.4`, `sudo ssh host`, `ssh host # github.com`, `curl https://<PROD_HOST>/`.

**Given** the same events **When** the command is on the allow-list **Then** it is allowed (exit 0, no stderr). Allow-list:
- `make check-fast MODULE=sdlc`, `make hooks-test`, `./gradlew test`, `npm ci`, `npm run build`;
- `git status`, `git add -A`, `git commit -m "feat(sdlc): x"`, `git fetch`, `git push -u origin story/STORY-002-x`;
- on a `story/STORY-xxx` branch: `git push --force-with-lease origin story/STORY-xxx`, `git reset --hard origin/story/STORY-xxx`, `git rebase origin/main`;
- `docker compose up -d`, `docker compose down`, `docker compose logs backend`;
- `cp .env.example .env`, `rm -rf build/`, `rm -rf /tmp/masroof-x`;
- `grep -rn "--no-verify" docs/`, `git commit -m "fix: handle the -n flag"`, `git commit -m "docs: never rm -rf the repo"`;
- `printenv HOME`, `ssh -T git@github.com`, `git config --get core.hooksPath`.

**Non-goals.** These are outside the defended set. The residual risk is accepted and backstopped by REQ-OPS-011 and the `pre-push` guard:
1. Commands whose dangerous part is assembled at run time from shell variables, command substitution, aliases or functions (`B=main; git push -f origin $B`, `$(echo rm) -rf /`). Delete targets are the exception: B5 always blocks a target that contains a variable.
2. Obfuscation through encoding or string building inside an interpreter (`python -c "import os; os.system('r'+'m -rf /')"`, `eval "$(printf …)"`). `base64 -d | sh` is still blocked by B6.
3. Heredoc bodies (`bash <<EOF … EOF`, `python - <<EOF`).
4. Commands run indirectly from files (scripts, Makefile recipes, git aliases).
5. Commands not listed in B1–B14.

A bypass in these categories is a finding against REQ-OPS-011 (if a protected file stayed changed) or a request for new scope. It is not a REQ-OPS-001 failure.

Unhappy path: **Given** the hook receives malformed JSON, empty stdin or a non-object JSON value **When** it runs **Then** it exits 0 and prints exactly one warning line on stderr (it never blocks on a parse error).
Unhappy path: **Given** a command that only *mentions* a blocked pattern as data, inside a quoted `-m` commit message or as a `grep` pattern **Then** it is allowed (see the allow-list).

### REQ-OPS-002 — protect-files blocks protected paths and enforces role write scopes
**Canonicalisation.** Before any rule is matched, `tool_input.file_path` (and `notebook_path`) is canonicalised:
- `\` becomes `/`, and the `C:/`, `c:\` and `/c/` drive forms are unified;
- a leading `\\?\`, `\\.\`, `//?/` or `//./` is removed;
- an NTFS stream suffix (`::$DATA`, `:<name>`, `:<name>:$DATA`) is removed;
- `//`, `.` and `..` segments are collapsed;
- every existing path component given as an 8.3 short name is expanded to its long name (`CLAUDE~1` → `.claude`, `SETTIN~1.JSO` → `settings.json`, `BASELI~1.JSO` → `baseline.json`);
- the result is compared with the project root case-insensitively.

**Given** an `Edit`/`Write`/`MultiEdit`/`NotebookEdit` event **When** the canonical target is `.env`, `*.pem`/`*.ppk`/`*.der`/`*.jks`/`*.keystore`, a Flyway migration already listed as applied in the migration ledger, a path under `**/generated/**`, `eval/baseline.json`, `.claude/hooks/**`, `.claude/settings.json`, `.claude/settings.local.json`, or `docs/PROMPT.md` **Then** the hook exits 2 and stderr says why (the migration message contains "immutable"; the hooks and settings message mentions `hooks-unlock`).
**Given** `.claude/state.json` has `hooks_unlocked: true` (or `MASROOF_HOOKS_UNLOCK=1` is set) **When** `.claude/hooks/guard-bash.sh` is edited **Then** the edit is allowed.
**Given** the event carries `agent: product-analyst` **When** the target is `backend/src/main/java/X.java` **Then** exit 2 (outside write scope `docs/*`); **When** the target is `docs/backlog/EPIC-01/STORY-009.md` **Then** exit 0. Scope checks use the same canonical path.
Unhappy path (aliases, all exit 2 while hooks are locked): `C:/Personal/Masroof/CLAUDE~1/hooks/guard-bash.sh`, `C:/Personal/Masroof/.claude/SETTIN~1.JSO`, `C:/Personal/Masroof/eval/BASELI~1.JSO`, `C:/Personal/Masroof/.claude/settings.json::$DATA`, `C:/Personal/Masroof/.claude/settings.json:x:$DATA`, `\\.\C:\Personal\Masroof\.claude\hooks\guard-bash.sh`, `\\?\C:\Personal\Masroof\.claude\settings.json`, `//?/C:/Personal/Masroof/docs/PROMPT.md`, `c:\personal\masroof\.claude\hooks\guard-bash.sh`, `.claude//hooks/lib.sh`, `.claude/x/../hooks/lib.sh`, `.ENV`, `.Env`, `docs/prompt.md`, `keys/id.PPK`, `certs/a.der`.
Unhappy path: **Given** a new, not-yet-applied migration `V7__x.sql` **When** it is edited **Then** exit 0 (only applied migrations are immutable). **Given** malformed JSON **Then** exit 0 with one stderr line.

### REQ-OPS-003 — scan-secrets blocks secret-like content in writes
**Given** a `Write`/`Edit`/`MultiEdit`/`NotebookEdit` event whose content contains an Anthropic-style key (`sk-ant-` + 20+ chars), an AWS access key id, a JWT, a private-key PEM header, a Meta app secret assigned to `WHATSAPP_APP_SECRET=`, a card number (PAN) or an Egyptian mobile number **When** the hook runs **Then** it exits 2 and stderr names the detector, but never echoes the secret itself (no 12-character slice of it appears on stdout or stderr). Test values are generated at test time; none is written literally in this spec (the hook would block it).

**Normalisation.** NBSP (U+00A0), thin space (U+2009), narrow NBSP (U+202F) and tab become a space. Arabic-Indic (U+0660–U+0669) and full-width (U+FF10–U+FF19) digits become ASCII digits.

**PAN candidates (bounded).** After normalisation, a candidate is one of:
- (a) a run of 14–19 contiguous digits, not preceded or followed by another digit or letter, and not part of a decimal number (`<13+ digits>.123`);
- (b) four groups of 4 digits, optionally followed by a group of 3 (`dddd dddd dddd dddd[ ddd]`), with exactly one space, `-` or `.` between groups.

A candidate is reported only if it starts with `3`, `4`, `5` or `6`, passes Luhn, is not one digit repeated, and is not in the existing public test-PAN allowlist (`TEST_PANS` in `scan-secrets.sh`, unchanged). Nothing else is a PAN candidate. In particular, lists of amounts or dates are never joined into a candidate. Blocked: a Luhn-valid 16-digit PAN starting with `5` written contiguous, as `dddd dddd dddd dddd`, `dddd-dddd-dddd-dddd` or `dddd.dddd.dddd.dddd`, and the same with NBSP or tab separators, or in Arabic-Indic or full-width digits.

**Egyptian mobiles.** Numbers with the prefixes `010`, `011`, `012` and `015` are detected in the forms `+20 1x xxxx xxxx`, `01x-xxxx-xxxx`, `01xxxxxxxxx`, `(01x) xxxx-xxxx` (parenthesised prefix, 4-4 body), and in Arabic-Indic digits.

**Given** any of these contents **When** the hook runs **Then** exit 0 (no false positive):
- `ANTHROPIC_API_KEY=changeme` in `.env.example`;
- the token `sk-ant-...` inside a Markdown code fence documenting the format;
- 3000 lines of 13-digit epoch milliseconds, or an epoch-ms value with a `.123` fraction;
- a 32-digit digit-only UUID;
- 40 one-line records `| <yyyy-mm-dd> | spent <a> <b> <c> EGP |` with random two-decimal amounts (e.g. `12783.55`), and the same with space-separated or Arabic-Indic amounts.

Unhappy path: **Given** a 5 MB write **When** it is scanned **Then** the hook finishes in < 2 s and still detects a key at the end of the content.
Unhappy path: **Given** one 200 KB line of the word `secret` repeated, followed by the line `WHATSAPP_APP_SECRET=<32 hex>` **Then** the hook finishes in < 2 s and exits 2.
Unhappy path: **Given** 5 MB of space-separated single digits **Then** the hook finishes in < 2 s and exits 0.

### REQ-OPS-004 — stop-gate blocks stopping on a red check-fast and escalates after 3 blocks
**Given** a `Stop` event with a clean tree or a docs-only change **When** the hook runs **Then** `make check-fast` is not invoked and exit is 0.
**Given** changed files under `backend/` and `make check-fast MODULE="backend"` exits non-zero **When** the hook runs **Then** exit 2, stderr contains `block 1/3`, the module name, and the last 20 lines of the failure (e.g. `ExpenseTest`), and `.claude/state.json.stop_blocks == 1`.
**Given** two prior consecutive blocks **When** a third red run happens **Then** the hook emits a `systemMessage`, appends a `RED` line to `docs/PROGRESS.md` and a `GATE-` entry to `docs/ESCALATIONS.md`, resets `stop_blocks` to 0 and allows the stop.
**Given** a green run recorded as `last_green` fingerprint of the tree **When** Stop fires again with an unchanged tree **Then** make is not re-run; **When** a file changed **Then** make runs again. Changed files in `web/` and `.claude/` run `MODULE="sdlc web"` (sorted).

### REQ-OPS-005 — SubagentStop enforces the output contract
**Given** a `SubagentStop` event for a roster agent (e.g. `qa-verifier`) whose last message lacks `STATUS:` or has a STATUS value outside `.claude/hooks/output-contracts.json` **When** the hook runs **Then** exit 2 and stderr lists the allowed statuses (`PASS|FAIL|BLOCKED`) and the required sections (`CRITERIA:`), naming the missing one.
**Given** the message ends with the exact contract (sections present, `STATUS: PASS` last line) **Then** exit 0.
Unhappy path: **Given** an agent name not in the contracts file **When** the hook runs **Then** exit 0 with a warning (non-roster agents are not gated).

### REQ-OPS-006 — SessionStart injects context
**Given** a `SessionStart` event (source `startup|resume|compact`) **When** the hook runs **Then** stdout contains: current phase (`Phase: 2`), active story id and stage (`STORY-001`, `implement`), loop counter `1/3`, the last 20 lines of `docs/PROGRESS.md` (not line 5 of a 30-line file), `Open escalations (n)` counting only unresolved entries, the Invariants section of `CLAUDE.md` (stopping at the next heading), backlog counts by status (`READY 1`, `DONE 1`), and `SessionStart: <source>`; total output < 10,000 bytes.
Unhappy path: **Given** `docs/PROGRESS.md` is missing **When** the hook runs **Then** it prints `PROGRESS.md: none` and exits 0.

### REQ-OPS-007 — test-integrity warns on weakened tests
**Given** a `PostToolUse` Edit on a test file whose assertion count (`assert*`, `expect(`, `shouldBe`, `@Test` methods) did not decrease and no `@Disabled`/`.skip`/`@Ignore`/`xit(` was added **When** the hook runs **Then** stdout is empty.
**Given** assertions dropped from 3 to 1 or a skip marker was added by agent `backend-engineer` **Then** stdout contains `TEST INTEGRITY WARNING` with the old count, and `.claude/state/test-integrity.log` gains a line naming the agent, file and delta for `qa-verifier`.
Unhappy path: **Given** the edited file is not a test file **Then** no output and no log line.

### REQ-OPS-008 — budget-check warns when over budget
**Given** `.env` sets `SDLC_AI_BUDGET_USD=300` and the `AI spend (USD)` row of `docs/SDLC_METRICS.md` reads `350` **When** the `UserPromptSubmit` hook runs **Then** stdout contains `BUDGET EXCEEDED` and the spend `350`, and exit is 0 (warn, not block — S16 checkpoint 3).
**Given** spend `120` **Then** no output.
Unhappy path: **Given** `docs/SDLC_METRICS.md` is missing or the spend cell is non-numeric **Then** the hook exits 0 silently; **Given** the cell reads `1,234.50` or `$350` **Then** it is parsed as 1234.50 / 350 (separators and currency signs stripped) and compared correctly.

### REQ-OPS-009 — PreCompact snapshot preserves state
**Given** a `PreCompact` event with trigger `auto` **When** the hook runs **Then** `.claude/state.json` records `last_compact` (ISO timestamp containing `T`) and `last_compact_trigger: auto`, keeps existing keys (e.g. `story: STORY-001`), and exactly one `state-<timestamp>.json` snapshot is written under `.claude/state/snapshots/`.
Unhappy path: **Given** the snapshots directory is not writable **Then** stderr explains and exit is 0 (compaction is never blocked).

### REQ-OPS-010 — format-changed gives lint feedback
**Given** a `PostToolUse` Write/Edit on `*.java`, `*.kt`, `*.ts`/`*.tsx`, `*.sh`, `*.py`, `*.yaml`/`*.yml` or `*.json` **When** the hook runs **Then** it lints that one file in < 10 s and exits 0 (it never blocks). Tools: spotless (java), ktlint (kt), prettier and eslint (ts/tsx), shellcheck (sh), ruff (py), yamllint (yaml).
- Each finding is one line `file:line: message`, where `file` is the project-relative path (`scripts/bad.sh:2: …`, `scripts/bad.py:1: SyntaxError: …`, `.github/bad.yml:2: invalid YAML: …`, `web/bad.json:1: invalid JSON …`).
- Findings are delivered as `hookSpecificOutput.additionalContext` starting `Lint feedback`.
- A clean file, or a path that no longer exists, prints nothing.

**Given** the linter for a `.sh`, `.py`, `.kt`, `.yaml`/`.yml` or `.ts`/`.tsx` file is not installed **When** the hook runs **Then** stderr has exactly one line `format-changed: <tool> not installed, skipped` per absent tool (`shellcheck`, `ruff`, `ktlint`, `yamllint`, `prettier`, `eslint`), and exit is 0. A `.ts` file with neither prettier nor eslint gives exactly two lines. The built-in syntax checks still run and report as above: Python compile check when ruff is absent, YAML parse check when yamllint is absent, JSON parse check always.
**Given** a file name that contains shell, sed or awk metacharacters — `scripts/it's.sh`, `backend/x#;e touch PWNED;#.kt`, `scripts/$(touch PWNED).sh`, `web/a&b|c.ts` — and an installed linter that reports a finding for it **When** the hook runs **Then**:
- no file named `PWNED` is created anywhere (the hook's cwd, the project root, the temp dir);
- the finding line starts with the literal relative name followed by `:<line>: `, e.g. `backend/x#;e touch PWNED;#.kt:1: `.

The file name is never interpolated into a shell, sed or awk program.
Unhappy path: **Given** malformed JSON or a non-file tool event **Then** exit 0 and no stdout.

### REQ-OPS-011 — protected-file integrity backstop and local pre-push guard for main
Protected set, the same as REQ-OPS-001 B13: `.claude/hooks/**`, `.claude/settings.json`, `.claude/settings.local.json`, `docs/PROMPT.md`, `eval/baseline.json`, `.env`, `.env.*`. Paths are compared by their canonical long name (REQ-OPS-002), so the spelling used to write a file does not matter.

**Integrity backstop (PostToolUse, `Bash|PowerShell`).**
**Given** hooks are locked (neither `hooks_unlocked: true` in `.claude/state.json` nor `MASROOF_HOOKS_UNLOCK=1`) **When** any `Bash` or `PowerShell` tool call ends and a protected path changed or appeared during that call **Then**:
- every tracked protected file whose content changed is restored to its `HEAD` content;
- every protected file that did not exist before the call and is untracked is removed, except `.env` created with content identical to `.env.example` (`cp .env.example .env` stays);
- the hook prints a PostToolUse blocking decision (`"decision": "block"`) whose `reason` names each restored or removed path and says that hook edits need `make hooks-unlock`;
- after the hook, `git status --porcelain -- .claude/hooks .claude/settings.json docs/PROMPT.md eval/baseline.json` shows no change from the call.

**Given** no protected path changed during the call **Then** the hook prints nothing and exits 0. A call that changes only other files (e.g. `backend/`, `docs/backlog/`) is never touched.
**Given** hooks are unlocked (`make hooks-unlock` or `MASROOF_HOOKS_UNLOCK=1`) **When** a protected file changes **Then** nothing is restored or removed and no decision is printed.

Unhappy paths: in a temp repo with hooks locked, each of these writes (run directly, as if guard-bash had missed it) is undone by the hook, and the decision names the canonical path:
- `echo x > CLAUDE~1/hooks/guard-bash.sh` (8.3 short name);
- a write to `.claude/settings.json::$DATA`;
- a write to `\\?\C:\…\.claude\hooks\lib.sh`;
- `python -c "open('.claude/hooks/guard-bash.sh','w').write('x')"`;
- `[IO.File]::WriteAllText('.claude/settings.json','{}')`;
- `git checkout HEAD~1 -- .claude/hooks/guard-bash.sh`;
- a new file `.claude/hooks/evil.sh`.

More unhappy paths:
- **Given** an existing untracked protected file (`.env`, `.claude/settings.local.json`) that changed during the call **Then** it is not restored (there is no `HEAD` copy, and `.env` content is never copied elsewhere), but the decision names it as `changed (not restorable)`.
- **Given** a protected tracked file that was already modified before the call (left from an unlocked session) and that the call did not change **Then** it is left as is.
- **Given** malformed JSON, or the project is not a git work tree **Then** exit 0 with exactly one stderr line.
- **Given** any call **Then** the hook finishes in < 2 s on this repo.

**Local pre-push guard (lefthook `pre-push`).**
**Given** `git push` sends a ref update for `refs/heads/main` or `refs/heads/master` **When** lefthook's `pre-push` runs **Then**:
- a deletion (local sha all zeros, e.g. `git push origin :main` or `git push --delete origin main`) is rejected: non-zero exit and a message naming the ref;
- a non-fast-forward update (the remote sha is not an ancestor of the local sha, e.g. after `git reset --hard HEAD~1` then `git push -f origin main`) is rejected the same way;
- a fast-forward update, or the creation of main on an empty remote, is allowed (exit 0).

**Given** a force push of a `story/STORY-xxx` branch **Then** exit 0.
Unhappy path: **Given** the remote sha of main is not present locally (not fetched) **Then** the push is rejected with a message telling the user to `git fetch` first (fail closed).
`--no-verify` and `LEFTHOOK=0` are blocked by REQ-OPS-001 B4. The remote guard is GitHub branch protection (STORY-016).

## UX states (UI stories only)
N/A (no UI).

## Contract impact
None — SDLC tooling only.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
These hooks are the mechanical guard for the Invariants in `CLAUDE.md`: secrets only in `.env`, applied migrations immutable, generated code untouched, tests never weakened.
- REQ-OPS-003 must never print the detected secret.
- Hook edits require `make hooks-unlock` (logged).
- Defence in depth: REQ-OPS-001/002 block an enumerated set before execution. REQ-OPS-011 deterministically undoes any protected-file change that slips through, and rejects history rewrites of `main` locally. CI branch protection guards the remote (STORY-016).
- Accepted residual risk (REQ-OPS-001 Non-goals): a run-time-assembled or obfuscated command can still do damage outside the protected set and outside `main`. This is mitigated by worktree isolation and backups, not by this story.

## Eval impact
None directly; the eval gate is proven by `/sdlc-selftest` (S15 item 6, recorded in `docs/SDLC_SELFTEST.md`), not by this story.

## Design notes (architect)
Filled at stage 3. Open for the architect:
- where REQ-OPS-011 records the pre-call state (it must detect "changed during the call" and leave changes that existed before the call alone);
- the `pre-push` script path and the `use_stdin` wiring in `lefthook.yml`;
- the `PostToolUse` matcher `Bash|PowerShell` in `.claude/settings.json`, which needs `make hooks-unlock`.

## Dependencies
None (first story of Phase 0).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines. The hooks exist. Loop 3 is the deltas to guard-bash, protect-files, scan-secrets and format-changed, plus one new PostToolUse hook, one pre-push script and their tests, estimated ≈ 350–400 lines. Split REQ-OPS-011 into its own story if the design estimate exceeds 400.
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY — REQ ids match `.claude/hooks/tests/test-*.sh` headers |
| 2026-10-24 | 1-2 spec fix | product-analyst | READY — after verify loop 2 FAIL (F-01..F-10, F-15, F-16). REQ-OPS-001 is rewritten as a closed set of classes B1–B14 with an allow-list and explicit non-goals. REQ-OPS-002 gains canonicalisation (8.3, `::$DATA`, `\\?\`/`\\.\`) plus `*.ppk`, `*.der` and `settings.local.json`. REQ-OPS-003 gains bounded PAN candidates, normalisation and timing cases. REQ-OPS-010 gains one skip line per tool and the file-name injection case. New REQ-OPS-011: integrity backstop and pre-push guard. Nothing already proven was removed. |

| 2026-10-24 | 6 verify | qa-verifier | FAIL loop 3/3 (1 BLOCKER: backstop sources the files it protects; 3 MAJOR) -> BLOCKED, ESCALATION LOOP-002 |
| 2026-10-24 | 5 implement | orchestrator | owner granted loop 4 (LOOP-002 option a): scope = loop-3 findings F-01..F-04 only; hook self-integrity vs git HEAD, fail closed |
