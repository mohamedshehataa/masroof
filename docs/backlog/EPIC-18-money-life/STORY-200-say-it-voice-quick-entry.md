---
id: STORY-200
epic: EPIC-18
title: Say it - add an entry by voice or one line of text, with income and currency on the Add form
phase: 7
status: DONE
risk_tier: high
size: M
modules: [android]
depends_on: []
reqs: [REQ-PARSE-098, REQ-PARSE-099, REQ-AND-144, REQ-EXP-052]
implements: ["Say teardown feature 3 (voice entry), with a confirm step", "ADR-0009 D1 (on-device, no network)"]
owner_agent: android-engineer
---

# STORY-200 - Say it - add an entry by voice or one line of text, with income and currency on the Add form

## Goal
Paid in cash? Mohamed taps the mic and says "150 pounds on fuel" or "دفعت مية وخمسين في كارفور امبارح", or types the same
line, and the Add form opens already filled (amount, currency, type, category, merchant, date). He checks it and taps
Save. Nothing is saved without that tap, which is where Masroof is better than Say: a misheard amount never becomes an
entry silently.

## Requirements and acceptance criteria
### REQ-PARSE-098 - English and Franco lines become a draft entry
**Given** the line **When** parsed **Then** the draft is:
| line | amount | cur | type | category | merchant | date |
|---|---|---|---|---|---|---|
| `150 pounds on fuel` | 15000 | EGP | EXPENSE | Fuel | - | today |
| `spent 85.5 at talabat yesterday` | 8550 | EGP | EXPENSE | (merchant default or none) | talabat | yesterday |
| `20 dollars netflix` | 2000 | USD | EXPENSE | Subscriptions | netflix | today |
| `received 12,000 salary` | 1200000 | EGP | INCOME | - | - | today |
| `1.5k groceries` | 150000 | EGP | EXPENSE | Groceries | - | today |
| `200 le uber on 3 oct` | 20000 | EGP | EXPENSE | Transport | uber | 2026-10-03 |
Amounts are parsed to minor units with integer arithmetic only (`85.5` -> 8550, `1,250.75` -> 125075, `1.5k` -> 150000);
currency words: pound(s), le, egp, ج, جنيه -> EGP; dollar(s), usd, $ -> USD; euro(s) -> EUR; riyal(s) -> SAR; dirham(s)
-> AED. Category comes from a keyword table over English and Arabic category names plus synonyms (fuel, gas, benzine,
uber/careem -> Transport, talabat/elmenus -> Dining out, carrefour/spinneys -> Groceries, netflix/shahid/spotify ->
Subscriptions). Dates: today, yesterday, "on 3 oct", "last friday" (Cairo).
Unhappy path: **Given** a line with no number (`fuel`) **Then** the draft has no amount and the form shows "Say or type an
amount" on the amount field. **Given** two numbers (`2 coffees 90`) **Then** the amount is the number next to a currency
word or, failing that, the last number; the other text goes to the note.
Unhappy path: **Given** an amount above 1,000,000,000,000 minor units or `0` **Then** no amount is set.

### REQ-PARSE-099 - Egyptian Arabic and mixed lines, including number words
**Given** the line **When** parsed **Then**:
| line | amount | cur | type | category | merchant | date |
|---|---|---|---|---|---|---|
| `١٥٠ جنيه بنزين` | 15000 | EGP | EXPENSE | Fuel | - | today |
| `دفعت مية وخمسين في كارفور امبارح` | 15000 | EGP | EXPENSE | Groceries | كارفور | yesterday |
| `خمسين جنيه مواصلات` | 5000 | EGP | EXPENSE | Transport | - | today |
| `ألفين ونص إيجار` | 250000 | EGP | EXPENSE | Rent & housing | - | today |
| `قبضت ٢٠ ألف مرتب` | 2000000 | EGP | INCOME | - | - | today |
| `300 جنيه talabat` | 30000 | EGP | EXPENSE | Dining out | talabat | today |
Number words: واحد..عشرة, حداشر..تسعتاشر, عشرين..تسعين, مية/ميه, ميتين, تلتمية..تسعمية, ألف/الف, ألفين, آلاف, مليون, نص
(half of the preceding unit), ربع, joined by و. Arabic-Indic and Persian digits are accepted; Arabic letters are
normalised (أ/إ/آ -> ا, ى -> ي, ة -> ه for matching only).
Unhappy path: **Given** an unparseable number phrase **Then** no amount is set and the words stay in the note.

