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

---

# Round 3 - 2026-10-27 - head 5a7c9adef2658323cfbbaead2b00c24ee766815d, base origin/main ba4c57c, loop 3 of 3

## Environment
- Fix commit 5a7c9ad: the store number must be the LAST token of a card or HSBC slot. The token before it needs 2+ letters, must not be an identifier word (the set was extended with account, tid, mid, terminal, reference, card, invoice, order, bill, receipt, pos, transaction, حساب, فاتورة, طلب, بطاقة) and must not be a currency word. The currency set gained جنيهات, ج, pound(s), dollar(s), euro(s).
- bash scripts/make/check-fast.sh android sdlc -> exit 0, check-fast GREEN [android sdlc] in 97s; quality tests 145/145
- gradlew testDebugUnitTest --rerun at 5a7c9ad -> CardStoreNumberTest 9/9, SmsParserTest 15/15, InstaPaySmsTest 2/2, 0 skipped
- bash scripts/quality/test-integrity-check.sh origin/main -> SmsParserTest asserts 101 -> 122, skips 0 -> 0, TEST_INTEGRITY: OK
- python scripts/quality/traceability.py --out tmp -> 403 REQs, 0 unknown, gate PASS
- gh pr checks 65, head 5a7c9ad -> all pass except Android check-fast and Build artifacts, which are PENDING
- Probe: the throwaway QaProbeTest was re-created and run with gradlew --rerun on head and on origin/main, over all 1900 fixture rows plus 251 exploratory inputs (the 174 from rounds 1-2 plus 77 new). A second head run checked 13 amount-word variants. The probe is deleted.

## Criteria
| Claim | Result | Evidence |
|---|---|---|
| 1. Every round 1-2 repro is not a record | PASS | Not records on head: SHOP 500 EGP / EGP 500 / 500 LE / 50 USD / 50 POUNDS / POUND / DOLLARS / EUROS / OFF / POINTS / PERCENT; SHOP ACCOUNT 999, TID / REFERENCE / CARD / INVOICE / ORDER / MID / TERMINAL / POS 123; SHOP POUNDS 50; كارفور 50 جنيهات, كارفور 50 ج, كارفور ج 50; HARDEE 50. X 60; A 50. round20 holds 30 not_record rows and all of them are not records. |
| 1b. Before-side gap hunt | FAIL | A non-identifier word before the number passes. Most of these only give an odd label with the right amount. BUT on the second F1 template the amount is wrong: see R3-MAJOR-1. |
| 2. Owner message, CARREFOUR 12, كارفور 12 recorded with numeral-free labels | PASS | HSBC and CIB x tails 0001/9999/7310 x 3 limits: 18/18 RECORD PURCHASE 13000 m=HARDEE S ELMERGHANY. CARREFOUR 12 -> m=CARREFOUR. كارفور 12 and كارفور ١٢ -> m=كارفور. CARREFOUR 7, AB 50 and *TRIP 5 -> RECORD. |
| 3. Slots without a number identical to main | PASS | 1900/1900 pre-existing fixture rows give the same outcomes (diff empty). In the exploratory diff, 0 records on main stopped being records. LE PAIN QUOTIDIEN, LE MERIDIEN, YEN SUSHI, SR MARKET, DH FOODS and InstaPay to SR AHMED are unchanged. |
| 4. Gates and integrity | PASS locally, CI pending | See Environment. SPINNEYS judgement: r20-store-middle and its assertTrue were added by this same PR and never existed on main. Flipping them to not_record / assertFalse narrows the PR own new behaviour and does not weaken any base test. The flip is recorded in DECISIONS, and the integrity tool reports OK. Accepted. |

