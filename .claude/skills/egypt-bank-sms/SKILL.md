---
name: egypt-bank-sms
description: Reference for parsing Egyptian bank, wallet and fintech SMS and push texts (template shapes per sender, sender IDs marked "verify current", OTP and promo negatives, direction keywords, balance capture, privacy rules). Loads automatically when SMS/notification parsing code, SMS eval datasets or SMS fixtures are touched; follow it when writing rules, templates, tests or eval cases.
user-invocable: false
paths:
  - "backend/**/parsing/**"
  - "backend/**/sms/**"
  - "backend/**/template*/**"
  - "backend/**/classif*/**"
  - "backend/src/main/resources/sms-templates/**"
  - "android/**/sms/**"
  - "android/**/ingest*/**"
  - "android/**/notification*/**"
  - "android/**/*Sms*"
  - "android/**/*Otp*"
  - "eval/datasets/sms*"
  - "eval/datasets/notifications*"
  - "fixtures/sms/**"
  - "fixtures/notifications/**"
---

# Egyptian bank and wallet SMS

Everything here is a template SHAPE for rules, tests and synthetic eval cases. Banks change wording without
notice; sender IDs below are marked **verify current** and must be confirmed against anonymized real fixtures
(`scripts/quality/anonymize-fixtures.py`) before a template goes `ACTIVE`. Never commit a raw personal SMS.

## 1. Pipeline position
`raw_message` (immutable) -> normalize (see `arabic-text`) -> **classify** (OTP/PROMO first, hard negatives) ->
rules by sender template (`sms_template.sender_pattern` + `regex` + `field_mapping`) -> LLM only when no
ACTIVE template matches -> enrich -> dedup. A rule match yields confidence >= 0.95; the LLM path is capped at 0.9.
Proposed templates (`status = PROPOSED`) are learned from LLM-parsed messages that repeat 3+ times with the same
shape; a human confirms in Settings -> Parsing rules.

## 2. Template shapes (English / Arabic variants exist for most senders)
| Sender (verify current) | Typical debit shape | Notes |
|---|---|---|
| `CIB` | `Dear Customer, your card ending with 1234 has been used for a purchase of EGP 350.00 at CARREFOUR MAADI on 12/03/2026 14:05. Available balance EGP 12,345.67` | ATM: `cash withdrawal of EGP ...`; foreign: `USD 20.00 ... equivalent EGP 1,010.00`; refunds `refund of ...` |
| `NBE` / `AlAhly` | `Transaction: Purchase of EGP 120.50 using card ending 5678 at TALABAT on 12-03-2026 at 14:05. Available balance: EGP 1,000.00` | Arabic: `تم شراء بمبلغ ... باستخدام البطاقة المنتهية بـ ...` |
| `BanqueMisr` / `BM` | `تم خصم مبلغ 250.00 جم من بطاقتكم المنتهية بـ 1234 لدى SEOUDI MARKET بتاريخ 12/03/2026 الرصيد المتاح 5,000.00 جم` | `جم` = EGP; credits `تم إيداع` |
| `QNB` (QNB Alahli -> QNB Egypt rebrand) | `QNB: Purchase EGP 90.00 Card **1234 at UBER 12/03 14:05 Avl Bal EGP 2,000.00` | Date may lack the year -> infer from `received_at`, roll back a year if in the future |
| `AAIB` | `Your card xxxx1234 was used for EGP 500.00 at AMAZON.EG on 12/03/2026 14:05. Available balance EGP 3,200.00` | |
| `BanqueDuCaire` / `BDC` | `عزيزي العميل، تم تنفيذ عملية شراء بمبلغ 75.00 جنيه من بطاقتك ****1234 لدى ... الرصيد المتاح ...` | |
| `InstaPay` | `You have received EGP 1,000.00 from AHMED M*** via InstaPay. Ref 1234567890` / `تم تحويل 500.00 جنيه إلى ... بنجاح` | TRANSFER by default; counterpart name is PII, store masked |
| `VodafoneCash` / `Vodafone` | `تم دفع 200 جنيه لـ FAWRY من محفظتك. رصيدك 1,300 جنيه. رقم العملية 123456` | wallet; `تم استلام` = credit |
| `OrangeCash` / `Orange` | `Orange Cash: You paid EGP 150.00 to ... Your balance is EGP 820.00. Trx 98765` | |
| `e& money` / `EtisalatCash` / `Etisalat` | `تم خصم 60 ج.م من محفظة e& money لشراء ... الرصيد 240 ج.م` | brand renamed from Etisalat Cash; both appear |
| `Fawry` / `FawryPay` | `Fawry: Payment of EGP 150.00 for Electricity bill ref 12345 successful. Fees EGP 5.00` | fee is a separate FEE entry; biller name -> category Bills |
| `valU` | `Your valU purchase of EGP 3,000.00 at B.TECH is approved. 12 installments of EGP 275.00 start 01/04/2026` | installment plan: expense now = installment or full? default full with `is_recurring` series of installments; configurable |
| `Telda` | `You paid EGP 85.00 at STARBUCKS with your Telda card ***1234. Balance EGP 414.00` | fintech card; low-value frequent |
| bank push notifications | same bodies, shorter, no sender id -> use the package name (`com.cib.mobile`, ...) as sender | Android `NotificationListener` path |