### REQ-AND-144 - Mic flow: on-device recognition, permission on tap, confirm before save
**Given** the Add form **When** the user taps the mic **Then** RECORD_AUDIO is requested only then (rationale text first
if needed), recognition starts with the app language (`ar-EG` or `en-US`), preferring the on-device recognizer
(`SpeechRecognizer.createOnDeviceSpeechRecognizer` on API 31+, else `EXTRA_PREFER_OFFLINE`), a listening sheet shows a
level indicator and Stop; the final text fills the quick-text field and the form fields. No audio is stored by the app.
A quick-text field on the Add form ("Type it: 150 fuel") runs the same parser on Done.
Unhappy path: **Given** permission denied (or denied forever) **Then** the mic shows a short explanation with a Settings
link and the quick-text field still works. **Given** no recognizer on the device **Then** the mic is hidden.
**Given** recognition error or silence **Then** "Didn't catch that - try again or type it", nothing is filled.
Unhappy path: **Given** a filled draft **When** the user presses Back **Then** nothing is saved.

### REQ-EXP-052 - The Add form can record income and other currencies
**Given** the Add form **When** it opens **Then** an Expense / Income switch (default Expense) and a currency chip (default
EGP; EGP, USD, EUR, SAR, AED, GBP, QAR - KWD dropped, see DECISIONS) are shown; saving writes `type` INCOME or EXPENSE and the chosen currency.
A non-EGP entry shows the existing "not included in EGP totals" hint under the amount.
Unhappy path: **Given** Income is chosen **Then** the category picker shows no spending budget hint and the entry never
counts as spending (existing D2 rule).

## UX states (UI stories only)
Add form: mic button in the amount row, quick-text field, listening sheet (listening, processing, error), filled form
with the parsed fields highlighted for 2 s. Home gets a "Say it" shortcut that opens Add with the mic started
(STORY-206 moves it into the quick actions). RTL, dark, Arabic-Indic digits.

## Contract impact
None.

## Data / migration impact
None (existing `expense.type` and `currency`).

## Security / privacy / LLM-safety impact
New permission RECORD_AUDIO (runtime, requested on tap only; security reviewer). Audio and transcripts are never stored
or logged above DEBUG; on-device recognition preferred. No network from the app.

## Eval impact
None for the LLM harness; table-driven parser tests (at least 50 lines across both tables plus negatives).

## Design notes (architect)
Pure-Kotlin `ingest/quick/QuickEntryParser` (no Android types) -> `EntryDraft`; `ui/add` gets a `VoiceInput` wrapper
around `SpeechRecognizer` behind an interface so tests use a fake. Category resolution goes through the seeded category
ids and names, never hard-coded row ids.

## Dependencies
None.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size: M (parser ~300, voice + form ~200, tests ~400)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-11-08 | 1 spec | orchestrator | READY - EPIC-18 (Say teardown), speed mode |
| 2026-11-08 | 5 implement | android-engineer | READY_FOR_VERIFY 30cae43; fix rounds 04997c1, d84ae28, 6e1e5ae, 57235e8, 9bcf7eb |
| 2026-11-08 | 6 verify | qa-verifier, security-reviewer | FAIL loops 1-5 (each fixed), PASS loop 6 (796 tests) - past the 3-loop limit by owner rule; security APPROVE |
| 2026-11-08 | 8 integrate | orchestrator | DONE |
