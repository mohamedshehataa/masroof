---
paths:
  - "backend/src/main/**"
  - "android/**/src/main/**"
  - "web/src/**"
  - "infra/**"
---
# Logging and privacy rule

- Never log, above DEBUG or in any CI output, the following: SMS bodies, WhatsApp message text, captions,
  OCR text, receipt images or their URLs with tokens, voice transcripts, LLM prompt inputs or raw outputs,
  account or card numbers (only `last4`), balances, phone numbers, WhatsApp ids, device tokens, API keys,
  webhook secrets, JWTs. Semgrep custom rules fail the build on `log.*(body|text|ocr|rawOutput|phone|token)`.
- Log identifiers instead: `raw_message_id`, `expense_id`, `household_id`, `member_id`, trace id, prompt
  name+version, token counts, cost, latency, status enums, error codes.
- Backend: structured JSON logs through the redaction layer (`RedactingEncoder` / MDC filter) that masks any
  value matching card-number, phone (`+20...`/`01[0125]...`), IBAN or key patterns even at DEBUG; `toString()` of
  entities with sensitive fields is redacted by design (no Lombok `@ToString` on them).
- Android: `Timber`/`Log` only via the app logger that strips in release builds (`BuildConfig.DEBUG` gate); never
  log SMS content or notification text, including in crash breadcrumbs; error tracking has PII scrubbing on.
- Web: no `console.log` in production code (ESLint `no-console`); error tracking scrubs request bodies.
- Error messages returned to clients never echo the input text; return codes and ids.
- Sensitive columns (`raw_message.text`, `raw_message.payload`, `media.ocr_text`) are encrypted at rest;
  exports are explicit, household-scoped and audited (`audit_log`).
- What goes to the LLM is minimized: strip balances, account numbers beyond last-4, phone numbers and URLs
  before the data block (see `.claude/rules/prompts.md`).
- Tests never assert on log text; fixtures and eval datasets are anonymized via
  `scripts/quality/anonymize-fixtures.py` (the `scan-secrets` hook also blocks real-looking card and phone numbers
  outside `fixtures/`).
