# Prompt template — `architect` (stage 3)

Agent tool: `subagent_type: architect`. Its write scope is docs/, contracts/, test dirs, CLAUDE.md files,
`.claude/rules/`, build files (see output-contracts.json). It must not write production code.

```
Design one approved Masroof story. The spec is final; do not re-open it (if it is wrong, say so under BLOCKED).

Story file: <path> (fill the "Design notes (architect)", "Contract impact" and "Data / migration impact" sections in place)
Contract: contracts/openapi.yaml (source of truth; servers and clients are generated from it, generated dirs are never hand-edited)
Module conventions: CLAUDE.md, backend/CLAUDE.md, web/CLAUDE.md, android/CLAUDE.md (whichever exist)
ADRs: docs/adr/ (next number: <NNNN>, template docs/templates/adr.md, MADR). Write one only for a significant,
hard-to-reverse choice (new dependency, schema shape, trust boundary, breaking contract change).
Threat model: docs/security/THREAT_MODEL.md (STRIDE per trust boundary; update the affected boundary if the
story touches auth, webhooks, ingest, uploads, PII, crypto, prompts, Android permissions or dependencies).
Rules to honor: .claude/rules/*.md (money as long minor units + currency, Africa/Cairo business dates stored as
timestamptz, immutable applied migrations with expand->migrate->contract, prompts versioned under
backend/src/main/resources/prompts/<name>.v<N>.md, no logging of bodies/OCR/account numbers above DEBUG).

Produce, in the story file:
- Module boundaries touched (hexagonal: domain / application / adapters/in / adapters/out), key types and
  their invariants, sequence of calls, error handling and idempotency, feature flag if the change is risky.
- Contract delta: exact paths/schemas added or changed; mark breaking vs additive. If breaking: ADR required
  and say how clients migrate. Run `make contract-lint` and `make contract-diff BASE=main` and paste the summary.
- Migration plan: `V<N>__<snake_name>.sql` names (next N from backend/src/main/resources/db/migration), expand ->
  migrate -> contract steps, backfill, rollback notes. Never modify an existing V* file.
- Test strategy hints for the test-designer: which REQs need property tests (jqwik), integration
  (Testcontainers), contract, e2e, eval cases.
- Threat notes: assets, entry points, trust boundary, mitigations (reference ASVS/MASVS/LLM Top 10 ids).

Commands you may run: read-only git, `make contract-lint`, `make contract-diff BASE=main`, `ls`, `grep`.
Do not write implementation code or tests. Keep the design small enough for the story's size (S/M).

End your final message with exactly this contract (STATUS must be the last line):
DESIGN: <3-6 lines: approach, modules, key types>
CONTRACT_IMPACT: none | additive: <paths> | breaking: <paths> (ADR-NNNN)
MIGRATION_IMPACT: none | V<N>__<name>.sql: <one line each>
THREATS: none (<reason>) | <boundary>: <threat> -> <mitigation>
STATUS: DESIGN_READY | BLOCKED
```

Parse: `DESIGN_READY` -> check `CONTRACT_IMPACT`/`THREATS`; run security-reviewer when
[triggers.md](../triggers.md) says so. `BLOCKED` -> if the reason is a spec defect, go back to stage 2 (counts
as a spec loop); otherwise escalate.
