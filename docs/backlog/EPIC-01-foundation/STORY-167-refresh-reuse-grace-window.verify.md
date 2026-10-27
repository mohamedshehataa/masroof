# Verification report — STORY-167

- **Verifier:** qa-verifier (fresh context)  ·  **Date:** 2026-10-27  ·  **Base:** `bebc78f8fcf51c562836d62949df5cd248bb5e25` (origin/main = merge-base)  ·  **Head:** `56d61516a9dc150cbd8d26d8fdbc90a637742cd2` (PR #71)
- **Loop:** 1 of 3

## Environment
- Ran in my own isolated worktree (agent-a5734d574d13a59e6) checked out detached at 56d6151 (rev-parse HEAD -> 56d61516...). JAVA_HOME = Temurin 21.0.12.
- `bash scripts/make/check-fast.sh backend` -> `OK backend: ./gradlew checkFast`, `check-fast GREEN [backend] in 36s`, exit 0. AuthServiceTest: 12 tests, 0 skipped, 0 failed.
- `bash scripts/make/backend-integration.sh` (Testcontainers) -> integrationTest executed 122 tests, archTest executed 16 tests, exit 0. AuthApiIT: 28 tests, 0 skipped, 0 failures, 0 errors.
- Live probes: the PR jar (gradlew bootJar) run on 127.0.0.1:18167 against a throwaway postgres:16.15-alpine3.24 container (Flyway applied V1..V11 OK), driven by a Python script (urllib + docker exec psql). Both removed afterwards.

## Criteria
| REQ / claim | Result | Evidence (command -> observed) |
|---|---|---|
| REQ-SEC-063 (1) duplicate inside 60 s -> 200, new pair, nothing revoked | PASS | IT graceDuplicate green. Live E1: login r1, refresh r1 -> r2, replay r1 -> 200, accessToken present, new token distinct from r1/r2; family 3 live of 3; audit REFRESH_GRACE=1, no REFRESH_REUSE |
| REQ-SEC-063 (2) first successor still works | PASS | IT graceDuplicate refreshes r2 and r3 with ok(). Live E1: refresh r2 -> 200, refresh r3 -> 200, family 5 live of 5 |
| REQ-SEC-063 (3) grace never changes rotated_at | PASS | IT asserts rotatedAt(r1) unchanged. Live E1: rotated_at 11:38:25.352261+00 before and after, grace 0 -> 1. Code: the grace branch only runs set grace_count = grace_count + 1 |
| REQ-SEC-063 (4) replay at 60 s and 61 s -> 401, family revoked, REFRESH_REUSE | PASS | IT graceBoundary (59 s grace; 60 s and 61 s -> 401, liveInFamily 0, REUSE +1, r2 -> 401) green; IT rotationAndReuse at 61 s green. Live E9 (rotated_at shifted from its stored app-clock value): 58.5 s -> 200; 60 s, 60.001 s, 61 s and 3600 s -> 401, family 0 live of 2. E14 at 61 s after 2 graces -> 401, all 4 tokens dead, REUSE +1. The comparison is now.isBefore(rotatedAt + 60 s), so exactly 60 s is reuse |
| REQ-SEC-063 (5) revoked (logout or earlier reuse) never gets grace | PASS | IT graceNotAfterLogout green. Live E7: logout 204, replay r1 in window -> 401 Invalid refresh token, GRACE count unchanged; at +120 s -> 401. Live E3: 7th replay after the cap-triggered revoke, still in window -> 401, no new GRACE row |
| REQ-SEC-064 (6) 6th grace re-issue is reuse | PASS | IT graceCap green. Live E3: 5 replays [200,200,200,200,200], grace=5, family 7 live of 7; 6th -> 401, family 0 live of 7, REUSE 0 -> 1 |
| REQ-SEC-063 (7) two parallel refreshes both 200 | PASS | IT graceParallel green (liveInFamily 3). Live E8: 2 parallel -> [200,200] grace=1; 6 parallel -> six 200s, grace=5, 7 live. The row lock (for update) serialises correctly, with no over-issue |
| (8) REFRESH_GRACE row holds no token data; nothing token-related logged | PASS | audit_log columns are id, household_id, member_id, action (CHECK upper-case/underscore, max 40), created_at, ip_hash; the insert writes only ids + action. Case-insensitive grep of the live app log for refresh, token, JWT (eyJ) or 64-hex -> 2 hits, both Flyway migration names. Grep of the IT output for JWT, 64-hex or refreshToken -> 0 hits. No new LOG calls in the diff |
| (9) no Instant.now() in new code | PASS | Grep of git diff origin/main...HEAD for Instant.now, currentTimeMillis, LocalDateTime.now -> 0 hits. now flows from clock.instant() (unit test refreshPassesGrace asserts the injected clock value reaches the store) |
| (10) V11 additive; V1-V10 untouched | PASS | git diff --name-status origin/main...HEAD on db/migration -> only A V11__refresh_token_grace.sql (ADD COLUMN grace_count integer NOT NULL DEFAULT 0). Live Flyway history: V1..V11 all success |
| (11) existing reuse tests not weakened | PASS | scripts/quality/test-integrity-check.sh origin/main -> TEST_INTEGRITY: OK, assertions 251 -> 272, skips 0 -> 0. The rotationAndReuse change adds one line, ageRotation(r1, 61), before the replay. All original assertions stay (401, problem+json, r2 dead, 0 live, REUSE >= 1). An immediate replay is now meant to be a grace case and is covered by graceDuplicate. AuthServiceTest and InviteServiceTest only gain the new rotate parameters |

## Findings
| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| MINOR | JdbcAuthStore.rotate check order (revoked, then expired, then rotated) | A rotated token that is past expires_at now returns REFRESH_EXPIRED (title Refresh token expired), with no family revocation and no REFRESH_REUSE. Before the PR, rotated was checked first, so the result was Reuse: title Invalid refresh token, family revoked and audited. STORY-167 says an expired token is Expired first, but STORY-009 REQ-SEC-005 (an already-rotated token revokes every token in the family) was not amended, and DECISIONS and THREAT_MODEL do not mention this. It narrows reuse detection, and the client-visible title changes for this case. | Live E6: login r1, refresh r1, set expires_at = now() - 1 second on r1 in SQL, refresh r1 -> 401 Refresh token expired, family 2 live of 2, REUSE count unchanged (also after aging it 2 days) |
| MINOR | JdbcAuthStore.rotate, revoked-first branch | Replaying a rotated token whose family is already revoked (after logout or an earlier reuse) no longer writes a REFRESH_REUSE row; it is a silent Invalid. Before the PR, every such replay was audited. This is a lost forensic signal and is not documented. | Live E7: logout, then replay rotated r1 inside the window and at +120 s -> 401 both times, audit counts unchanged |
| MINOR | AuthApiIT.ageRotation (sets rotated_at = now() - make_interval) | rotate stores the JVM clock, but the test helper rewrites rotated_at from the Postgres container clock. The +60 s assertion therefore has about 0 ms margin if the DB clock runs ahead of the JVM, and the +59 s case has 1 s margin the other way. This makes the test flaky under Docker Desktop/WSL2 clock skew. A skew-free form is rotated_at = rotated_at - make_interval (the live probes used this). | Shift the Testcontainers VM clock by more than ~10 ms ahead of the host, then run graceBoundary: the 60 s case is graced and the 401 assertion fails |
| NIT | tests | No deterministic fixed-clock test of the exact 60.000 s instant (the store has no unit test). The boundary rests on isBefore plus IT timing. | n/a |
| NIT | docs (THREAT_MODEL max 5 grace, API.md) | The cap is per rotated token, not per family. A grace-issued token, once rotated, has its own budget of 5 (live E5: 5 more 200s, family 9 live). This matches the story wording, but max 5 in the docs reads as per family. Growth stays linear in request count. | Live E5 |
| NIT | behaviour, per spec | A burst of 7 or more identical refreshes still signs the user out. The 6 tokens returned with 200 die right away (live E8: 7 parallel gave six 200s and one 401, family 0 live of 7; 10 parallel gave six 200s and four 401s). This is per REQ-SEC-064 and noted only for client awareness. | Live E8 |

Trade-off check (stolen token), live E12: the attacker rotates r1 first and the victim retries r1 within 60 s. Both chains stay alive through 3 more rotations each (family 7 live of 7), with one REFRESH_GRACE row and no REFRESH_REUSE. A victim logout with its own token kills the attacker chain (attacker refresh -> 401). This is the trade-off documented in DECISIONS.md 2026-10-27 (a stolen token used within 60 s of a rotation gets a session, at most 5, and later reuse still revokes). The attacker gains nothing beyond that.

Client-visible error codes (live E10): unknown, reuse, oversized and empty tokens -> 401 type .../problems/unauthorized, title Invalid refresh token; expired (never rotated) -> 401 Refresh token expired. These are unchanged from main, except for the rotated-and-expired case in finding 1.

## Exploratory
16 cases tried, 2 problems (MINOR findings 1 and 2). Inputs, against the live jar plus docker exec psql:
- E1 replay r1 right after rotation; refresh r2 and r3 afterwards
- E3 5 sequential grace replays, a 6th, then a 7th after the revoke
- E5 grace-issued token rotated, then replayed 5 times (per-token cap)
- E6 rotated token with expires_at = now() - 1 s, inside the window and again aged 2 days
- E7 logout with the rotated r1, then replay at +0 s and +120 s
- E8 2, 6, 7 and 10 parallel refreshes of the same unrotated token
- E9 rotated_at aged 58.5, 59.9, 60, 60.001, 61 and 3600 s. The 59.9 s case -> 401 because about 0.4 s of probe latency (docker exec) pushes the real elapsed time past 60 s. This is a probe artifact, not a defect.
- E10 error bodies: unknown token, reuse, 200-char token, empty string, expired never-rotated token
- E12 stolen-token interleave (attacker first, victim retry in window), then victim logout
- E13 replay r1 at +30 s after its successor r2 already rotated -> 200 (the window is anchored to the rotated_at of r1)
- E14 two graces, then replay at +61 s -> 401; grace token g1 also revoked
- E15 odd token strings: an SQL-quote string (quote + or 1=1 --), Arabic-Indic digits U+0661..U+0663, null, 10 spaces, U+202E RTL-override + abc -> all 401, no 500

## Test integrity
OK — bash scripts/quality/test-integrity-check.sh origin/main:
AuthApiIT asserts 110 -> 128, V2CoreSchemaMigrationTest 33 -> 33, AuthServiceTest 53 -> 56, InviteServiceTest 55 -> 55; skips 0 -> 0 everywhere; total 251 -> 272; TEST_INTEGRITY: OK.

STATUS: PASS
