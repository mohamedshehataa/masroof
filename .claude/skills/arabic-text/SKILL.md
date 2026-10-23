---
name: arabic-text
description: Arabic and Egyptian-colloquial text handling for parsing and UI (Arabic-Indic and Persian digits, Arabic separators, tatweel and diacritics stripping, alef/yaa/taa-marbuta normalization, RTL marks and bidi isolation of numbers, money words like مية/ألف/k/ونص, tokenization pitfalls). Loads automatically when normalization or parsing code, web i18n files or Android string resources are touched.
user-invocable: false
paths:
  - "backend/**/normaliz*/**"
  - "backend/**/parsing/**"
  - "backend/**/text/**"
  - "backend/**/*Normaliz*"
  - "backend/**/*Tokeniz*"
  - "web/src/i18n/**"
  - "web/public/locales/**"
  - "web/src/**/*rtl*"
  - "web/src/**/*Rtl*"
  - "web/src/**/*format*"
  - "android/**/res/values*/strings.xml"
  - "android/**/i18n/**"
  - "android/**/*Format*"
  - "android/**/*Locale*"
  - "eval/datasets/whatsapp_text*"
  - "eval/datasets/commands*"
---

# Arabic text handling

## 1. Normalization pipeline (`normalize(text) -> NormalizedText{clean, original, map}`)
Apply in this order; keep the original text and an index map so error messages and highlights refer to the
original. The function must be idempotent (`normalize(normalize(x)) == normalize(x)`; jqwik property).
1. Unicode NFKC: folds presentation forms (U+FB50-FDFF, U+FE70-FEFF, e.g. ﻻ -> لا), fullwidth and superscript
   digits, ligatures. NFKC does NOT map Arabic-Indic digits to ASCII; do that explicitly next.
2. Digits: Arabic-Indic `٠١٢٣٤٥٦٧٨٩` (U+0660-0669) and Extended/Persian `۰۱۲۳۴۵۶۷۸۹` (U+06F0-06F9) -> `0-9`.
   Property: `digits(x) == digits(arabicIndic(x))` for all numbers; mixed-script numbers (`٢5٠`) are valid input.
3. Separators: Arabic decimal `٫` (U+066B) -> `.`; Arabic thousands `٬` (U+066C) -> `,`; Arabic percent `٪` (U+066A) -> `%`;
   Arabic comma `،` (U+060C) -> `,` as a word separator (NOT a thousands separator unless between digit groups of 3).
   Then resolve number grammar: `1,250` -> 1250; `1,250.50` -> 1250.50; `1.250` (one dot, exactly 3 digits after) ->
   1250 (Egyptian habit) but `1.25` -> 1.25; `1.250,00` -> 1250.00; `12,5` -> 12.5 (one comma, 1-2 digits after).
   Ambiguous forms lower confidence and keep both candidates for the LLM path.
4. Remove: tatweel `ـ` (U+0640); diacritics/harakat U+064B-U+065F, U+0670, U+06D6-U+06ED; zero-width
   U+200B-U+200D, U+2060, BOM U+FEFF; bidi controls U+200E/U+200F (LRM/RLM), U+061C (ALM), U+202A-U+202E
   (embeddings/overrides), U+2066-U+2069 (isolates). NBSP U+00A0 and narrow NBSP U+202F -> space.
5. Letters (for matching only, keep `clean_display` without this step): alef variants `أ إ آ ٱ` -> `ا`;
   `ى` -> `ي` (final yaa; Egyptians write both); `ة` -> `ه` ONLY in the matching key (so `جنيه`/`جنية` match)
   and never in display text; `ک` -> `ك`, `ی` -> `ي` (Persian forms); `ئ ؤ` kept. Collapse repeated letters
   used for emphasis (`جااامد` -> `جامد`) in the matching key only, max 2 repeats.
6. Whitespace: collapse to single spaces; trim; normalize `\r\n`.
7. Case-fold Latin (`toLowerCase(Locale.ROOT)`). Never use the default locale: Turkish-i bugs.

## 2. Money words (Egyptian colloquial) -> amount
| token(s) | value | examples |
|---|---|---|
| `مية`, `ميه`, `مائة` | 100 | `مية جنيه` = 100 |
| `ميتين`, `مئتين` | 200 | |
| `تلتمية`, `ثلاثمائة` / `ربعمية` / `خمسمية` / `ستمية` / `سبعمية` / `تمنمية` / `تسعمية` | 300 / 400 / 500 / 600 / 700 / 800 / 900 | spelling varies (`تلاتمية`, `خمسمية`, `خمسميه`) — match normalized key with fuzzy prefix |
| `ألف`, `الف`, `ألفين`, `الفين`, `تلاتلاف`/`٣ آلاف`, `N آلاف`/`الاف` | 1000, 2000, 3000, N*1000 | `٢ الف` = 2000 |
| `k`, `K`, `ك` suffix | *1000 | `2k`, `2.5k` = 2500, `٢ك` |
| `مليون` | 1,000,000 | over the ceiling -> PENDING_REVIEW |
| `ونص`, `و نص`, `ونصف` | + half of the last magnitude unit | `ميتين ونص` = 250; `ألف ونص` = 1500; `٣ ونص` = 3.5; `خمسة جنيه ونص` = 5.5; `تلاتة ونص كيلو` is a quantity, not money |
| `وربع` / `وتلت` / `وتلات ارباع` | + 1/4, 1/3, 3/4 of the unit | `ميتين وربع` = 225 |
| `نص` alone before a unit | 0.5 of the unit | `نص جنيه` = 0.50 |
| `صاغ`, `قرش`, `قروش`, `تعريفة` (rare) | piasters | `خمسة وسبعين صاغ` = 0.75 EGP |
Currency words: `جنيه`, `جنية`, `جنيهات`, `جنيهًا`, `ج.م`, `ج`, `LE`, `L.E.`, `EGP`, `pounds`, `geneh`, `gnee`, `دولار`/`$`/`USD`,
`يورو`/`€`/`EUR`, `ريال`/`SAR`, `درهم`/`AED`, `جنيه استرليني`/`GBP`. Default currency is the household base (EGP)
when none is given. "ونص" rule: it applies to the LAST magnitude word (hundreds/thousands) if the amount is a
round multiple of that unit; otherwise to the unit (= .5). Ambiguity -> confidence <= 0.7, ask a clarification via
the WhatsApp reply (`needs_clarification`).
Number words 1-99: `واحد`..`تسعة`, `عشرة`, `حداشر`, `اتناشر`, `تلتاشر`..., `عشرين`, `تلاتين`, `اربعين`, `خمسين`, `ستين`, `سبعين`, `تمانين`, `تسعين`,
composition `خمسة وعشرين` = 25 (units before tens, joined by `و`).