## Findings
| Sev | Where | What | Repro |
|---|---|---|---|
| R3-MAJOR-1 | AutoRecordFamilies.kt card() / amountAgrees + second cardPurchaseEn regex | A WRONG AMOUNT is uploaded without a human. In the template used at MERCHANT for AMT, an amount-like word before the store number (AMOUNT, VALUE) makes the engine read the slot number as the amount. amountAgrees accepts ANY number in the clause instead of the AMT capture, so the family gate agrees, and the new store-number rule lets the slot pass. On main this is a review item, so a human sees it. | CIB: Your card ending 4821 was used at SHOP AMOUNT 500 for EGP 130.00 -> RECORD PURCHASE 50000 (main: REVIEW, suggestion 50000). The same with SHOP VALUE 500 -> 50000. Your card ending 4821 was used at SHOP AMOUNT 999 for EGP 1.00 -> RECORD PURCHASE 99900. The first template is fine: SHOP AMOUNT 500 there -> 13000. |
| R3-MINOR-1 | nameTokens before-side | Amount, balance, tax and quantity words before a trailing number pass. The recorded amount stays correct (13000) and nothing numeric is uploaded; only the label is odd. | Card wrapper with SHOP TOTAL 500 / AMOUNT 500 / AMT 500 / BALANCE 500 / BAL 500 / LIMIT 500 / VAT 14 / TAX 14 / TIP 50 / DISCOUNT 50 / INSTALLMENT 3 / MONTHS 12 / PTS 50 / QTY 3 / PHONE 123 / TEL 123 / CALL 123 / EXT 123 -> RECORD; HSBC SHOP TOTAL 500 -> RECORD; Arabic كارفور مبلغ 500 / رصيد 500 / قيمة 500 / اجمالي 500 / خصم 50 / بمبلغ 500 -> RECORD 13000. All were review on main. |
| R3-NIT-1 | pre-existing, not this PR | A status glued into one word is not seen by the status gate: SHOP ONHOLD records with or without a number. | card wrapper with SHOP ONHOLD; used at SHOP ONHOLD for EGP 130.00 |
| NIT-2 | pre-existing | at SHOP 5.00 and at SHOP 50.5 are RECORD on main and on head alike. | card wrapper |
| info | design | SPINNEYS 3 ZAYED and LE MERIDIEN 2 / SR MARKET 5 are review by design. Status words before the number stay blocked: PENDING, HOLD, VOID, VOIDED, REFUNDED, CANCEL, DECLINED, FAILED, RETURN, REVERSAL, مرتجع, معلقة plus a number -> not records. | |

## Exploratory (round 3)
- The 174 inputs from rounds 1-2 were re-run, plus 77 new card, HSBC and Arabic inputs, plus 13 amount-word variants: 264 in total.
- The new inputs were: CARREFOUR 12; SHOP POUNDS 50; SHOP BRANCH 50 / STORE 12 / TABLE 5 / QTY 3 / X 50 / TOTAL 500 / AMOUNT 500 / AMT 500 / BALANCE 500 / BAL 500 / LIMIT 500 / FEE 50 / FEES 50 / VAT 14 / TAX 14 / TIP 50 / DISCOUNT 50 / CASHBACK 50 / INSTALLMENT 3 / MONTHS 12 / PTS 50 / HOLD 50 / ONHOLD 50 / PENDING 50 / RETURN 50 / REFUNDED 50 / CANCEL 50 / VOID 50 / VOIDED 50 / DECLINED 50 / FAILED 50 / RETRY 3 / ATTEMPT 3 / REVERSAL 50; SHOP MID / TERMINAL / POS / BILL / RECEIPT / TRANSACTION / CVC / CVV2 / EXT / PHONE / TEL / CALL 123; SHOP OTP; SR MARKET 5; SHOP EGYPT 50; MCDONALDS 12; SHOP ATM / USER / AHMED / MOBILE 12; SHOP KM 5 / LTR 50 / KG 5.
- They also covered: HSBC SHOP TOTAL 500 and SHOP BRANCH 5; Arabic كارفور ج 50 / مبلغ 500 / رصيد 500 / خصم 50 / قيمة 500 / اجمالي 500 / حساب 123 / فاتورة 123 / طلب 123 / مرتجع 50 / معلقة 50 / جنيهات 50 / كود 123, and the mirrored forms مبلغ / قيمة / بمبلغ 500.
- On the second F1 template: used at SHOP TOTAL / AMOUNT / AMT / VALUE / SUM / PRICE 500 for EGP 130.00, used at SHOP AMOUNT 999 for EGP 1.00, and charged EGP 130.00 at SHOP TOTAL / AMOUNT 500.
- Problems: 2 new (R3-MAJOR-1, R3-MINOR-1) plus 1 pre-existing NIT

