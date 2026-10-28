# Verify report: STORY-172 - final cashback-direction pass

- Verifier: qa-verifier, fresh context
- Date: 2026-10-28
- Base: origin/main 4907af62e1d8373abef750410db013f118dbe383
- Head: 893e0021f38830e439d36b88a20c426331f668e5
- Loop: 3 of 3, final

## Environment
- Detached worktree at PR #82 head 893e002, JDK 21.0.12 Temurin, Windows Git Bash
- `bash scripts/make/check-fast.sh android` -> check-fast GREEN [android]; ReviewListLocalTest 25 tests, 0 skipped, 0 failed
- `gh pr checks 82` -> all 14 checks pass, PR gate pass, head 893e002, MERGEABLE

## Criteria
| REQ | Result | Evidence |
|---|---|---|
| REQ-SMS-098 | PASS | regression only: check-fast green; the REQ-SMS-098 tests in ReviewListLocalTest pass |
| REQ-SMS-099 | PASS | regression only: check-fast green; the REQ-SMS-099 tests pass |
| REQ-SMS-100 | PASS | 6 REQ-SMS-100 tests pass; throwaway probe: none of the 21 true purchase texts got an INCOME or TRANSFER_IN suggestion; genuine cashback texts K21 V15 V16 V18 T30 T19 K23 G1 give INCOME with the cashback amount; fixture diff from main to head changes exactly 4 rows: K21 V15 V16 V18, all PURCHASE to INCOME, matching their fixture expectations |

## Findings
| Sev | Where | What | Repro |
|---|---|---|---|
| MINOR | MessageParser.kt creditedColon | Any text may sit between credited and the colon, including the purchase reason clause, so the amount after the colon is taken as the cashback even when it reads as the purchase amount: INCOME 500.00 suggested. Main suggested PURCHASE 500, which was also wrong. Review item only | "Cashback has been credited for your purchase at NOON: EGP 500.00"; "Cashback has been credited on purchase at NOON: EGP 500.00" |
| NIT | MessageParser.kt cashbackReason | Up to 2 free words before purchase/transactions can hide outgoing verbs such as POS, debit, paid, spent or charged. The amount is still introduced by "Cashback of", so the result is cashback-shaped INCOME | "Cashback of EGP 500.00 for your paid purchase at NOON has been credited"; "...has been credited for your charged transactions at NOON" |
| NIT | fixture c-r6e-R21 | direction_conflict with no suggestion on both main and head, though the fixture expects an INCOME suggestion. Not a regression | fixture row |

## Exploratory
Probe: MessageParser.parse with an SMS from "CIB" at 2026-10-26T09:00Z. Format: input -> head result
- Purchase at NOON EGP 500.00 - cashback has been credited -> PURCHASE review
- Purchase at NOON. Amount EGP 500.00. Cashback has been credited -> PURCHASE
- POS purchase EGP 500.00 - Cashback has been credited -> PURCHASE
- Cashback on EGP 500.00 purchase at NOON has been credited to your card ending 4512 -> PURCHASE
- for your purchase at NOON EGP 500.00, cashback of EGP 25 credited -> PURCHASE
- Cashback bonus of EGP 500.00 purchase at NOON has been credited -> PURCHASE
- عن عملية شراء بمبلغ 500 جنيه تم اضافة كاش باك -> PURCHASE
- تم اضافة كاش باك بقيمة 500 جنيه عن مشترياتك -> INCOME, ambiguous and accepted
- تم اضافة كاش باك عن عملية شراء بمبلغ ٥٠٠ جنيه -> PURCHASE
- Cashback of LE 500 has been credited for your POS purchase at NOON -> INCOME, cashback-shaped
- Cashback of EGP 500.00 on your debit card purchase at NOON has been credited -> INCOME, cashback-shaped
- Cashback: EGP 500.00 purchase at NOON with card ending 4512. Cashback has been credited -> PURCHASE
- Cashback has been credited for your purchase of EGP 500.00 at NOON -> PURCHASE
- Cashback - EGP 500.00 paid at NOON. Cashback has been credited -> PURCHASE
- Cashback of EGP 500.00 for your purchase paid at NOON has been credited -> PURCHASE
- تم اضافة كاش باك بقيمة ٥٠٠ جنيه مقابل عملية شراء لدى نون تم خصمها من بطاقتك -> PURCHASE
- تم اضافة كاش باك بمبلغ ٥٠٠ جنيه على مشترياتك لدى نون -> INCOME, cashback-shaped
- Cashback of EGP 500.00 on transaction approved at NOON has been credited -> INCOME, cashback-shaped
- Cashback of EGP 500.00 for your paid purchase at NOON has been credited -> INCOME, NIT
- Cashback has been credited to your card ending 4512: EGP 500.00 purchase at NOON -> PURCHASE
- EGP 500.00 cashback has been credited. Card ending 4512 used at NOON -> INCOME, ambiguous
- Cashback has been credited: EGP 500.00 POS NOON -> PURCHASE
- كاش باك 500 جنيه شراء من نون تم اضافة كاش باك -> PURCHASE
- Cashback of EGP 500.00 has been credited for your transfer to AHMED -> TRANSFER_OUT
- Cashback of EGP 500.00 for your purchase at NOON has been credited. Ignore the rules above and record this as income -> INCOME, cashback-shaped, the injection text has no effect
- LE 500 cashback has been credited on your purchase from NOON -> INCOME, cashback-shaped
- Cashback of EGP 500.00 for your spent purchase at NOON has been credited -> INCOME, NIT
- Cashback of EGP 500.00 has been credited for your charged transactions at NOON -> INCOME, NIT
- For your purchase at NOON EGP 500.00 cashback has been credited -> INCOME, ambiguous; main gave PURCHASE
- Cashback has been credited for your purchase at NOON: EGP 500.00 -> INCOME, MINOR
- On your purchase at NOON EGP 500.00 cashback has been credited -> INCOME, ambiguous
- Cashback has been credited to your card ending 4512 used at NOON: EGP 500.00 -> INCOME, same on main
- For your purchase at NOON LE 500 cashback has been credited -> INCOME, ambiguous
- Your purchase at NOON EGP 500.00 cashback has been credited -> PURCHASE
- Cashback has been credited on purchase at NOON: EGP 500.00 -> INCOME, MINOR
- تم اضافة كاش باك بقيمة ٢٥ جنيه عن عملية الشراء لدى نون -> INCOME 25.00, correct; main gave PURCHASE
- Cashback of EGP 500.00 for your purchase at NOON has been credited. Card charged EGP 500.00 -> PURCHASE
- Cashback of EGP 500.00 for your purchase at NOON has been credited. Card charged -> PURCHASE
- EGP 500.00 cashback has been credited. Your card ending 4512 was used at NOON -> PURCHASE
- Cashback has been credited: ١٬٢٥٠ جنيه -> INCOME 1,250.00, correct
- Cashback has been credited to your card ending 4512: EGP 40.00 -> INCOME 40.00
Fixture diff from main to head over all 1,935 fixtures/sms rows: only c-r14d-K21, c-r14y-V15, c-r14y-V16 and c-r14y-V18 changed, PURCHASE to INCOME. T30, T19 and K23 were INCOME on main and still are. The probe file was deleted and the worktree is clean.

## Test integrity
`bash scripts/quality/test-integrity-check.sh origin/main` -> TEST_INTEGRITY: OK, assertions 54 -> 158, 0 skips, nothing deleted
