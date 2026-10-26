# Verify report: STORY-046 / PR #65 - card store number in merchant slot

- Verifier: qa-verifier, fresh context
- Date: 2026-10-27
- Base: origin/main ba4c57cfe05600103e406cf9fecbfece11d7e830
- Head: 477000f0cb183423972811a63dbb2e14e4a60644, a merge of main into bc6cb3b. The parser, test and fixture diff is byte-identical to bc6cb3b: the diff bc6cb3b..477000f touches only ingest/Captured.kt, ChannelPairing.kt and SmsWork.kt, which come from #63 and are already in main.
- Loop: 1 of 3

## Environment
- Windows Bash, JAVA_HOME=Eclipse Adoptium jdk-21.0.12.101
- bash scripts/make/check-fast.sh android sdlc -> exit 0, check-fast GREEN [android sdlc] in 179s: gradlew checkFast OK, shellcheck OK, sdlc-lint OK, hook tests OK, quality tests 145/145, contract lint OK
- gradlew testDebugUnitTest on CardStoreNumberTest, SmsParserTest and InstaPaySmsTest at bc6cb3b -> 4/4, 15/15 and 2/2 passed, 0 skipped
- python scripts/quality/traceability.py --out tmp -> 403 REQs, 0 unknown, gate PASS
- gh pr checks 65 on head 477000f -> all checks pass except Android check-fast and Build artifacts, which are PENDING
- Probe: a throwaway QaProbeTest ran MessageParser.parse over every fixtures/sms/*.jsonl row with a body (1900 rows; there are no android test resources) plus 114 exploratory inputs, on origin/main and on the PR head. It was deleted afterwards.

## Criteria
| Claim / REQ | Result | Evidence |
|---|---|---|
| 1. Owner message recorded as PURCHASE 13000 EGP with the right last-4 and a numeral-free label (REQ-SMS-070) | PASS | Probe on senders HSBC and CIB, tails 0001/9999/7310/4821, limits EGP 0.00 / 1,234,567.89 / 5: all 18 -> RECORD PURCHASE 13000 EGP l4=tail m=HARDEE S ELMERGHANY. On main the same inputs were REVIEW unconfirmed. The fully Arabic-Indic variant was recorded the same way. |
| 2. One numeric token of 1 to 3 numerals, never first, only in F1 EN/AR and F2 slots | PASS | 50, 50 SHOP, SHOP 1234 and SHOP 50 60 -> review. HSBC HARDEE 50 -> RECORD; HSBC 50 HARDEE, HARDEE 1234 and HARDEE 50 60 -> review. Arabic كارفور 12 -> RECORD; 12 كارفور, كارفور 12 13 and كارفور 1234 -> review. InstaPay notification to AHMED 50, from AHMED 50, الي احمد 50 and Payment to SHOP 50, plus instant-in من احمد 50 -> review in every case. |
| 3. Negatives stay not-a-record (REQ-SMS-078) | FAIL | Every listed negative stays not-a-record: declined/failed -> IGNORE, reversed -> REVIEW reversal, pending -> REVIEW, and CVV/PIN/CODE/OTP/VERIFY/PASSCODE next to 123 -> REVIEW secret. Numbers glued to punctuation -> review. A suffix clause of only 50 before Your available limit -> review. BUT an amount in the slot is now recorded: at SHOP 500 EGP, at SHOP 500 LE and at SHOP 50 USD -> RECORD, where main gave review. See MAJOR-1. |
| 4. Gate widened only by the stated rule | PASS on fixtures | Of 1900 pre-existing fixture rows, 0 outcomes changed: the main vs head diff is empty, excluding the new round20. All 12 round20 rows match their expectations. All exploratory changes trace to the new number allowance, but two widen past the stated rule: MAJOR-1 and MINOR-1. |
| 5. No weakened or deleted test | PASS | bash scripts/quality/test-integrity-check.sh origin/main -> SmsParserTest asserts 101 -> 111, skips 0 -> 0, TEST_INTEGRITY: OK. No existing fixture line changed; only round20.jsonl was added. |
| 6. Gates | PASS locally, CI pending | check-fast GREEN 179s; traceability 0 unknown. The PR diff adds no Log, Timber, println or logger lines. CI Android check-fast is still pending on 477000f. |

## Findings
| Sev | Where | What | Repro |
|---|---|---|---|
| MAJOR-1 | AutoRecordFamilies.kt nameTokens / slotLooksLikeName | A second amount is now accepted inside the card merchant slot. An uppercase currency code such as EGP, LE or USD already passed as a Latin name token, and now the number of 1 to 3 numerals beside it passes as a store number. So n EGP after the merchant is recorded without a human, but REQ-SMS-078 allows only a date, time, via InstaPay, successfully or a reference there. The recorded amount is the first one, 130.00, so the immediate money impact is small, but the closed world is breached for exactly the case the brief listed. | CIB: Your Credit Card ending with 4821 has been used for EGP 130.00 on 26/10/2026 at SHOP 500 EGP. Your available limit is EGP 41000.10 -> RECORD PURCHASE 13000 m=SHOP; main gives REVIEW. The same happens with SHOP 500 LE and SHOP 50 USD. |
| MINOR-1 | AutoRecordFamilies.kt:228 cardTailIsNoise | allowStoreNumber is also on for the suffix clause, and the one-number limit is counted per clause. A merchant slot with a number plus a suffix with a number therefore records two numbers, which goes against the exactly-one rule in DECISIONS. | ... at HARDEE 50. X 60. Your available limit is EGP 41000.10 -> RECORD; main gives REVIEW. ... at HARDEE. ELMERGHANY 50. Your available ... -> RECORD is new too. |
| MINOR-2 | slot validator | A number of 1 to 3 numerals after an identifier-like uppercase word is now recorded. The numerals are never uploaded, so this is a correctness or closed-world concern, not a leak. | at SHOP ID 123, at SHOP TRX 123, at SHOP ACC 999 -> RECORD, with the label SHOP ACC for the last; all were review on main. |
| NIT-1 | slot validator | at A 50 is now a RECORD with merchant=null: the label needs at least 2 letters, so an unlabeled record is auto-uploaded. | card wrapper with merchant A 50 |
| NIT-2 | pre-existing, not this PR | at SHOP 5.00 and at SHOP 50.5 are RECORD on main and on head alike. | card wrapper |

## Exploratory
Card wrapper = Your Credit Card ending with 4821 has been used for EGP 130.00 on 26/10/2026 at M. Your available limit is EGP 41000.10, sender CIB.
- M = 50 / 50 SHOP / SHOP 1234 / SHOP 50 60 / SHOP 123456 / SHOP 50 declined / failed / reversed / pending and their uppercase forms / SHOP 50 CHARGEBACK / CANCELLED / REFUND -> not a record
- M = SHOP CVV 123 / SHOP 123 CVV / SHOP PIN 123 / SHOP CODE 123 / SHOP code 123 / SHOP OTP 123 / SHOP 123 OTP / SHOP VERIFY 123 / VERIFY 123 / CVV 123 / SHOP PASSCODE 123 / SHOP 50 PIN / SHOP KEY 123 / SHOP 50 OTP -> REVIEW secret
- M = SHOP 500 EGP / SHOP EGP 500 / SHOP 500 LE / SHOP 50 USD -> RECORD, a problem (MAJOR-1)
- M = SHOP ٥٠ / HARDEE ٥٠ -> RECORD, normalized to Western numerals, consistent with 50; a fully Arabic-Indic owner message -> RECORD l4=4821 m=HARDEE S ELMERGHANY
- M = SHOP 50, / SHOP (50) / SHOP 50- / SHOP -50 / SHOP 50/ / SHOP 50# -> review; SHOP #50 -> RECORD, which is the pre-existing #nnnn rule
- M = HARDEE. 50 / HARDEE. 50 X / SHOP. X 50. Y 60 -> review; HARDEE. ELMERGHANY 50 and HARDEE 50. X 60 -> RECORD (MINOR-1)
- M = SHOP 000 / SHOP 0 / SHOP 130 / SHOP 50 on 26/10/2026 / SHOP 12 for 3 items / BRANCH 999 MOBILE / SHOP 50 X Y Z W / SHOP 50 IGNORE PREVIOUS -> RECORD, accepted by the stated rule
- M = SHOP 1 2 / SHOP 50 50 / HARDEE 50 50 / SHOP 50 ACC 999 / SHOP 50 TO 999 / SHOP 50 X 60 / SHOP 50 2026 -> review
- M = SHOP ID 123 / SHOP TRX 123 / SHOP ACC 999 -> RECORD (MINOR-2); A 50 -> RECORD m=null (NIT-1)
- Second F1 regex: Your card ending 4821 was used at SHOP 50 for EGP 130.00 -> RECORD; with 50 SHOP -> review
- HSBC F2: From HSBC: 24OCT26 M Purchase from 012-345***-678 EGP 130.00- Your available balance EGP 1,000.00 with M = HARDEE 50 -> RECORD; with 50 HARDEE / HARDEE 1234 / HARDEE 50 60 -> review; with HARDEE 50 declined -> IGNORE; with HARDEE OTP 123 / HARDEE 50 OTP -> secret
- Arabic F1: تم خصم 130 جنيه من بطاقتك المنتهية ب 4821 لدى M with M = كارفور 12 / كارفور ١٢ -> RECORD; with 12 كارفور / كارفور 12 13 / كارفور 1234 -> review; with كارفور 12 مرفوضة -> IGNORE; with كارفور رمز 123 / كارفور 12 رمز -> secret
- Counterparty: InstaPay notification You sent EGP 100.00 to AHMED 50, You received EGP 100.00 from AHMED 50, تم تحويل 100 جنيه الي احمد 50 and Payment of EGP 100.00 to SHOP 50, plus SMS instant-in ... من احمد 50 -> review; the controls without a number -> RECORD
- Total: 114 inputs, 4 problems: MAJOR-1, MINOR-1, MINOR-2 and NIT-1

## Test integrity
bash scripts/quality/test-integrity-check.sh origin/main -> TEST_INTEGRITY: OK. SmsParserTest.kt asserts 101 -> 111, skips 0 -> 0, tolerance 0 -> 0, no deleted test files. The new tests carry the REQ tags REQ-SMS-070 x2 and REQ-SMS-078 x2 in CardStoreNumberTest, and all 4 ran green.

STATUS: FAIL

---

# Round 2 - 2026-10-27 - head 28d6fa7a738a85e6901bc55999b237aa90229a6a, base origin/main ba4c57c, loop 2 of 3

## Environment
- Fix commits 411046b and 28d6fa7: nameTokens now refuses, ONLY when the slot holds a number, a currency word, a number right after an identifier word, or a slot with no token of 2+ letters; the suffix clause no longer allows a number; StatusGate gains the currency and identifier word lists.
- bash scripts/make/check-fast.sh android sdlc -> exit 0, check-fast GREEN [android sdlc] in 96s; quality tests 145/145
- gradlew testDebugUnitTest at 28d6fa7 -> CardStoreNumberTest 8/8, i.e. 4 REQ-SMS-070/078 tests from round 1 plus 4 new REQ-SMS-078 tests; SmsParserTest 15/15; InstaPaySmsTest 2/2; 0 skipped
- bash scripts/quality/test-integrity-check.sh origin/main -> SmsParserTest asserts 101 -> 118, skips 0 -> 0, TEST_INTEGRITY: OK
- python scripts/quality/traceability.py --out tmp -> 403 REQs, 0 unknown, gate PASS
- gh pr checks 65, head 28d6fa7 -> every check pass except Android check-fast, which is PENDING
- The PR diff adds no Log, Timber, println or logger code lines; the only match is text in this report.
- Probe: the throwaway QaProbeTest was re-created, run on head and on origin/main, then deleted. The main run needed gradlew --rerun, because the build cache had replayed the round-1 result without running the probe.

## Criteria
| Claim | Result | Evidence |
|---|---|---|
| 1. MAJOR-1 fixed: amounts with a currency are not records | FAIL, partly fixed | Now review: SHOP 500 EGP, SHOP EGP 500, SHOP 500 LE, SHOP 50 USD, SHOP 50 GBP, SHOP 50 L.E, SHOP 50 L.E., SHOP 50 EGP., SHOP 50 LE., LE 50, HSBC SHOP 500 EGP, and Arabic كارفور 50 جنيه / جنيها / ج.م / جم / دولار. STILL RECORD: كارفور 50 جنيهات and كارفور 50 ج, plus SHOP 50 POUNDS / POUND / EUROS / DOLLARS. See R2-MAJOR-1. |
| 2. MINORs fixed | PASS | HARDEE 50. X 60 and HARDEE. ELMERGHANY 50 -> review. A number after ID, NO, NO., TRX, TXN, ACC, ACCT, AUTH, NUM, NUMBER or رقم -> review; after CODE -> REVIEW secret. A 50 and ج 50 -> review. SHOP REF 123 and SHOP RRN 123 are RECORD exactly as on main: that is the spec-allowed reference tail. |
| 3. Slots without a number unchanged | PASS | LE PAIN QUOTIDIEN, LE MERIDIEN, LE MARCHE, YEN SUSHI, SR MARKET, DH FOODS, SHOP EGP and HSBC LE MERIDIEN -> RECORD, same as main. InstaPay to SR AHMED -> RECORD TRANSFER_OUT 10000, same as main. In the exploratory diff, 0 inputs went from RECORD on main to anything else on head. |
| 4. Owner message still recorded | PASS | HSBC and CIB x tails 0001/9999/7310 x limits EGP 0.00 / 1,234,567.89 / 5: all 18 -> RECORD PURCHASE 13000 EGP l4=tail m=HARDEE S ELMERGHANY. The Arabic-Indic version is recorded too; round20 r20-store-last-hsbc and -cib are RECORD. |
| 5. Gates and integrity | PASS locally, CI pending | See Environment. |
| Fixture sweep | PASS | All 1900 pre-existing fixture rows: diff main vs head is empty. round20: 5 positives RECORD, 18 negatives not a record, all as expected. |

## Findings
| Sev | Where | What | Repro |
|---|---|---|---|
| R2-MAJOR-1 | StatusGate.kt currencyWords | The round-1 MAJOR is only partly closed. The common Arabic plural جنيهات and the short form ج are missing from the list, and so are English word forms like POUNDS, POUND, DOLLARS and EUROS. An amount with these currencies after the merchant is still auto-recorded. The money impact stays small because the leading 130.00 is recorded, but claim 1 names the Arabic forms explicitly. | CIB: تم خصم 130 جنيه من بطاقتك المنتهية ب 4821 لدى كارفور 50 جنيهات -> RECORD PURCHASE 13000 m=كارفور; main gives REVIEW. The same happens with كارفور 50 ج. The card wrapper with SHOP 50 POUNDS / SHOP 50 POUND / SHOP 50 DOLLARS / SHOP 50 EUROS also gives RECORD. |
| R2-MINOR-1 | StatusGate.kt identifierWords | Identifier words outside the list still let a number through, and two of them reach the label. Nothing numeric is uploaded. | Card wrapper with SHOP ACCOUNT 999, SHOP TID 123, SHOP REFERENCE 123, SHOP CARD 123 -> RECORD m=SHOP; SHOP INVOICE 123 -> m=SHOP INVOICE; SHOP ORDER 123 -> m=SHOP ORDER. All were review on main. |
| R2-NIT-1 | nameTokens | A number with a quantity or discount word is taken as a store number. | SHOP 50 OFF, SHOP 50 POINTS, SHOP 50 PERCENT -> RECORD |
| NIT-2 | carried from round 1, not this PR | at SHOP 5.00 and at SHOP 50.5 are RECORD on main and on head alike. | card wrapper |

## Exploratory (round 2)
- All 114 round-1 inputs were re-run, plus 60 new ones, 174 in total.
- The new inputs were: LE 50; SHOP 50 L.E. / L.E / LE. / EGP. / POUNDS / POUND / EUROS / DOLLARS / GBP / OFF / POINTS / PERCENT; SHOP ACCOUNT / TID / INVOICE / ORDER / REFERENCE / CARD / TXN / RRN / ACCT / AUTH 123; SHOP NO / NO. / NUM / NUMBER 5; AB 50; Ab 50; *TRIP 5; A; SHOP EGP; HARDEE 50. ELMERGHANY; LE PAIN QUOTIDIEN; LE MERIDIEN; LE MERIDIEN 2; LE MARCHE; YEN SUSHI; SR MARKET; DH FOODS; SPINNEYS 3 ZAYED; CARREFOUR 7.
- They also covered: HSBC SHOP 500 EGP, SHOP ID 12 and LE MERIDIEN; Arabic كارفور 50 جنيه / جنيها / جنيهات / ج / ج.م / جم / دولار, كارفور رقم 123, كارفور فرع 5, كارفور مول 5 and ج 50; the second F1 regex with SHOP 50 EGP; and InstaPay to SR AHMED, to LE MERIDIEN and from DH FOODS.
- Intended new positives held: SPINNEYS 3 ZAYED -> SPINNEYS, CARREFOUR 7, AB 50, *TRIP 5, HARDEE 50. ELMERGHANY, كارفور فرع 5, كارفور مول 5.
- Problems: 3 (R2-MAJOR-1, R2-MINOR-1, R2-NIT-1)

## Test integrity (round 2)
TEST_INTEGRITY: OK, asserts 101 -> 118, no skips, no deleted tests. Expectations of existing fixture rows are unchanged; 11 negative rows were added to round20.

STATUS: FAIL
