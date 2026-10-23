#!/usr/bin/env bash
# One-line wrapper so the Makefile / CI can call the coverage gate as a shell step. Exit codes pass through.
# $PYTHON may be "py -3" (two words), so it is intentionally unquoted.
# shellcheck source=../env.sh
# shellcheck disable=SC2086
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/env.sh" && exec $PYTHON "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/coverage-gate.py" "$@"
