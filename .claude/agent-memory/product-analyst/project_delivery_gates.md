---
name: project-delivery-gates
description: Owner addendum docs/PROMPT_DELIVERY_GATES.md (STORY-145) binds every later spec - M1-M18 gates, size exception, sanctioned test changes, OWNER-011/012 blockers
metadata:
  type: project
---

docs/PROMPT_DELIVERY_GATES.md is the owner's binding addendum (wins over PROMPT.md). It is specified as STORY-145 (REQ-OPS-150..165, size L under the M12 size exception, 10 internal increments), written 2026-10-24.

**Why:** the owner wants every push/PR/approve/merge/release passed by numeric script checks. GitHub forbids the author (owner account mohamedshehataa) approving their own PR, so the final merge is blocked on OWNER-011 (approval identity) and OWNER-012 (ANTHROPIC_API_KEY secret).

**How to apply when specifying later stories:**
- Over 400 lines is allowed only through the M12 exception (label size-exception + a DECISIONS "size exception" line). Otherwise split.
- Any story that changes an existing test assertion (a flip or a deletion) needs a "## Sanctioned test changes" table row. Otherwise M5 fails.
- An optional "## Rollback plan" section feeds the generated PR body.
- Review reports gain a "## Verdicts" table, and M10/M11 apply the HEAD rule (only bookkeeping paths may change after a report).
- No story may plan a direct `git push origin main`.
- Branch protection stays with STORY-016.

Related: [[feedback-ticketed-followups]]
