# Verification report — STORY-145 (closing PR #12)

Combined fresh-context verify+review (low tier). The verifier couldn't write to the main checkout because of the worktree guard, so the orchestrator saved its report verbatim in substance.

## Loop 1 (base e047c6d, head 836f286) — FAIL
| REQ | Result | Evidence |
|---|---|---|
| REQ-OPS-150 | FAIL | `gh run view 40092342642 --json jobs` shows security failed and PR gate failed; 40093004627 is all green; `python -m unittest test_pr_gate -v` passes 4/4 and catches both mutants. But a failed `detect` still lets the gate pass (finding 1). |
| REQ-OPS-151 | PASS (moved) | STORY-147 is DRAFT with `reqs: [REQ-OPS-151]`, and the REQ block is byte-identical to the one at e047c6d. OWNER-014 is resolved with option (c). |

## Findings
| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| MAJOR | pr.yml gate + test_pr_gate.py NOT_GATED | `detect` is not in `gate.needs`. If it fails, every module job is skipped and the gate passes. | Run the gate script with all module jobs `skipped`: it exits 0 |
| MINOR | pr.yml gate | Only known-bad results are rejected, so an empty RESULTS passes. | `RESULTS='' bash gate.sh` exits 0 |
| MINOR | ESCALATIONS.md | OWNER-014 is marked resolved but sits in the Open section. | grep |
| NIT | release.yml:4 | The header comment still describes release-please running on every push to main. | read |
| NIT | test_pr_gate.py | A comment mentions REQ-OPS-151, so trace counts the file as coverage for it. | TRACEABILITY.md row |
| NIT | release.yml:85 | A manual dispatch started from a `v*` tag would run the release jobs. | reasoned from the `if:` expression |

TEST_INTEGRITY: OK

## Loop 2 fixes (orchestrator)
- The gate now needs `detect` and requires `.detect.result == "success"`, checked with `jq` because GitHub pretty-prints `toJSON(needs)` across lines. An empty result set fails.
- The test now covers a failed, skipped or missing `detect` and empty input, with inputs in GitHub's pretty-printed format.
- The release header comment is corrected, and release jobs now require a push event.
- Resolved escalations moved under the Resolved heading, and the REQ-OPS-151 mention is removed from the test.

## Loop 2 (head 9418499) — PASS
| REQ | Result | Evidence |
|---|---|---|
| REQ-OPS-150 | PASS | `python -m unittest test_pr_gate -v` passes 5 tests, and all 4 mutants are caught (security-fast or detect removed from needs, the failure pattern narrowed, the jq block deleted). The real gate step on pretty-printed RESULTS exits 0 when all are green; failure, cancelled, failed detect, empty and malformed input all exit non-zero. CI run 40137648022 at 9418499: all jobs succeed, and the PR gate log prints "All required jobs passed". |
| REQ-OPS-151 | PASS (moved) | STORY-147 is DRAFT with identical text, and the trace row lists no tests. |

All six loop-1 findings are fixed. Two NITs remain: a push to main starts a release run where every job is skipped (documented in the header), and the old grep would have matched pretty-printed lines anyway (jq is still the stricter check).

TEST_INTEGRITY: OK

STATUS: PASS