## Test integrity (round 3)
TEST_INTEGRITY: OK, asserts 101 -> 122, no skips, no deleted tests. The SPINNEYS flip is inside rows and assertions this PR itself added (see criterion 4), so it is not a weakening against base.

Loop 3 of 3 reached with an open MAJOR.

STATUS: FAIL

---

# Round 4 - 2026-10-27 - head 09e062cec16694f5cd73917e215713ad32bf3c4c, base origin/main ba4c57c

## Environment
- Fix commit 09e062c: every family regex now captures its own amount (AMTG with groups a1/a2; HSBC keeps amt). amountMatches requires that captured amount to equal the engine amount, and amountAgrees (any number in the clause) is deleted. InstaPay now checks the amount per regex match. The identifier set gained total, amount, amt, value, balance, bal, vat, tax, discount, qty, quantity, phone, mobile, tel and the Arabic مبلغ, قيمة, رصيد, ضريبة, خصم, كمية, هاتف, موبايل.
- bash scripts/make/check-fast.sh android sdlc -> exit 0, check-fast GREEN [android sdlc] in 98s; quality tests 145/145
- gradlew testDebugUnitTest --rerun at 09e062c -> CardStoreNumberTest 11/11, SmsParserTest 15/15, InstaPaySmsTest 2/2, 0 skipped
- bash scripts/quality/test-integrity-check.sh origin/main -> SmsParserTest asserts 101 -> 128, skips 0 -> 0, no deleted files, TEST_INTEGRITY: OK. amountAgrees was a private helper and no test referenced it.
- python scripts/quality/traceability.py --out tmp -> 403 REQs, 0 unknown, gate PASS
- gh pr checks 65, head 09e062c -> all checks pass, including Build artifacts; Android check-fast is PENDING
- Probe: the throwaway QaProbeTest was run with gradlew --rerun on head and on origin/main, over all 1900 fixture rows plus 305 exploratory inputs: the 264 from rounds 1-3 with the amount-word variants, plus 41 new amount and transfer inputs. The probe is deleted.

## Criteria
| Claim | Result | Evidence |
|---|---|---|
| 1. No auto-record with an amount other than the captured one | PASS | used at SHOP AMOUNT 500 / VALUE 500 for EGP 130.00 and used at SHOP AMOUNT 999 for EGP 1.00 -> REVIEW, where round 3 gave RECORD 50000 / 99900. used at SHOP 500 for EGP 130.00 -> RECORD 13000, correct. Two amounts (charged EGP 130.00 EGP 500.00 at SHOP; at SHOP for EGP 500.00; a second clause Amount EGP 500.00) -> review. Currency inside the slot (at SHOP 500 EGP) -> review. Arabic 130 جنيه 500 جنيه -> ambiguous_amount; Arabic لدي كارفور 500 من بطاقتك -> RECORD 13000, correct. InstaPay You sent EGP 10 to AHMED 500 and to AHMED ALI EGP 500 -> review. Instant out with a second number -> ambiguous_amount; instant in from احمد علي 500 -> RECORD 10000 on head and on main alike. Every head record in the exploratory set carries the amount its template captured: 13000, 10000, 125050, 125000, 150075 or 50000. |
| 2. Round-3 MINOR words are not records | PASS | Card wrapper with SHOP TOTAL / AMOUNT / AMT / VALUE / BALANCE / BAL 500, VAT / TAX 14, DISCOUNT 50, QTY 3, QUANTITY 3, PHONE / TEL 123, MOBILE 12; HSBC SHOP TOTAL 500 and SHOP AMOUNT 500; Arabic كارفور مبلغ 500 / رصيد 500 / قيمة 500 / خصم 50 -> all not records. |
| 3. No regression from the tightened amount check | PASS | 1900/1900 pre-existing fixture rows give identical outcomes (diff empty). round20: exactly the 4 intended positives RECORD (store-last-hsbc, store-last-cib, store-one-digit, store-ar), 31 not records. In the exploratory set, 0 records on main were lost or changed in kind, amount, label or last-4: the main-RECORD side of the diff is empty. |
| 4. Owner message, CARREFOUR 12, كارفور 12; numberless slots unchanged | PASS | HSBC and CIB x tails 0001/9999/7310 x 3 limits: 18/18 RECORD PURCHASE 13000 m=HARDEE S ELMERGHANY; the Arabic-Indic version is recorded too. CARREFOUR 12 -> 13000 m=CARREFOUR. كارفور 12 and كارفور ١٢ -> 13000 m=كارفور. LE PAIN QUOTIDIEN, LE MERIDIEN, LE MARCHE, YEN SUSHI, SR MARKET, DH FOODS, InstaPay to SR AHMED, Payment to LE MERIDIEN and from DH FOODS all match main. |
| 5. Gates | PASS locally, CI Android pending | See Environment. |