## 3. RTL and bidi in the UI
- Numbers and Latin merchant names inside Arabic sentences must be isolated so neighbouring punctuation and signs
  do not jump: wrap with FSI `⁨` ... PDI `⁩` (or LRI `⁦`), never with LRM hacks around every char.
  Web: `<bdi>` element or `unicode-bidi: isolate` / `plaintext`; Compose: `BidiFormatter.getInstance().unicodeWrap(text)`
  or `TextDirection` on the amount `Text`. Format amount and currency as ONE isolated run (`١٬٢٥٠ ج.م`) so the
  sign/currency stays attached.
- Layout mirroring: `dir="rtl"` on `<html>` when `lang="ar"`; use logical CSS properties (`margin-inline-start`,
  `padding-inline`, `inset-inline`) and `start`/`end` alignment, never `left`/`right`. Compose: `LayoutDirection`
  from the locale, `Arrangement.Start`, `PaddingValues(start=...)`; icons with direction (back arrows, send,
  chevrons, swipe hints, bar charts baseline) are mirrored with `Modifier.mirror()`/`autoMirrored` or an RTL variant;
  phone numbers, times, chart axes stay LTR inside an isolate.
- Digits setting: `Western` (default) or `Arabic-Indic`; apply with `NumberFormat`/`Intl.NumberFormat('ar-EG', {numberingSystem: 'arab'})`
  only at display; inputs accept both and are normalized (section 1).
- Fonts: IBM Plex Sans Arabic + Inter (or equivalents) with `tnum`; Arabic line height >= 1.5; avoid letter-spacing on Arabic.
- Egyptian Arabic in UI strings: colloquial, short, second person (`ضيف مصروف`, `محتاج مراجعة (٣)`), bilingual
  categories from PROMPT.md §8; no machine-translated Modern Standard Arabic unless it is a legal/security text.
- Screenshot tests (Roborazzi/Playwright) cover LTR and RTL; text overflow and truncation are checked with the
  longest Arabic strings and font scale 1.5.

## 4. Tokenization pitfalls
- Regex `\b` does not match around Arabic letters in Java unless `Pattern.UNICODE_CHARACTER_CLASS` (`(?U)`) is set;
  in JS use `\p{L}`/`\p{N}` with the `u` flag; in Kotlin `Regex(..., RegexOption.IGNORE_CASE)` plus `(?U)`. Prefer
  explicit `[\p{L}\p{N}]` classes and whitespace/punctuation splitting over `\w`.
- Clitic prefixes attach to words: `بـ`/`ب` (for/with: `ب٢٠٠`, `بالفيزا`), `و` (and: `و٥٠`), `لـ`/`ل` (to/for: `للسوبر`),
  `ال` (the), `ع`/`على` (`عالفيزا`). Strip `ب`/`و`/`ل` only when the remainder is a number, a currency word or a
  known merchant/category; otherwise keep (`بنزين` is not `ب+نزين`).
- Mixed scripts in one token (`٢٠٠جنيه`, `200ج`, `2k`) must split at script/digit boundaries before matching.
- Visual vs logical order: incoming text is logical order; never reverse strings for "RTL". Do not sort Arabic with
  code-point order; use `Collator.getInstance(Locale("ar","EG"))`.
- `ة`/`ه` and `ى`/`ي` variants, `أ/ا`, repeated letters and missing spaces (`مية جنيه` vs `ميةجنيه`) break exact
  matches: always match on the normalized key (section 1.5) and compare merchants with normalized + fuzzy
  (Jaro-Winkler >= 0.9) + transliteration alias table (`كارفور` <-> `CARREFOUR`, `سعودي` <-> `SEOUDI`).
- Dates: `١٢/٣` is day/month in Egypt; Arabic month names (`مارس`, `أبريل`...) and `النهاردة`/`امبارح`/`الأسبوع اللي فات`
  are relative to the household's Africa/Cairo "today".
- Emoji and skin-tone modifiers (U+1F3FB-1F3FF), `ZWJ` sequences: keep in `original`, strip from the matching key.
- Length: WhatsApp messages up to 4096 chars; OCR up to 20k. Truncate for logs (never above DEBUG anyway) and cap
  LLM input at the configured limit with a `truncated` flag.
