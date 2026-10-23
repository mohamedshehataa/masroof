# Lessons

Every escaped defect, failed gate pattern or SDLC friction becomes one entry: **what happened → root cause (spec gap / test gap / review miss / environment) → prevention (test, rule, hook or checklist item)**.

- 2026-10-23 — Tooling installed mid-session (winget) is not on the PATH of already-running processes → environment → `scripts/env.sh` is sourced by every hook and the Makefile so PATH never depends on how the shell was started.
- 2026-10-23 — A single very large `bash -c` script with a dozen heredocs failed to parse and wrote nothing → environment → write files one per tool call; keep shell batches small so a failure is local and visible.