## Findings
| Sev | Where | What | Repro |
|---|---|---|---|
| R4-MINOR-1 | StatusGate identifierWords, before-side | Some words before a trailing number still pass. The amount is always the captured one and correct, and no numerals are uploaded; only the label is odd. | Card wrapper with SHOP LIMIT 500, TIP 50, INSTALLMENT 3, MONTHS 12, PTS 50, SUM 500, PRICE 500, COST 500, PAID 500, EXT 123, CALL 123, ATTEMPT 3, KM 5, LTR 50, KG 5 -> RECORD 13000. Arabic كارفور اجمالي 500 and كارفور بمبلغ 500 -> RECORD 13000. used at SHOP SUM / PRICE / COST 500 for EGP 130.00 -> RECORD 13000. |
| NIT (pre-existing) | StatusGate | A status glued into one word is missed: SHOP ONHOLD records on main and on head, with or without a number. This PR does not introduce it. | used at SHOP ONHOLD for EGP 130.00 -> RECORD on both |
| NIT (pre-existing) | slot | at SHOP 5.00 and at SHOP 50.5 are RECORD on main and on head alike. | card wrapper |

## Exploratory (round 4)
- 305 inputs: the 264 from rounds 1-3 with the amount-word variants, plus 41 new.
- New card inputs: SHOP QUANTITY 3, VALUE / SUM / PRICE / COST / CHARGE / PAID 500 in the card wrapper; used at SHOP 500 / COST 500 / CHARGE 500 for EGP 130.00; charged EGP 130.00 at SHOP 500 EGP; charged EGP 130.00 at SHOP for EGP 500.00; charged EGP 130.00 EGP 500.00 at SHOP; charged 130 EGP / EGP 1,250.50 / EGP1,250.50 / LE 130 at CARREFOUR 12; a second clause Amount EGP 500.00; used at CARREFOUR 12 for EGP 1,250.50 and for 130.00 EGP; HSBC SHOP AMOUNT 500.
- New Arabic inputs: 130 جنيه 500 جنيه, the mirrored forms with كارفور 12 and كارفور 500, مبلغ 1,250.50, all-Arabic-Indic numerals ١٢٥٠ / ٤٨٢١ / ١٢, the currency-first جنيه 130, and كارفور 500 / بمبلغ 500.
- New transfer inputs: instant in with 500 after the name and with 1,500.75; instant out with a second number, with 1,500.75 and with Arabic-Indic numerals; InstaPay to AHMED 500, 1,250.50, 500 EGP, to AHMED ALI EGP 500, تحويل 1,250, استلام 100, received successfully.
- Problems: 1 MINOR. No wrong amount, no hidden status introduced by this PR, no lost auto-record.

## Test integrity (round 4)
TEST_INTEGRITY: OK, asserts 101 -> 128, no skips, no deleted tests. The removed amountAgrees had no direct test, and no assertion was lost.

STATUS: PASS
