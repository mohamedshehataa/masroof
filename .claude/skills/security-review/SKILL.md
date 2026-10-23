---
name: security-review
description: Runs a full or scoped security review (make security scanners plus security-reviewer in a fresh context against ASVS L2 / MASVS / OWASP LLM Top 10, threat model upkeep) and records findings as stories or escalations. Use when the owner says /security-review, before a phase gate for Phase 3+, or after dependency updates; it does not fix code.
argument-hint: "[STORY-NNN | path/glob | full]"
disable-model-invocation: true
user-invocable: true
---

# /security-review $ARGUMENTS

## 0. Scope
- `STORY-NNN` -> diff mode on `story/STORY-NNN-*` (`BASE=$(git merge-base main <branch>)`, `HEAD`).
- a path or glob -> scoped mode: `git log -1 --format=%H -- <path>` as HEAD, review the current contents of
  those files plus their trust boundary in `docs/security/THREAT_MODEL.md`.
- `full` or empty -> full mode: whole repo at `HEAD`, every boundary in the threat model, all checklists under
  `docs/security/` (`THREAT_MODEL.md`, `ASVS_CHECKLIST.md`, `MASVS_CHECKLIST.md`, `LLM_SECURITY.md`; missing
  checklists are themselves a MAJOR finding to ticket).
- `source scripts/env.sh`; stage stays as is; append `· SDLC · review · INFO · security review started (<scope>)` to PROGRESS.

## 1. Scanners first (deterministic evidence)
```bash
make security                      # semgrep (incl. .semgrep/ custom rules), gitleaks, osv-scanner, trivy, zap/mobsf where available
make sbom 2>/dev/null || true       # CycloneDX SBOMs for the review (Phase 9+)
```
Save the output to the scratchpad; the reviewer gets the path. Scanner tooling missing locally -> note it as
`environment` and keep going (CI runs the full set).

## 2. Reviewer — agent `security-reviewer` (fresh context, no Write)
Prompt from [../story/prompts/security-reviewer.md](../story/prompts/security-reviewer.md) (diff mode) with
these additions for scoped/full mode: "Scope: <paths or 'whole repository'>. Scanner output: <path>. Walk each
trust boundary in docs/security/THREAT_MODEL.md and each checklist item in docs/security/*; mark items
PASS/FAIL/N-A with evidence; probe at least 5 attack attempts against `make up` (auth bypass, IDOR across
households, webhook bad signature, oversized upload, prompt injection via /api/ingest)." Expect
`FINDINGS`, `THREAT_MODEL_UPDATED`, `STATUS: APPROVE | CHANGES_REQUESTED`.
Phase 3+ full mode: launch `ai-eval-engineer` in parallel to run the `adversarial` suite
(`python eval/runners/run.py --suite adversarial`); any obeyed injected instruction is a BLOCKER.

## 3. Record and route
1. Write `docs/security/reviews/<YYYY-MM-DD>-<scope-slug>.md`: scope, scanner summary (counts per tool and
   severity, suppressions with reasons), the FINDINGS table (severity, CWE/ASVS id, location, why, fix), checklist
   deltas, `THREAT_MODEL_UPDATED`, final STATUS. Edit `docs/security/THREAT_MODEL.md` with the reviewer's
   required text (orchestrator glue; reviewers cannot write).
2. Every BLOCKER/MAJOR becomes a story: `/spec "<finding>"` is too heavy for small fixes, so create
   `docs/backlog/EPIC-<security epic>/STORY-NNN-<slug>.md` from the template with REQ-SEC-NNN ids, `status: READY`,
   `size: S`, `owner_agent` by module, and link the review file. A BLOCKER in a story under review ->
   `CHANGES_REQUESTED` for that story (its loop counter applies).
3. Secrets found -> stop everything else, rotate via the owner (escalation `OWNER-NNN`), purge per
   `docs/security/` runbook; never paste the secret anywhere.
4. PROGRESS: `<ts> · P<phase> · <STORY or SDLC> · review · <GREEN if APPROVE and scanners clean, else RED> · security review <scope>: B/M/m = a/b/c; <n> stories created`.
5. Commit: `docs(security): review <scope> [STORY-NNN|SDLC]`. `make check-fast MODULE=sdlc` green before stopping.

## Exit gate
Review file committed with scanner evidence, every BLOCKER/MAJOR either fixed in a story pipeline or ticketed
as a READY story, threat model updated where required. APPROVE is reported only when the reviewer said so and
`make security` has no unexplained findings.