Field conventions: `amount` (first currency+number pair after the direction keyword), `currency` (`EGP`, `جم`,
`ج.م`, `جنيه`, `LE`, `L.E.`, `USD`, `$`, `EUR`, `SAR`, `AED`, `GBP`), `account_last4` (digits after
`ending with`, `ending`, `**`, `xxxx`, `****`, `المنتهية بـ`, `البطاقة رقم`), `merchant_raw` (text between `at`/`لدى`/`من`/`في`
and the date/`on`/`بتاريخ`), `date`/`time` (`dd/MM/yyyy`, `dd-MM-yyyy`, `dd/MM`, `HH:mm`, Arabic-Indic digits
possible), `balance` (after `Available balance`, `Avl Bal`, `Balance`, `الرصيد المتاح`, `رصيدك`, `الرصيد`),
`reference` (`Ref`, `Trx`, `رقم العملية`, `رقم المرجع`).

## 3. Hard negatives (classify BEFORE any amount extraction)
**OTP** (drop on device, never upload, OTP leak is a hard eval failure): any of
`OTP`, `one-time`, `one time password`, `passcode`, `verification code`, `code is`, `your code`, `PIN`,
`رمز`, `رمز التحقق`, `كود`, `كود التحقق`, `كلمة السر لمرة واحدة`, `لا تشارك`, `لا تشاركه`, `لا تقم بمشاركة`,
`لا تفصح`, `do not share`, `never share`, `valid for`, `صالح لمدة`, `expires in`, or a standalone 4-8 digit code
followed by "to confirm/complete" wording with no currency. Digits-only tokens of length 4-8 near those words are
never amounts.
**PROMO** (ignore): `offer`, `عرض`, `عروض`, `discount` without a card/amount pair, `خصم يصل`, `% off`, `cashback`
(as an offer, not a posted credit), `points`, `نقاط`, `rewards`, `اشترك`, `subscribe`, `T&C`, `terms apply`,
`الشروط والأحكام`, `call 19xxx`, `اتصل بـ`, URL-only messages, `installments up to`, `ارسل`/`send ... to`,
`congratulations`/`مبروك` with no transaction verbs, `*#` USSD codes. Promos often contain a currency amount —
the presence of a direction verb + card/account reference distinguishes a transaction.
Others: `statement is ready`, `كشف حساب`, `due date`/`تاريخ الاستحقاق` reminders (classify `UNKNOWN`, suggest a
recurring bill, no expense), `declined`/`تم رفض` (no expense; optional insight), `reversed` (see reversal).

## 4. Direction keywords -> type
| type | English | Arabic |
|---|---|---|
| EXPENSE (debit, purchase) | `purchase`, `used for`, `debited`, `payment of`, `paid`, `spent`, `charged`, `POS` | `خصم`, `تم خصم`, `شراء`, `تم شراء`, `دفع`, `تم دفع`, `مدفوعات`, `سداد` |
| INCOME / credit | `received`, `credited`, `deposit`, `salary`, `incoming transfer` | `إيداع`, `تم إيداع`, `استلام`, `تم استلام`, `وارد`, `تحويل وارد`, `راتب`, `مرتب` |
| REFUND | `refund`, `refunded`, `credit voucher` | `استرداد`, `تم استرداد`, `مرتجع`, `رد مبلغ` |
| reversal (cancel the matched expense, not income) | `reversal`, `reversed`, `cancelled transaction`, `void` | `عكس`, `تم عكس`, `إلغاء عملية`, `الغاء` |
| CASH_WITHDRAWAL | `ATM`, `cash withdrawal`, `withdrawn` | `سحب`, `تم سحب`, `سحب نقدي`, `ماكينة الصراف`, `ATM` |
| FEE (EXPENSE, category Fees & Bank Charges) | `fee`, `fees`, `charges`, `commission`, `stamp`, `VAT on` | `رسوم`, `مصاريف`, `عمولة`, `ضريبة` |
| TRANSFER (excluded from spend) | `transfer to`, `transferred`, `sent to`, `InstaPay to`, `own account` | `تحويل`, `تم تحويل`, `حولت`, `إلى حساب` |
Order matters: check reversal/refund before purchase (bodies often contain both words); `transfer` + `received`
= INCOME only if the counterpart is not a household member (else TRANSFER). Foreign currency: capture
`original_amount/currency` and the bank's EGP equivalent if printed; else convert with the day's rate.

## 5. Balance capture
Capture `balance` when present and keep it ONLY in the encrypted `raw_message.payload`; it is not an expense
field, never goes into LLM prompts (strip before the data block), never into logs or eval case `expected` beyond a
boolean `balance_present`. It is useful for account reconciliation (a running balance jump reveals missed SMS).

## 6. Sender handling (Android)
Allowlist by sender id AND by package for notifications; defaults = the table above; the user adds others in
onboarding from senders found in the inbox. Alphanumeric sender ids are not stable (`CIB`, `CIB-Bank`, `CIBEG`
appear) — match case-insensitively on normalized prefix, keep `sender_raw`. Messages from phone-number senders
are never auto-ingested (OTP/promo risk) unless the user allowlists the number.

## 7. Tests and eval cases
Each template: at least one English and one Arabic debit, one credit, one with Arabic-Indic digits, one with a
thousands separator, one foreign currency, one OTP negative and one promo negative in `eval/datasets/sms.jsonl`
(`tags: ["REQ-SMS-NNN", "<sender>", "<type>"]`, `source: synthetic`). The android unit tests for OTP/promo
detection are a hard failure gate (any OTP classified as a transaction fails the build).
