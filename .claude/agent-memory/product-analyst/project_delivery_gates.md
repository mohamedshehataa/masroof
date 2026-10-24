---
name: project-delivery-gates
description: docs/PROMPT_DELIVERY_GATES.md is mostly DEFERRED (owner decision 2026-10-24); only CI on every PR + branch protection apply; specs follow the risk-tiered process in CLAUDE.md
metadata:
  type: project
---

Owner decision 2026-10-24 (lighter, risk-tiered process aimed at a working vertical slice): the delivery-gates addendum is mostly deferred. In force now: CI on every PR (`pr.yml`, aggregate job `gate`; STORY-145 REQ-OPS-150). Branch protection (REQ-OPS-151) is not in force: STORY-147 DESCOPED 2026-10-25. Deferred until there is a real product to protect: per-metric `gate/Mn` checks, delivery-gates.yaml / delivery-gate.py, generated PR body + pr-body-lint, revert-on-red, release/hotfix/dependabot profiles, pre-push M1/M5, the "Sanctioned test changes" table, the strict HEAD rule. REQ-OPS-152..167 are retired; the old full spec/design is at commit 56c4a4f.

**Why:** the SDLC had grown far beyond the product (STORY-145 alone was estimated at ~7,600 lines while the product was a skeleton); the owner wants the vertical slice first.

**How to apply when specifying stories:**
- Set frontmatter `risk_tier: high` for parsing, dedup, money, auth, ingestion (incl. webhooks, LLM prompts); `low` for everything else. Low-tier stories get one spec pass (no spec-review loop).
- Keep the ≤ ~400 hand-written-line cap; split rather than invoking size exceptions.
- Merges to main go through a PR; since 2026-10-25 the orchestrator squash-merges after a green `PR gate` + verify PASS (OWNER-011 resolved). main is unprotected (STORY-147 DESCOPED, OWNER-014 c). No story may plan a direct `git push origin main`.
- The product-analyst has no shell tool: it cannot run `git show` or `sdlc-lint.py`; emulate lint rules by grep and tell the orchestrator to run it.
- Do not add new SDLC machinery to a story without an explicit owner decision.
