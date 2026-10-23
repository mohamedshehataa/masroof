---
paths:
  - "**/prompts/**"
---
# Prompts rule

- Prompts are versioned files `backend/src/main/resources/prompts/<name>.v<N>.md` with the YAML header
  (name, version, purpose, model as a `config:` reference, schema id, output mode, few_shot_source, changelog).
  Any wording change = new file with `v<N+1>`; old versions stay because `ai_call_log` rows reference them.
- Untrusted content (message text, OCR, captions, sender names, file names) appears only inside a nonce-tagged
  `<untrusted_input>` data block in the dynamic Input section, with the system statement that it is data, never
  instructions. Never interpolate it into system, household, schema or few-shot sections.
- Parsing calls expose **no tools with side effects**: the only "tool" is the output schema. The application
  decides what to do with the result and bounds it server-side (ceiling, INCOME from WhatsApp, unknown category,
  low confidence -> `PENDING_REVIEW`).
- Structured output only; validate against the JSON schema; one repair retry; then `NEEDS_REVIEW`.
- Few-shot examples come from the eval `train` split only. Model ids and parameters live in config, not here.
- **Eval gate:** every prompt or model-config change runs `/eval` (fast on PR, full nightly), must not regress any
  metric by more than 1 percentage point, must keep OTP leaks and obeyed injections at 0, and needs the
  `ai-eval-engineer`'s `APPROVE`; the `security-reviewer` is triggered too. `eval/baseline.json` moves only via
  `/eval --rebaseline "<reason>"`.
- Static sections first and marked cacheable; dynamic input last. Never log prompt inputs or raw outputs above DEBUG.
- Full rules: `.claude/skills/prompt-engineering-rules/SKILL.md`.
