#!/usr/bin/env bash
# REQ: REQ-OPS-142
# STORY-144 mechanical acceptance checks for the Spring Boot 4.1.2 upgrade. Run: bash scripts/quality/tests/backend-boot41-checks.sh
# Prints one PASS/FAIL line per check; exit 1 when any check fails. Named without a `test-` prefix so run.sh skips it
# (like backend-gate-probes.sh); the verifier runs it directly.
set -uo pipefail
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_DIR" || exit 1
export LC_ALL=C

B=backend
BUILD=$B/build.gradle.kts
LOCK=$B/gradle.lockfile
BLOCK=$B/buildscript-gradle.lockfile
META=$B/gradle/verification-metadata.xml
fails=0

check() { # check <name> <command...>; the command succeeds when the requirement holds
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "PASS: $name"; else echo "FAIL: $name"; fails=$((fails + 1)); fi
}
absent() { ! grep -Eq "$1" "${@:2}" 2>/dev/null; }

check "REQ-OPS-142 plugin org.springframework.boot version 4.1.2" \
  grep -Eq 'id\("org\.springframework\.boot"\) version "4\.1\.2"' "$BUILD"
check "REQ-OPS-142 override 1 jackson-bom 2.21.8 removed" absent 'jackson-bom:2\.21\.8' "$BUILD"
check "REQ-OPS-142 override 2 postgresql 42.7.14 constraint removed" absent 'org\.postgresql:postgresql:42\.7\.14' "$BUILD"
check "REQ-OPS-142 override 3 log4j-api 2.25.5 constraint removed" absent 'log4j-api:2\.25\.5' "$BUILD"
check "REQ-OPS-142 override 4 commons-compress 1.28.0 constraint removed" absent 'commons-compress:1\.28\.0' "$BUILD"
check "REQ-OPS-142 override 5 junit-bom 5.14.4 removed" absent 'junit-bom:5\.14\.4' "$BUILD"
check "REQ-OPS-142 override 6 httpclient5/httpcore5/httpcore5-h2 buildscript constraints removed" \
  absent 'httpclient5:5\.6\.5|httpcore5:5\.4\.4|httpcore5-h2:5\.4\.4' "$BUILD"
check "REQ-OPS-142 no net.jqwik in build.gradle.kts or lockfiles" absent 'net\.jqwik' "$BUILD" "$LOCK" "$BLOCK"
check "REQ-OPS-142 gradle.lockfile has spring-boot:4.1.2" grep -Eq 'spring-boot:4\.1\.2' "$LOCK"
check "REQ-OPS-142 no 3.5.16 in lockfiles" absent '3\.5\.16' "$LOCK" "$BLOCK"
check "REQ-OPS-142 log4j-api and log4j-to-slf4j same version" bash -c '
  a=$(grep -E "^org\.apache\.logging\.log4j:log4j-api:" "$1" | grep "=.*runtimeClasspath" | head -1 | cut -d: -f3 | cut -d= -f1)
  b=$(grep -E "^org\.apache\.logging\.log4j:log4j-to-slf4j:" "$1" | grep "=.*runtimeClasspath" | head -1 | cut -d: -f3 | cut -d= -f1)
  [ -n "$a" ] && [ "$a" = "$b" ]' _ "$LOCK"
check "REQ-OPS-142 no web starters declared (webmvc, webmvc-test, jdbc-test)" \
  absent 'spring-boot-starter-webmvc|spring-boot-starter-jdbc-test' "$BUILD"
check "REQ-OPS-142 no tomcat-embed-core in any runtimeClasspath entry" \
  absent '^org\.apache\.tomcat\.embed:tomcat-embed-core:.*[Rr]untimeClasspath' "$LOCK"
check "REQ-OPS-142 verification-metadata: no org.junit* 5.14.4" bash -c \
  '! grep -E "<component group=\"org\.junit[^\"]*\"[^>]*version=\"5\.14\.4\"" "$1"' _ "$META"
check "REQ-OPS-142 verification-metadata: no org.testcontainers 1.21.4" bash -c \
  '! grep -E "<component group=\"org\.testcontainers\"[^>]*version=\"1\.21\.4\"" "$1"' _ "$META"
check "REQ-OPS-142 verification-metadata: no com.tngtech.archunit 1.4.1" bash -c \
  '! grep -E "<component group=\"com\.tngtech\.archunit\"[^>]*version=\"1\.4\.1\"" "$1"' _ "$META"
check "REQ-OPS-142 verification-metadata: no net.jqwik component" bash -c \
  '! grep -E "<component group=\"net\.jqwik\"" "$1"' _ "$META"
check "REQ-OPS-142 verification-metadata: no version=\"3.5.16\"" absent 'version="3\.5\.16"' "$META"
check "REQ-OPS-142 backend/CLAUDE.md has no jqwik" absent 'jqwik' "$B/CLAUDE.md"

# ArchUnit totals (only when the archTest results exist: the gate run produces them).
if ls $B/build/test-results/archTest/*.xml >/dev/null 2>&1; then
  totals="$(cat $B/build/test-results/archTest/*.xml | grep -o '<testsuite [^>]*>' | sed -E \
    's/.*tests="([0-9]+)".*skipped="([0-9]+)".*failures="([0-9]+)".*errors="([0-9]+)".*/\1 \2 \3 \4/' |
    awk '{t+=$1;s+=$2;f+=$3;e+=$4} END {print t" "s" "f" "e}')"
  check "REQ-OPS-142 archTest totals tests=9 skipped=0 failures=0 errors=0 (got: $totals)" [ "$totals" = "9 0 0 0" ]
else
  echo "SKIP: archTest results absent (run ./gradlew archTest first)"
fi

if [ "$fails" -gt 0 ]; then echo "$fails check(s) FAILED"; exit 1; fi
echo "all checks passed"
