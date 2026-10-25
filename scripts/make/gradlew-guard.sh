#!/usr/bin/env bash
# Sourced by check-fast.sh / build.sh. A Gradle module is "present" when <module>/gradlew exists.
# A present-but-not-executable wrapper (git mode 100644) used to make `[ -x ... ]` false on Linux CI, so the module
# was silently skipped and the job went green without building anything. Never skip: fail with a clear message.
#
#   gradlew_state <module>   prints: absent | ok   (exits the caller's function with 1 via `return 1` when broken)
#   Returns 0 = present and executable, 2 = absent (caller may skip), 1 = present but NOT executable (caller must fail).
gradlew_state() {
  local mod="${1:?module}"
  if [ ! -e "$mod/gradlew" ]; then return 2; fi
  if [ ! -x "$mod/gradlew" ]; then
    echo "ERROR: $mod/gradlew exists but is not executable (git mode 100644). Refusing to skip the $mod module." >&2
    echo "       Fix: git update-index --chmod=+x $mod/gradlew && chmod +x $mod/gradlew" >&2
    return 1
  fi
  return 0
}
