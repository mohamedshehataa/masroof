# Prompt templates — triggered specialist reviewers (stage 7, parallel)

Launch each triggered agent in the same message as `code-reviewer`. Shared header for all of them:

```
Specialist review of one Masroof change. Fresh context; you cannot edit files. Review the diff, not the author.
Story file: <path>. Branch: story/<STORY>-<slug>. Base: <BASE sha>. Head: <HEAD sha>. Diff: `git diff <BASE>...<HEAD>`.
How to run: `source scripts/env.sh`; `make check-fast MODULE=<m>`; `make up`; <stage-specific targets below>.
Verify report: <path>.verify.md. Trigger that brought you here: <matched paths>.
Claims to verify (treat as untested):
- <same list as given to qa-verifier>
Every finding: severity (BLOCKER/MAJOR/MINOR/NIT), location, why it matters, concrete fix.
End your final message with the contract below; STATUS must be the last line.
```

## `ux-a11y-reviewer` (with `i18n-rtl-reviewer`, always both)
```
Compare every touched screen against docs/PROMPT.md §11 (design language, navigation, key screens).
Check all UX states: empty, loading, error with retry, offline, RTL, dark, font scale 1.5 (Android).
Evidence: screenshot tests (`make e2e` / Roborazzi output under android/**/build/outputs/roborazzi, Playwright
`web/test-results`), axe-core results (zero serious/critical), TalkBack content descriptions, 48dp targets,
contrast AA, focus order, tabular numerals for amounts.
Contract:
FINDINGS:
- [SEV] <screen/component> — <what> — <fix>
STATES_CHECKED: empty | loading | error | offline | RTL | dark | font-1.5 (mark each checked/missing)
STATUS: APPROVE | CHANGES_REQUESTED
```

## `i18n-rtl-reviewer`
```
Check Arabic and English completeness (no hard-coded user-facing strings; every key present in both locales),
Egyptian Arabic phrasing (colloquial where §11 says so, consistent terms with the default categories in §8),
RTL mirroring (icons with direction, paddings, swipe directions, charts), bidi isolation of numbers and Latin
merchant names inside Arabic text, Western vs Arabic-Indic digit setting respected, currency formatting
`1,250 EGP` / `١٬٢٥٠ ج.م`, plural rules, date formats in Africa/Cairo.
See .claude/skills/arabic-text/SKILL.md for the normalization and bidi rules.
Contract:
FINDINGS:
- [SEV] <file:line or key> — <what> — <fix>
STATUS: APPROVE | CHANGES_REQUESTED
```

## `performance-engineer` (worktree)
```
Measure, do not guess. Targets: `make perf` (k6, Lighthouse CI, size-limit, Macrobenchmark where available);
`make up` plus EXPLAIN (ANALYZE) on new or changed queries (no seq scan on hot tables above 10k rows; no N+1 —
check Hibernate statistics assertions in integration tests); p95 budgets from PROMPT.md S9 (reads < 200 ms,
writes < 400 ms at 20 rps; initial JS <= 200 KB gzip; cold start < 1.5 s).
Contract:
FINDINGS:
- [SEV] <where> — <what> — <fix>
MEASUREMENTS:
- <metric>: <value> (budget <x>) — `<command>`
STATUS: APPROVE | CHANGES_REQUESTED
```

## `ai-eval-engineer`
```
Judge the change on data. Run `python eval/runners/run.py --fast` (and the full affected suite when a prompt or
model config changed: `python eval/runners/run.py --suite <suite>`). Compare to eval/baseline.json: no metric may
drop more than 1 percentage point; OTP false negatives and obeyed injected instructions must be 0; cost p95 may
not rise more than 25% without an ADR. Check dataset hygiene (few-shot examples only from the train split; no
test-split leakage; `source` set; anonymized). Check prompt file header and version bump
(backend/src/main/resources/prompts/<name>.v<N>.md) and that the version id is logged on ai_call_log rows.
You may write under eval/, docs/, fixtures/ only (new cases, report notes); never the baseline here.
Contract:
METRICS:
- <suite>/<metric>: <baseline> -> <now> (<gate>)
REGRESSIONS:
- none | <suite>/<metric> <delta>
FINDINGS:
- [SEV] <where> — <what> — <fix>
STATUS: APPROVE | REJECT | BLOCKED
```

Parse: `APPROVE` from each -> fine. `CHANGES_REQUESTED` / `REJECT` -> review fix loop (counts toward the
3-loop limit). `BLOCKED` from ai-eval-engineer (no API key, dataset missing) -> run the reference adapter
(`--adapter reference`) if that is what is missing, else escalate as `OWNER-NNN`.
