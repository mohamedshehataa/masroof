# STORY-185 security review (security-reviewer, fresh context)

## Round 1 - head `713f94b` - APPROVE
Commands: `bash scripts/make/check-fast.sh android` -> GREEN in 159s; `bash scripts/make/security.sh` -> osv-scanner OK,
trivy fs/config OK, semgrep not installed locally (custom rules checked by hand on the added lines: 0 matches), gitleaks
full history `leaks found: 2` (the story diff scans clean; the 2 hits are the AWS documentation example key in an
unmerged backend SigV4 test, see docs/DECISIONS.md).
Clean: every text column goes through the formula guard and RFC 4180 quoting; SMS-derived merchant labels cannot carry
`=`, `;`, `|` or line breaks (LabelNormalizer); the export query reads typed entry fields only (no review text, message
text, sender ids, receipt names or hashes, last fours); the new paged query is a literal `@Query` with bound
parameters; nothing logged; exception text never reaches the UI; output only to the user-chosen SAF document, no cache
copy, no FileProvider; no manifest, permission, network, dependency or scanner-config change.
FINDINGS:
- [MINOR] CsvFormat.kt:31-39 -- the guard checked only the first character; in `;`-separator Excel locales or with a bare
  LF inside a field, a new cell starting with `=` could be evaluated (user-typed or server-imported text only)
- [MINOR] ExportCsvViewModel.kt:105-107 -- a cancelled export left a truncated file at the chosen location
- [MINOR] CsvExportTest.kt:348,355 -- the leak test did not assert the source sender id or the instrument last four
- [NIT] leading whitespace/zero-width characters before `=`; openOutputStream on Main with the Activity context
THREAT_MODEL_UPDATED: CSV export row (app -> user-chosen SAF document): formula injection mitigated by guarding every
text cell; information limited to typed entry fields; half-written file discarded on failure (and, after the fix round,
on cancel).
STATUS: APPROVE (all MINORs and NITs fixed in `3e46c37`)
