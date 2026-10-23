---
name: eval
description: Runs the AI evaluation harness (python eval/runners/run.py) for one suite or all, compares against eval/baseline.json using eval/thresholds.yaml, and, only with ai-eval-engineer approval and a written reason, rebaselines through the state flag. Use when the owner says /eval, after any prompt, model-config, parser or dataset change, and from /story for parsing stories.
argument-hint: "[suite] [--fast] [--adapter reference|http|mock] [--rebaseline \"<reason>\"]"
disable-model-invocation: true
user-invocable: true
---

# /eval $ARGUMENTS

Suites: `sms`, `notifications`, `whatsapp_text`, `commands`, `receipts`, `voice`, `dedup`, `adversarial`,
`narrative`. Gates live in `eval/thresholds.yaml` (S10.2): regression > 1 pp on any metric fails; OTP leak and
obeyed injected instruction are 0-tolerance; cost p95 +25% needs an ADR.

## 1. Run
```bash
source scripts/env.sh
ARGS=""; [ -n "<suite>" ] && ARGS="--suite <suite>"; [ "<--fast given>" ] && ARGS="$ARGS --fast"
ADAPTER=<reference|http|mock>   # default: http when ANTHROPIC_API_KEY is in .env, else reference (deterministic parser); say which one you used
python eval/runners/run.py $ARGS --adapter $ADAPTER
```
(`make eval` / `make eval-fast` are the same commands; use them when no flags are needed.) The runner writes
`eval/reports/<timestamp>.md` and exits non-zero on a gate failure. Capture `REPORT=$(ls -t eval/reports/*.md | head -1)`.

## 2. Read the report
Extract: per-metric table vs baseline, confusion matrix summary, the 20 worst failures, cost/latency. Classify
each failed gate as: regression (metric dropped), dataset change (new cases), threshold change, environment
(adapter/key). Record the verdict line: `eval <suite|all> <adapter>: <n> metrics, <k> below gate, worst <metric> <delta>`.

## 3. No rebaseline requested
- Green -> PROGRESS `<ts> · P<phase> · <STORY or SDLC> · <stage> · GREEN · <verdict>`; commit
  `test(eval): report <timestamp> [STORY-NNN|SDLC]` (reports are committed; they are evidence).
- Red -> PROGRESS `... · RED · <verdict>`. Inside /story this is a verify FAIL for the implementer (findings =
  the worst-failures list). Outside a story, create a READY story `fix(eval): <metric> regression` or, if the
  adversarial/OTP hard gate failed, treat it as a BLOCKER security finding (`/security-review`).

## 4. Rebaseline path (`--rebaseline "<reason>"`)
Only when the current run is green against the thresholds OR the drop is intended and explained (dataset grew,
threshold raised by ADR). Procedure, in this order, no shortcuts:
1. Agent `ai-eval-engineer` (prompt from [../story/prompts/specialist-reviewers.md](../story/prompts/specialist-reviewers.md),
   ai-eval section) with: the report path, the reason text, `git diff main -- eval/ backend/src/main/resources/prompts/`,
   and the question "Approve rebaselining eval/baseline.json to this report? List every metric that moves and why
   each move is acceptable." Expect `METRICS`, `REGRESSIONS`, `FINDINGS`, `STATUS: APPROVE | REJECT | BLOCKED`.
   `REJECT` -> stop; record the reason in PROGRESS (RED) and do not touch the baseline.
2. On `APPROVE`:
   ```bash
   bash scripts/sdlc/state.sh rebaseline-on
   MASROOF_REBASELINE=1 python eval/runners/run.py $ARGS --adapter $ADAPTER --rebaseline "<reason>"
   bash scripts/sdlc/state.sh rebaseline-off          # ALWAYS, also on failure (use a trap or run it next regardless)
   bash scripts/sdlc/state.sh get rebaseline_allowed  # must print false
   ```
   The protect-files hook blocks `eval/baseline.json` writes unless `rebaseline_allowed` is true or the writer is
   `ai-eval-engineer`; never set the flag for anything else and never leave it on.
3. Append one line to `docs/DECISIONS.md`: `<date> — eval baseline moved to <timestamp> (<suites>) — <reason>; approved by ai-eval-engineer (<metrics moved>)`.
4. Commit `test(eval): rebaseline <suites> [STORY-NNN|SDLC]` with the baseline, the report and DECISIONS.md.

## Exit gate
Report path and verdict quoted; state `rebaseline_allowed` is false; baseline changed only with an
ai-eval-engineer `APPROVE` quoted and a DECISIONS line. A red gate is reported as RED, never as "minor drift".
