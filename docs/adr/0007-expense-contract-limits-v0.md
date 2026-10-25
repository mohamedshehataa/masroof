# 0007. Tighten expense text limits in the v0 contract

Status: accepted (2026-10-25). The PR carries the `breaking-change` label.

## Context
STORY-020 specifies `notes` at most 500 characters, at most 10 tags, each tag at most 30 characters. The v0
contract allowed 2000 / unbounded / 40. The generated request validation enforces the contract, so the limits must
be in the contract rather than only in service code. There are no API clients yet (the PWA and the Android app are
still being built against the generated clients), so nothing in the field depends on the looser limits.

## Decision
Lower the limits on the shared `ExpenseCreate` and `ExpensePatch` schemas (and mirror them on `Expense`):
`notes` maxLength 500, `tags` maxItems 10, `tags[]` maxLength 30. oasdiff reports this as a breaking change on
these paths, which this ADR names:

- `POST /api/expenses`
- `PATCH /api/expenses/{expenseId}`
- `POST /api/review/{expenseId}/resolve` (its `patch` property reuses `ExpensePatch`)

The same change documents `409` on `createExpense` (idempotency key reuse) and `501` as Problem+JSON on the
not-yet-built expense operations.

## Consequences
Clients regenerate from the contract (the web client is regenerated in the same PR). Lengths in the service and the
database count Unicode code points; the generated Bean Validation counts UTF-16 units, so text made mostly of
astral characters (emoji) is rejected slightly earlier by request validation than the code-point rule alone would.

## Status codes
Text-limit violations (notes > 500 code points, more than 10 tags, a tag over 30 characters) are rejected by request validation with **400** Problem+JSON naming the field. REQ-EXP-002's text says 422; the contract-level limit makes these malformed requests, and the service-level 422 checks stay as a backstop. STORY-020's acceptance text is to be aligned when the story closes.
