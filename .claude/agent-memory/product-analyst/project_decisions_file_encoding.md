---
name: decisions-file-not-utf8
description: docs/DECISIONS.md is not valid UTF-8 (2026-10-26), so Edit/Write refuse it; hand proposed DECISIONS lines to the orchestrator instead
metadata:
  type: project
---

As of 2026-10-26, Read reports `docs/DECISIONS.md` as "not valid UTF-8 (legacy encoding)", and Edit/Write refuse to change it. The product-analyst has no shell, so it cannot append lines.

**Why:** some earlier writer saved it in a legacy encoding. Editing it with the wrong encoding would corrupt existing lines.

**How to apply:** put proposed DECISIONS lines in the final report (and in the epic/backlog README when they belong there), and ask the orchestrator to append them with a shell command that keeps the encoding, or to convert the file to UTF-8. Re-check with Read before assuming this is still true.

Also noted: `.claude/skills/money-and-dates/SKILL.md` §5 says the ambiguous fall-back hour in Cairo is 00:30. tzdata (`Oct lastThu 24:00`) makes 23:00–23:59 on Thursday repeat. EPIC-16 specs defer to the JDK zone rules. See [[project-delivery-gates]].
