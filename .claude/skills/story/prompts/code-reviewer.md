# Prompt template — `code-reviewer` (stage 7, fresh context)

Agent tool: `subagent_type: code-reviewer`. Launch in the same message as the triggered specialists.
No author summary; the "Claims to verify" list is the same one the verifier got.

```
Review one Masroof change. Fresh context; you cannot edit files. Review the diff, not the author.

Story file: <path> (spec with REQ ids; "Design notes (architect)").
Branch: story/<STORY>-<slug>. Base: <BASE sha>. Head: <HEAD sha>. Diff: `git diff <BASE>...<HEAD>`.
How to run: `source scripts/env.sh`; `make check-fast MODULE=<m>`; `make up`.
Verify report (evidence, not opinion): <path>.verify.md
Invariants: CLAUDE.md "Invariants" + .claude/rules/*.md (money as long minor units + ISO currency, never
double/float; Africa/Cairo business dates stored as timestamptz; no body/OCR/account-number logging above DEBUG;
untrusted LLM input wrapped in data blocks; applied migrations immutable; generated code untouched).
Review report template: docs/templates/review-report.md.
Claims to verify (treat as untested):
- <same list as given to qa-verifier>

Order of review: correctness against each REQ -> design and boundaries (hexagonal layers, no domain->framework
dependency, adapters do not call each other) -> error handling, concurrency, idempotency (duplicate delivery,
retries) -> test quality (do the tests assert behavior, would they catch a plausible bug, are mocks hiding the
real integration, were test-designer tests touched?) -> naming/readability -> dead code and leftover TODOs
without a story id. Run the tests or a quick check when a doubt can be settled by a command.
Every finding: severity (BLOCKER = wrong behavior or invariant broken; MAJOR = likely bug or missing test;
MINOR = quality; NIT = style), location file:line, why it matters, a concrete fix.

End your final message with exactly this contract (STATUS must be the last line):
FINDINGS:
- [BLOCKER|MAJOR|MINOR|NIT] <file:line> — <what> — <why it matters> — <fix>
STATUS: APPROVE | CHANGES_REQUESTED
```

Parse: collect FINDINGS into `<story>.review.md` together with the specialists' findings (one table, sorted by
severity, "Reviewer" column filled). `APPROVE` from everyone and zero BLOCKER/MAJOR -> stage 8.
`CHANGES_REQUESTED` -> review fix loop (counts toward the 3-loop limit): implementer -> verify -> review again.
