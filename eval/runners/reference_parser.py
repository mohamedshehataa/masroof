"""Deterministic reference parser for the Masroof eval harness (adapter `reference`).

This is NOT the product parser. It is a small, dependency-free stand-in that:
  * normalises Unicode / Arabic-Indic digits / separators / multipliers,
  * classifies OTP, promo, command, chit-chat, expense-like inputs,
  * extracts amount, currency, merchant, category, date/time, payment method, last-4,
  * parses receipt OCR stand-ins into line items,
  * scores multi-source sequences for the dedup suite.

It exists so that `make eval` has a real pipeline to measure (plumbing, metrics, regression and
hard gates) before the LLM-backed parser exists, and so the SDLC self-test can break something
observable (e.g. remove a key from DIGIT_MAP) and watch the gate fail.

Everything here treats message content strictly as DATA. Instruction-like clauses are stripped
before extraction and never change the parse (S10.5).
"""

from __future__ import annotations

import datetime as dt
import hashlib
import re
import unicodedata
from dataclasses import dataclass, field
from typing import Any, Optional

# --------------------------------------------------------------------------------------------
# Normalisation
# --------------------------------------------------------------------------------------------

#: Arabic-Indic (U+0660..U+0669) and Extended Arabic-Indic (U+06F0..U+06F9) digits -> ASCII.
DIGIT_MAP: dict[str, str] = {
    "٠": "0", "١": "1", "٢": "2", "٣": "3", "٤": "4", "٥": "5", "٦": "6", "٧": "7", "٨": "8", "٩": "9",
    "۰": "0", "۱": "1", "۲": "2", "۳": "3", "۴": "4", "۵": "5", "۶": "6", "۷": "7", "۸": "8", "۹": "9",
}

_INVISIBLE_RE = re.compile(
    "[\u200b-\u200f\u202a-\u202e\u2060-\u2064\u2066-\u2069\ufeff\u00ad\u061c]"
)
_HTML_TAG_RE = re.compile(r"<[^>]{1,200}>")
_URL_RE = re.compile(r"https?://\S+|www\.\S+")
_TASHKEEL_RE = re.compile("[ؐ-ًؚ-ٰٟۖ-ۭـ]")

CAIRO_TZ_FALLBACK_STANDARD = dt.timedelta(hours=2)
CAIRO_TZ_FALLBACK_DST = dt.timedelta(hours=3)


def normalize_text(text: str) -> str:
    """NFKC, strip invisibles/HTML/URLs, map digits, unify separators and whitespace."""
    text = unicodedata.normalize("NFKC", text or "")
    text = _INVISIBLE_RE.sub("", text)
    text = _HTML_TAG_RE.sub(" ", text)
    text = _URL_RE.sub(" ", text)
    text = "".join(DIGIT_MAP.get(ch, ch) for ch in text)
    text = text.replace("٬", ",").replace("٫", ".").replace("،", ",")
    text = re.sub(r"[ \t ]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def normalize_arabic(text: str) -> str:
    """Lower-case + fold Arabic letter variants so keyword tables stay small."""
    text = _TASHKEEL_RE.sub("", text.lower())
    for src, dst in (("أ", "ا"), ("إ", "ا"), ("آ", "ا"), ("ة", "ه"), ("ى", "ي"), ("ؤ", "و"), ("ئ", "ي")):
        text = text.replace(src, dst)
    return text


def cairo_offset(utc_dt: dt.datetime) -> dt.timedelta:
    """Africa/Cairo offset. Uses zoneinfo when tzdata is available, else the 2023+ Egyptian DST
    rule (last Friday of April 00:00 -> last Thursday of October 24:00)."""
    try:
        from zoneinfo import ZoneInfo  # noqa: PLC0415

        local = utc_dt.astimezone(ZoneInfo("Africa/Cairo"))
        return local.utcoffset() or CAIRO_TZ_FALLBACK_STANDARD
    except Exception:  # noqa: BLE001  (ZoneInfoNotFoundError on Windows without tzdata)
        year = utc_dt.year
        april_last_friday = _last_weekday(year, 4, 4)  # Friday = 4
        october_last_thursday = _last_weekday(year, 10, 3)  # Thursday = 3
        start = dt.datetime(year, 4, april_last_friday.day, 0, 0, tzinfo=dt.timezone.utc) - CAIRO_TZ_FALLBACK_STANDARD
        end = dt.datetime(year, 10, october_last_thursday.day, 23, 59, 59, tzinfo=dt.timezone.utc) - CAIRO_TZ_FALLBACK_DST
        return CAIRO_TZ_FALLBACK_DST if start <= utc_dt <= end else CAIRO_TZ_FALLBACK_STANDARD


def _last_weekday(year: int, month: int, weekday: int) -> dt.date:
    day = dt.date(year, month + 1, 1) - dt.timedelta(days=1) if month < 12 else dt.date(year, 12, 31)
    while day.weekday() != weekday:
        day -= dt.timedelta(days=1)
    return day


def parse_iso(ts: Optional[str]) -> Optional[dt.datetime]:
    if not ts:
        return None
    ts = ts.replace("Z", "+00:00")
    parsed = dt.datetime.fromisoformat(ts)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


def to_cairo(utc_dt: dt.datetime) -> dt.datetime:
    return utc_dt + cairo_offset(utc_dt)


# --------------------------------------------------------------------------------------------
# Keyword tables
# --------------------------------------------------------------------------------------------

CURRENCY_TOKENS: dict[str, str] = {
    "egp": "EGP", "le": "EGP", "l.e": "EGP", "l.e.": "EGP", "جنيه": "EGP", "جنيها": "EGP", "جنيهات": "EGP",
    "ج.م": "EGP", "جم": "EGP", "pounds": "EGP", "pound": "EGP", "جنيه مصري": "EGP",
    "usd": "USD", "$": "USD", "دولار": "USD", "دولار امريكي": "USD", "dollars": "USD", "dollar": "USD",
    "eur": "EUR", "€": "EUR", "يورو": "EUR", "euro": "EUR", "euros": "EUR",
    "gbp": "GBP", "£": "GBP", "استرليني": "GBP",
    "sar": "SAR", "ريال": "SAR", "aed": "AED", "درهم": "AED", "kwd": "KWD",
}
_CURRENCY_BEFORE_RE = re.compile(
    r"(egp|usd|eur|gbp|sar|aed|kwd|le|l\.e\.?|\$|€|£|جنيه|دولار|يورو|ريال|درهم)\s*$", re.IGNORECASE
)
_CURRENCY_AFTER_RE = re.compile(
    r"^\s*(egp|usd|eur|gbp|sar|aed|kwd|le\b|l\.e\.?|\$|€|£|جنيه مصري|جنيهات|جنيها|جنيه|ج\.م|جم\b|pounds?|dollars?|euros?|دولار امريكي|دولار|يورو|ريال|درهم|استرليني)",
    re.IGNORECASE,
)

MULTIPLIERS: dict[str, int] = {"k": 1000, "الف": 1000, "ألف": 1000, "آلاف": 1000, "الاف": 1000, "ميه": 100, "مية": 100}
_MULTIPLIER_AFTER_RE = re.compile(r"^\s*(k\b|K\b|الف|ألف|آلاف|الاف|مية|ميه)", re.IGNORECASE)

OTP_KEYWORDS = (
    "otp", "one-time password", "one time password", "one-time passcode", "verification code", "is your code",
    "your code is", "passcode", "activation code", "security code", "do not share", "don't share", "never share",
    "رمز التحقق", "كود التحقق", "كود التفعيل", "رمز التفعيل", "رمز سري", "كلمه المرور", "كلمه السر", "لا تشارك",
    "لا تشاركه", "رمز الدخول", "رمز التاكيد", "كود التاكيد", "الرمز السري",
)
PROMO_KEYWORDS = (
    "offer", "offers", "discount", "sale", "promo", "apply now", "download the app", "t&c", "terms and conditions",
    "valid till", "valid until", "shop now", "pay later", "% off", "interest", "loan", "win ", "congratulations",
    "rate your", "urgent", "account will be", "click here", "limited time", "subscribe", "unsubscribe", "cashback",
    "عرض", "عروض", "هديه", "العرض ساري", "تطبق الشروط", "الشروط والاحكام", "قرض", "فائده", "اشحن", "اربح", "مبروك",
    "حمل التطبيق", "ساري حتي", "خصم %", "تقسيط بدون", "استرداد نقدي", "اشترك الان",
)
TRANSACTION_KEYWORDS = (
    "was used", "debited", "purchase", "payment of", "paid", "withdrawal", "withdrawn", "received", "credited",
    "refund", "reversed", "reversal", "transfer", "transferred", "sent", "deducted", "installment", "fee",
    "charged", "successful", "تم خصم", "خصم مبلغ", "تم سحب", "تم دفع", "تم استخدام", "تم تحويل", "تم ايداع",
    "تم استلام", "استرداد", "تم رد", "بمبلغ", "مبلغ", "عمليه", "تحويل", "دفعت",
)
REFUND_KEYWORDS = ("refund", "reversal", "reversed", "استرداد", "رد مبلغ", "تم رد", "مرتجع", "عكس العمليه", "الغاء العمليه", "استرجع", "رجعولي", "مرتجعات")
WITHDRAWAL_KEYWORDS = ("atm", "cash withdrawal", "withdrawal", "withdrawn", "سحب نقدي", "تم سحب", "ماكينه الصراف", "الصراف الالي", "ماكينه")
TRANSFER_KEYWORDS = ("transfer", "transferred", "تحويل", "حولت", "between your accounts")
INCOME_KEYWORDS = ("received", "credited to your", "deposit", "deposited", "salary", "تم ايداع", "ايداع", "تم استلام", "استلمت", "تم اضافه", "وصلك", "راتب", "مرتب")
FEE_KEYWORDS = ("fee", "fees", "charges", "رسوم", "مصاريف اداريه", "مصاريف")

INSTRUCTION_PATTERNS = [re.compile(p, re.IGNORECASE) for p in (
    r"ignore (all |any |the )?(previous|prior|above|earlier|the) (instructions|rules|messages)",
    r"\bdisregard\b", r"\bsystem\s*:", r"\[system\]", r"\bassistant\b", r"system prompt", r"you are now", r"from now on", r"\breclassify\b",
    r"new instructions", r"forget (the|all|your|everything)", r"\boverride\b", r"\bdan\b", r"jailbreak",
    r"\b(mark|record|log|register|book|enter|treat|consider|classify|set)\b.{0,40}\b(as |income|transfer|refund|category|total)",
    r"call (the )?tool", r"use (the )?tool", r"function[_ ]call", r"delete[_ ]all", r"change the category",
    r"add a new category", r"set (the )?total to", r"actual expense is", r"the above is a test", r"print your",
    r"تجاهل", r"التعليمات", r"سجل .{0,30}(كدخل|دخل)", r"اعتبر", r"غير الكاتيجوري", r"ملاحظه للنظام", r"يا بوت",
    r"انت الان", r"من دلوقتي", r"امسح كل", r"```", r"\{\s*\"",
)]

COMMAND_TABLE: dict[str, tuple[str, ...]] = {
    "TOTAL": ("total", "totals", "الاجمالي", "اجمالي", "المجموع", "صرفنا كام", "كام صرفنا", "صرفنا قد ايه", "how much did we spend",
              "how much have we spent", "spent this month", "month total", "الشهر ده كام"),
    "CATEGORY_SPEND": ("كام علي", "how much on", "how much did we spend on", "spent on", "spend on", "كام ع "),
    "UNDO": ("undo", "امسح اخر", "الغي اخر", "الغي", "delete last", "remove last", "تراجع", "cancel last", "امسح"),
    "EDIT_LAST": ("edit last", "عدل اخر", "غير اخر", "change last", "fix last", "عدل الاخيره"),
    "BUDGET": ("budget", "budgets", "الميزانيه", "ميزانيه", "بدجت", "budget status"),
    "PENDING": ("pending", "المعلق", "معلق", "المعلقه", "في الانتظار", "review", "للمراجعه", "مراجعه"),
    "SUMMARY": ("weekly summary", "week", "weekly", "summary", "ملخص الاسبوع", "ملخص", "الاسبوع", "this week"),
    "HELP": ("help", "مساعده", "ساعدني", "commands", "الاوامر", "how to use", "ازاي استخدم"),
}
_FUZZY_COMMAND_WORDS = {w: c for c, words in COMMAND_TABLE.items() for w in words if re.fullmatch(r"[a-z]{4,}", w)}

#: (alias substring in normalised text, merchant, category). Longest aliases first.
MERCHANT_ALIASES: list[tuple[str, str, str]] = sorted([
    ("amazon web services", "Amazon Web Services", "Subscriptions"),
    ("carrefour", "Carrefour", "Groceries"), ("كارفور", "Carrefour", "Groceries"),
    ("seoudi", "Seoudi", "Groceries"), ("سعودي", "Seoudi", "Groceries"),
    ("spinneys", "Spinneys", "Groceries"), ("سبينيس", "Spinneys", "Groceries"),
    ("metro market", "Metro", "Groceries"), ("kazyon", "Kazyon", "Groceries"), ("breadfast", "Breadfast", "Groceries"),
    ("gourmet", "Gourmet", "Groceries"),
    ("netflix", "Netflix", "Subscriptions"), ("نتفلكس", "Netflix", "Subscriptions"),
    ("spotify", "Spotify", "Subscriptions"), ("anghami", "Anghami", "Subscriptions"),
    ("icloud", "Apple", "Subscriptions"), ("apple.com", "Apple", "Subscriptions"),
    ("uber", "Uber", "Transport"), ("اوبر", "Uber", "Transport"), ("careem", "Careem", "Transport"), ("كريم", "Careem", "Transport"),
    ("swvl", "Swvl", "Transport"),
    ("talabat", "Talabat", "Dining & Delivery"), ("طلبات", "Talabat", "Dining & Delivery"),
    ("starbucks", "Starbucks", "Dining & Delivery"), ("ستاربكس", "Starbucks", "Dining & Delivery"),
    ("mcdonald", "McDonald's", "Dining & Delivery"), ("costa coffee", "Costa Coffee", "Dining & Delivery"),
    ("cafe supreme", "Cafe Supreme", "Dining & Delivery"), ("cilantro", "Cilantro", "Dining & Delivery"),
    ("el ezaby", "El Ezaby", "Health"), ("ezaby", "El Ezaby", "Health"), ("العزبي", "El Ezaby", "Health"),
    ("seif pharmacy", "Seif Pharmacy", "Health"), ("19011", "Seif Pharmacy", "Health"),
    ("b.tech", "B.Tech", "Shopping"), ("btech", "B.Tech", "Shopping"), ("بي تك", "B.Tech", "Shopping"),
    ("amazon", "Amazon", "Shopping"), ("امازون", "Amazon", "Shopping"), ("jumia", "Jumia", "Shopping"), ("جوميا", "Jumia", "Shopping"),
    ("noon", "Noon", "Shopping"),
    ("zara", "Zara", "Clothing"), ("h&m", "H&M", "Clothing"), ("lc waikiki", "LC Waikiki", "Clothing"),
    ("ikea", "IKEA", "Home & Furniture"), ("ايكيا", "IKEA", "Home & Furniture"),
    ("vodafone", "Vodafone", "Bills & Utilities"), ("فودافون", "Vodafone", "Bills & Utilities"),
    ("orange", "Orange", "Bills & Utilities"), ("اورنج", "Orange", "Bills & Utilities"),
    ("etisalat", "Etisalat", "Bills & Utilities"), ("اتصالات", "Etisalat", "Bills & Utilities"),
    ("we internet", "WE", "Bills & Utilities"), ("we telecom", "WE", "Bills & Utilities"), ("telecom egypt", "WE", "Bills & Utilities"),
    ("شركه الكهرباء", "Electricity Company", "Bills & Utilities"), ("الكهرباء", "Electricity Company", "Bills & Utilities"),
    ("كهربا", "Electricity Company", "Bills & Utilities"), ("electricity", "Electricity Company", "Bills & Utilities"),
    ("wataniya", "Wataniya", "Fuel"), ("الوطنيه", "Wataniya", "Fuel"), ("chillout", "Chillout", "Fuel"), ("mobil", "Mobil", "Fuel"),
    ("total energies", "TotalEnergies", "Fuel"),
    ("gold's gym", "Gold's Gym", "Health"), ("cleopatra hospital", "Cleopatra Hospital", "Health"),
    ("egyptair", "EgyptAir", "Travel"), ("مصر للطيران", "EgyptAir", "Travel"),
    ("vox cinema", "VOX Cinemas", "Entertainment"),
], key=lambda t: -len(t[0]))

#: (keyword, category) for messages without a known merchant. Checked in order.
CATEGORY_KEYWORDS: list[tuple[str, str]] = [
    ("بنزين", "Fuel"), ("fuel", "Fuel"), ("petrol", "Fuel"), ("gas station", "Fuel"), ("octane", "Fuel"), ("بنزينه", "Fuel"),
    ("car wash", "Car"), ("غسيل العربيه", "Car"), ("mechanic", "Car"), ("ميكانيكي", "Car"), ("tires", "Car"), ("عربيه", "Car"), ("oil change", "Car"),
    ("supermarket", "Groceries"), ("سوبر ماركت", "Groceries"), ("groceries", "Groceries"), ("grocery", "Groceries"), ("بقاله", "Groceries"),
    ("خضار", "Groceries"), ("عيش", "Groceries"), ("فول", "Groceries"), ("فاكهه", "Groceries"), ("لحمه", "Groceries"), ("فراخ", "Groceries"), ("market", "Groceries"),
    ("dinner", "Dining & Delivery"), ("lunch", "Dining & Delivery"), ("breakfast", "Dining & Delivery"), ("coffee", "Dining & Delivery"),
    ("pizza", "Dining & Delivery"), ("burger", "Dining & Delivery"), ("restaurant", "Dining & Delivery"), ("عشا", "Dining & Delivery"),
    ("غدا", "Dining & Delivery"), ("فطار", "Dining & Delivery"), ("قهوه", "Dining & Delivery"), ("اكل برا", "Dining & Delivery"), ("مطعم", "Dining & Delivery"),
    ("delivery", "Dining & Delivery"), ("كشري", "Dining & Delivery"), ("شاورما", "Dining & Delivery"),
    ("taxi", "Transport"), ("تاكسي", "Transport"), ("مواصلات", "Transport"), ("ride", "Transport"), ("bus", "Transport"), ("ميكروباص", "Transport"), ("مترو", "Transport"),
    ("كهربا", "Bills & Utilities"), ("مياه", "Bills & Utilities"), ("غاز", "Bills & Utilities"), ("انترنت", "Bills & Utilities"), ("internet", "Bills & Utilities"),
    ("electricity", "Bills & Utilities"), ("water bill", "Bills & Utilities"), ("موبايل", "Bills & Utilities"), ("تلفون", "Bills & Utilities"),
    ("تلفونات", "Bills & Utilities"), ("فاتوره", "Bills & Utilities"), ("bill", "Bills & Utilities"), ("recharge", "Bills & Utilities"), ("شحن رصيد", "Bills & Utilities"),
    ("rent", "Rent/Housing"), ("ايجار", "Rent/Housing"), ("الايجار", "Rent/Housing"), ("سكن", "Rent/Housing"),
    ("موبيليا", "Home & Furniture"), ("furniture", "Home & Furniture"), ("عفش", "Home & Furniture"), ("sofa", "Home & Furniture"), ("كنبه", "Home & Furniture"),
    ("دكتور", "Health"), ("doctor", "Health"), ("pharmacy", "Health"), ("صيدليه", "Health"), ("دوا", "Health"), ("medicine", "Health"),
    ("hospital", "Health"), ("مستشفي", "Health"), ("gym", "Health"), ("جيم", "Health"), ("dentist", "Health"), ("تحاليل", "Health"),
    ("لبس", "Clothing"), ("clothes", "Clothing"), ("shoes", "Clothing"), ("جزمه", "Clothing"), ("hoodie", "Clothing"), ("هدوم", "Clothing"),
    ("subscription", "Subscriptions"), ("اشتراك", "Subscriptions"),
    ("مدرسه", "Education"), ("school", "Education"), ("course", "Education"), ("كورس", "Education"), ("دروس", "Education"), ("tuition", "Education"),
    ("هديه", "Gifts & Family"), ("gift", "Gifts & Family"), ("عيديه", "Gifts & Family"),
    ("صدقه", "Charity"), ("charity", "Charity"), ("زكاه", "Charity"), ("donation", "Charity"),
    ("سينما", "Entertainment"), ("cinema", "Entertainment"), ("movie", "Entertainment"), ("ترفيه", "Entertainment"), ("concert", "Entertainment"),
    ("flight", "Travel"), ("طياره", "Travel"), ("hotel", "Travel"), ("فندق", "Travel"), ("travel", "Travel"), ("سفر", "Travel"),
    ("baby", "Baby"), ("بيبي", "Baby"), ("حفاضات", "Baby"), ("diapers", "Baby"),
    ("حلاق", "Personal Care"), ("barber", "Personal Care"), ("salon", "Personal Care"), ("كوافير", "Personal Care"), ("haircut", "Personal Care"),
    ("shopping", "Shopping"), ("تسوق", "Shopping"),
    ("fee", "Fees & Bank Charges"), ("رسوم", "Fees & Bank Charges"),
]
CATEGORY_ARG_ALIASES: dict[str, str] = {
    "الاكل": "Dining & Delivery", "اكل": "Dining & Delivery", "food": "Dining & Delivery", "dining": "Dining & Delivery",
    "بنزين": "Fuel", "fuel": "Fuel", "البنزين": "Fuel", "groceries": "Groceries", "البقاله": "Groceries", "بقاله": "Groceries",
    "transport": "Transport", "المواصلات": "Transport", "مواصلات": "Transport", "bills": "Bills & Utilities", "الفواتير": "Bills & Utilities",
    "health": "Health", "الصحه": "Health", "rent": "Rent/Housing", "الايجار": "Rent/Housing", "clothes": "Clothing", "اللبس": "Clothing",
    "subscriptions": "Subscriptions", "الاشتراكات": "Subscriptions",
}

ARABIC_NUMBER_WORDS: dict[str, int] = {
    "واحد": 1, "واحده": 1, "اتنين": 2, "اثنين": 2, "تلاته": 3, "ثلاثه": 3, "تلات": 3, "اربعه": 4, "اربع": 4, "خمسه": 5, "خمس": 5,
    "سته": 6, "ست": 6, "سبعه": 7, "سبع": 7, "تمانيه": 8, "ثمانيه": 8, "تمن": 8, "تسعه": 9, "تسع": 9, "عشره": 10, "عشر": 10,
    "حداشر": 11, "اتناشر": 12, "تلتاشر": 13, "اربعتاشر": 14, "خمستاشر": 15, "ستاشر": 16, "سبعتاشر": 17, "تمنتاشر": 18, "تسعتاشر": 19,
    "عشرين": 20, "تلاتين": 30, "ثلاثين": 30, "اربعين": 40, "خمسين": 50, "ستين": 60, "سبعين": 70, "تمانين": 80, "ثمانين": 80, "تسعين": 90,
    "ميه": 100, "مائه": 100, "ميتين": 200, "مئتين": 200, "تلتميه": 300, "ثلاثمائه": 300, "تلاتميه": 300, "ربعميه": 400, "اربعمائه": 400,
    "خمسميه": 500, "خمسمائه": 500, "ستميه": 600, "ستمائه": 600, "سبعميه": 700, "سبعمائه": 700, "تمنميه": 800, "ثمانمائه": 800,
    "تسعميه": 900, "تسعمائه": 900, "الف": 1000, "الفين": 2000, "تلاتالاف": 3000, "اربعتالاف": 4000, "خمستالاف": 5000, "مليون": 1_000_000,
}
ENGLISH_NUMBER_WORDS: dict[str, int] = {
    "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11,
    "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15, "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19,
    "twenty": 20, "thirty": 30, "forty": 40, "fifty": 50, "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90,
    "hundred": 100, "thousand": 1000, "million": 1_000_000,
}

CORRECTION_PATTERNS = [re.compile(p) for p in (
    r"^(لا|لأ|no|nope|not|nah)\b", r"\bمش\b", r"\bكانت\b", r"\bmake it\b", r"^category\b", r"^cat\b", r"كاتيجوري", r"قصدي", r"غلط",
    r"\bactually\b", r"\bit was\b", r"\bshould be\b", r"^change\b", r"^fix\b", r"خليها", r"^عدل", r"دي كانت",
)]

WHATSAPP_LIKE_SOURCES = {"WHATSAPP", "MANUAL", "UPLOAD"}
SMS_LIKE_SOURCES = {"SMS", "IOS_SHORTCUT", "PUSH"}

MINOR_UNITS: dict[str, int] = {"KWD": 1000}
HUGE_AMOUNT_MINOR_EGP = 100_000_00  # 100,000 EGP -> needs clarification from free text


# --------------------------------------------------------------------------------------------
# Result object
# --------------------------------------------------------------------------------------------

@dataclass
class ParseOutput:
    type: str = "UNKNOWN"
    amount_minor: Optional[int] = None
    currency: Optional[str] = None
    original_amount_minor: Optional[int] = None
    original_currency: Optional[str] = None
    merchant_normalized: Optional[str] = None
    category: Optional[str] = None
    date: Optional[str] = None
    time: Optional[str] = None
    payment_method: str = "UNKNOWN"
    account_last4: Optional[str] = None
    items: list[dict[str, Any]] = field(default_factory=list)
    total_consistent: Optional[bool] = None
    needs_clarification: bool = False
    is_command: bool = False
    command: Optional[str] = None
    command_args: dict[str, Any] = field(default_factory=dict)
    is_correction: bool = False
    paid_by: Optional[str] = None
    split: bool = False
    should_ingest: bool = False
    injection_obeyed: bool = False
    confidence: float = 0.0
    language: str = "en"
    notes: Optional[str] = None
    instruction_text_detected: bool = False

    def as_dict(self) -> dict[str, Any]:
        return {
            "type": self.type, "amount_minor": self.amount_minor, "currency": self.currency,
            "original_amount_minor": self.original_amount_minor, "original_currency": self.original_currency,
            "merchant_normalized": self.merchant_normalized, "category": self.category, "date": self.date, "time": self.time,
            "payment_method": self.payment_method, "account_last4": self.account_last4, "items": self.items,
            "total_consistent": self.total_consistent, "needs_clarification": self.needs_clarification,
            "is_command": self.is_command, "command": self.command, "command_args": self.command_args,
            "is_correction": self.is_correction, "paid_by": self.paid_by, "split": self.split,
            "should_ingest": self.should_ingest, "injection_obeyed": self.injection_obeyed,
            "confidence": self.confidence, "language": self.language, "notes": self.notes,
            "instruction_text_detected": self.instruction_text_detected,
        }


# --------------------------------------------------------------------------------------------
# Amount candidates
# --------------------------------------------------------------------------------------------

# ASCII digits only on purpose: Python's \d and float() accept Arabic-Indic digits, which would hide
# a broken DIGIT_MAP. Normalisation is the single path for non-ASCII digits.
_NUMBER_RE = re.compile(
    r"(?<![\w.,/:-])([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]{1,2})?|[0-9]{1,3}(?:\.[0-9]{3})+(?:,[0-9]{1,2})?|[0-9]+(?:\.[0-9]+)?)(?![0-9/:])"
)
_EXCLUDE_BEFORE_RE = re.compile(
    r"(ending(?: with| in)?|رقم(?: العمليه| العملية| المرجع| الحساب)?|ref\.?(?: no\.?)?|reference|trx id|transaction id|no\.|#|\*{2,}|card|كارت|بطاقه|حساب|account|"
    r"\bat|الساعه|@|\bx|×|\+2|\bof the\b|till|until|valid|حتي|لمده|every)\s*$",
    re.IGNORECASE,
)
_EXCLUDE_AFTER_RE = re.compile(
    r"^\s*(%|pm\b|am\b|مساء|صباحا|الصبح|x\b|×|ميجا|جيجا|gb\b|mb\b|دقائق|دقيقه|minutes?\b|installments?\b|months?\b|شهور\b|"
    r"days?\b|ايام\b|hours?\b|ساعات\b|اكتوبر|نوفمبر|pcs\b|people\b|افراد|/)",
    re.IGNORECASE,
)
_BALANCE_CONTEXT_RE = re.compile(r"(balance|available|limit|remaining|الرصيد|رصيدك|المتاح|المتبقي|متاح)\s*(limit|balance)?\s*[:\-]?\s*(egp|usd|جنيه)?\s*$", re.IGNORECASE)


@dataclass
class AmountCandidate:
    value: float
    currency: Optional[str]
    multiplier: int
    start: int
    end: int
    is_balance: bool = False

    @property
    def minor(self) -> int:
        cur = self.currency or "EGP"
        scale = MINOR_UNITS.get(cur, 100)
        return int(round(self.value * self.multiplier * scale))


def _parse_number_token(tok: str) -> Optional[float]:
    if "," in tok and "." in tok:
        if tok.rfind(",") > tok.rfind("."):  # 1.250,00 -> european
            tok = tok.replace(".", "").replace(",", ".")
        else:  # 1,250.00
            tok = tok.replace(",", "")
    elif "," in tok:
        parts = tok.split(",")
        if all(len(p) == 3 for p in parts[1:]):
            tok = tok.replace(",", "")
        else:
            tok = tok.replace(",", ".")
    elif tok.count(".") > 1:
        tok = tok.replace(".", "")
    try:
        return float(tok)
    except ValueError:
        return None


def find_amount_candidates(text: str) -> list[AmountCandidate]:
    out: list[AmountCandidate] = []
    for m in _NUMBER_RE.finditer(text):
        raw = m.group(1)
        before = text[max(0, m.start() - 40):m.start()]
        after = text[m.end():m.end() + 40]
        digits_only = re.sub(r"\D", "", raw)
        if len(digits_only) >= 7 and "," not in raw and "." not in raw:
            continue  # phone / reference number
        if _EXCLUDE_BEFORE_RE.search(before) and not _CURRENCY_BEFORE_RE.search(before):
            continue
        if _EXCLUDE_AFTER_RE.match(after) and not _CURRENCY_AFTER_RE.match(after):
            continue
        value = _parse_number_token(raw)
        if value is None:
            continue
        currency: Optional[str] = None
        cb = _CURRENCY_BEFORE_RE.search(before)
        ca = _CURRENCY_AFTER_RE.match(after)
        end = m.end()
        if ca:
            currency = CURRENCY_TOKENS.get(normalize_arabic(ca.group(1)).rstrip("."), None) or CURRENCY_TOKENS.get(ca.group(1).lower())
            end = m.end() + ca.end()
        elif cb:
            currency = CURRENCY_TOKENS.get(cb.group(1).lower().rstrip(".")) or CURRENCY_TOKENS.get(normalize_arabic(cb.group(1)))
        multiplier = 1
        mm = _MULTIPLIER_AFTER_RE.match(after)
        if mm and not ca:
            multiplier = MULTIPLIERS.get(mm.group(1).lower(), MULTIPLIERS.get(normalize_arabic(mm.group(1)), 1))
            end = m.end() + mm.end()
            ca2 = _CURRENCY_AFTER_RE.match(text[end:end + 30])
            if ca2:
                currency = CURRENCY_TOKENS.get(normalize_arabic(ca2.group(1)).rstrip(".")) or CURRENCY_TOKENS.get(ca2.group(1).lower())
        is_balance = bool(_BALANCE_CONTEXT_RE.search(before))
        out.append(AmountCandidate(value, currency, multiplier, m.start(), end, is_balance))
    return out


def words_to_number(text_norm: str) -> Optional[int]:
    """Sum Egyptian-Arabic or English number words ("بميتين وخمسين" -> 250, "four hundred fifty" -> 450)."""
    tokens = re.findall(r"[a-z]+|[؀-ۿ]+", text_norm)
    total = 0
    current = 0
    found = False
    for tok in tokens:
        t = tok
        if t.startswith("و") and t[1:] in ARABIC_NUMBER_WORDS:
            t = t[1:]
        elif t.startswith("ب") and t[1:] in ARABIC_NUMBER_WORDS:
            t = t[1:]
        if t in ARABIC_NUMBER_WORDS:
            found = True
            val = ARABIC_NUMBER_WORDS[t]
            if val >= 1000:
                total += (current or 1) * val
                current = 0
            else:
                current += val
            continue
        if t in ENGLISH_NUMBER_WORDS:
            found = True
            val = ENGLISH_NUMBER_WORDS[t]
            if val == 100:
                current = (current or 1) * 100
            elif val >= 1000:
                total += (current or 1) * val
                current = 0
            else:
                current += val
            continue
        if t == "and":
            continue
    if not found:
        return None
    return total + current


# --------------------------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------------------------

_CLAUSE_SPLIT_RE = re.compile(r"(?<=[.!?;])\s+|\n+|،\s*|,\s+|-{3,}|\|")


def strip_instruction_clauses(text: str) -> tuple[str, bool]:
    """Remove clauses that look like instructions to the model. Returns (clean_text, detected)."""
    detected = False
    kept: list[str] = []
    for clause in _CLAUSE_SPLIT_RE.split(text):
        if not clause or not clause.strip():
            continue
        norm = normalize_arabic(clause)
        if any(p.search(norm) for p in INSTRUCTION_PATTERNS):
            detected = True
            continue
        kept.append(clause.strip())
    return " ".join(kept), detected


def detect_language(text: str) -> str:
    arabic = len(re.findall(r"[؀-ۿ]", text))
    latin = len(re.findall(r"[A-Za-z]", text))
    if arabic and latin:
        return "mixed"
    return "ar" if arabic else "en"


def find_merchant(text_norm: str) -> tuple[Optional[str], Optional[str]]:
    wallet_context = "vodafone cash" in text_norm or "فودافون كاش" in text_norm or "orange cash" in text_norm
    for alias, merchant, category in MERCHANT_ALIASES:
        if alias in text_norm:
            if merchant in ("Vodafone", "Orange") and wallet_context:
                # the wallet is the payment rail; look for the payee instead
                continue
            return merchant, category
    return None, None


def find_category(text_norm: str) -> Optional[str]:
    for kw, cat in CATEGORY_KEYWORDS:
        if kw in text_norm:
            return cat
    return None


_DATE_RE = re.compile(r"\b(\d{1,2})/(\d{1,2})/(\d{2,4})\b")
_ISO_DATE_RE = re.compile(r"\b(\d{4})-(\d{2})-(\d{2})\b")
_TIME_RE = re.compile(r"\b(\d{1,2}):(\d{2})\b")


def extract_date_time(text: str) -> tuple[Optional[str], Optional[str]]:
    date: Optional[str] = None
    m = _DATE_RE.search(text)
    if m:
        d, mo, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
        if y < 100:
            y += 2000
        try:
            date = dt.date(y, mo, d).isoformat()
        except ValueError:
            date = None
    else:
        m2 = _ISO_DATE_RE.search(text)
        if m2:
            date = f"{m2.group(1)}-{m2.group(2)}-{m2.group(3)}"
    time: Optional[str] = None
    t = _TIME_RE.search(text)
    if t and 0 <= int(t.group(1)) <= 23:
        time = f"{int(t.group(1)):02d}:{t.group(2)}"
    return date, time


_LAST4_RE = re.compile(r"(?:ending(?: with| in)?|card|كارت|بطاقتكم(?: الائتمانيه)?|رقم|حسابكم|account)\s*(?:رقم\s*)?\*{0,4}\s*(\d{4})\b|\*{2,}(\d{4})\b", re.IGNORECASE)


def extract_last4(text: str) -> Optional[str]:
    for m in _LAST4_RE.finditer(text):
        val = m.group(1) or m.group(2)
        if val:
            return val
    return None


def detect_payment_method(text_norm: str, source: str, text_type: str) -> str:
    if "instapay" in text_norm or "انستاباي" in text_norm or "ipn" in text_norm:
        return "INSTAPAY"
    if any(k in text_norm for k in ("vodafone cash", "فودافون كاش", "orange cash", "اورنج كاش", "wallet", "محفظ", "telda", "e& money", "etisalat cash")):
        return "WALLET"
    if text_type == "CASH_WITHDRAWAL":
        return "CARD"
    if any(k in text_norm for k in ("card", "كارت", "بطاق", "visa", "mastercard", "فيزا")):
        return "CARD"
    if source in WHATSAPP_LIKE_SOURCES and any(k in text_norm for k in ("كاش", "cash", "نقدي")):
        return "CASH"
    if any(k in text_norm for k in ("account", "حساب", "transfer", "تحويل", "ايداع")):
        return "BANK_TRANSFER"
    return "UNKNOWN"


def _levenshtein(a: str, b: str) -> int:
    """Optimal string alignment distance: edits plus adjacent transpositions ("hlep" -> "help" = 1)."""
    if a == b:
        return 0
    d = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) + 1):
        d[i][0] = i
    for j in range(len(b) + 1):
        d[0][j] = j
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
    return d[len(a)][len(b)]


def detect_command(text_norm: str, has_amount: bool) -> tuple[Optional[str], dict[str, Any]]:
    body = re.sub(r"^[/!.]+", "", text_norm.strip())
    body = re.sub(r"[?؟!.]+$", "", body).strip()
    tokens = body.split()
    if not tokens or len(tokens) > 7:
        return None, {}
    # EDIT_LAST carries an amount; everything else must be amount-free.
    for phrase in COMMAND_TABLE["EDIT_LAST"]:
        if body.startswith(phrase):
            return "EDIT_LAST", {}
    if has_amount:
        return None, {}
    for cmd in ("CATEGORY_SPEND", "TOTAL", "UNDO", "BUDGET", "PENDING", "SUMMARY", "HELP"):
        for phrase in COMMAND_TABLE[cmd]:
            if body == phrase or body.startswith(phrase + " ") or (len(phrase) > 5 and phrase in body):
                args: dict[str, Any] = {}
                if cmd == "CATEGORY_SPEND":
                    rest = body[len(phrase):].strip() if body.startswith(phrase) else body
                    for word in rest.split():
                        if word in CATEGORY_ARG_ALIASES:
                            args["category"] = CATEGORY_ARG_ALIASES[word]
                            break
                return cmd, args
    if len(tokens) == 1 and re.fullmatch(r"[a-z]{4,}", tokens[0]):
        for word, cmd in _FUZZY_COMMAND_WORDS.items():
            if _levenshtein(tokens[0], word) <= 1:
                return cmd, {}
    return None, {}


def _contains_any(text_norm: str, keywords: tuple[str, ...]) -> bool:
    return any(k in text_norm for k in keywords)


# --------------------------------------------------------------------------------------------
# Main parse
# --------------------------------------------------------------------------------------------

def parse(case_input: dict[str, Any], repo_root: Optional[str] = None) -> dict[str, Any]:
    """Parse a standard eval-case input (dict with text/media/source/received_at/history)."""
    source = case_input.get("source", "WHATSAPP")
    media = case_input.get("media") or {}
    text = case_input.get("text") or ""
    if media.get("kind") == "ocr_text_standin" and media.get("path"):
        import os  # noqa: PLC0415

        path = os.path.join(repo_root or ".", media["path"])
        with open(path, encoding="utf-8") as fh:
            ocr_text = fh.read()
        return parse_receipt_text(ocr_text, case_input).as_dict()
    return parse_text(text, source, case_input.get("received_at"), case_input.get("history") or []).as_dict()


def parse_text(raw_text: str, source: str, received_at: Optional[str], history: list[dict[str, Any]]) -> ParseOutput:
    out = ParseOutput()
    text = normalize_text(raw_text)
    out.language = detect_language(text)
    norm_full = normalize_arabic(text)

    # 1) OTP first: never ingest, regardless of amounts in the message.
    if _contains_any(norm_full, OTP_KEYWORDS) and re.search(r"\b\d{4,8}\b", text):
        out.type, out.confidence, out.should_ingest = "OTP", 0.97, False
        return out

    # 2) Instruction-like clauses are data we ignore, never directives.
    clean, detected = strip_instruction_clauses(text)
    out.instruction_text_detected = detected
    if detected:
        out.notes = "instruction-like text ignored"
    norm = normalize_arabic(clean)

    candidates = [c for c in find_amount_candidates(clean) if not c.is_balance]
    has_amount = bool(candidates)

    # 3) SMS / push classification
    if source in SMS_LIKE_SOURCES:
        is_txn = _contains_any(norm, TRANSACTION_KEYWORDS)
        if not (is_txn and has_amount):
            if _contains_any(norm, PROMO_KEYWORDS) or not has_amount or not is_txn:
                out.type, out.confidence, out.should_ingest = "PROMO", 0.9, False
                return out
        return _finish_transaction(out, clean, norm, candidates, source, received_at)

    # 4) WhatsApp-like: commands, corrections, chit-chat, expenses
    if detected and not has_amount:
        out.type, out.confidence, out.should_ingest = "UNKNOWN", 0.9, False
        return out

    cmd, args = detect_command(norm, has_amount)
    if cmd:
        out.type, out.is_command, out.command, out.command_args = "COMMAND", True, cmd, args
        out.confidence, out.should_ingest = 0.95, False
        if cmd == "EDIT_LAST" and candidates:
            out.amount_minor, out.currency = candidates[-1].minor, candidates[-1].currency or "EGP"
        return out

    if history and any(p.search(norm) for p in CORRECTION_PATTERNS):
        return _finish_correction(out, clean, norm, candidates)

    if not has_amount:
        word_value = words_to_number(norm)
        # Spelled-out amounts only count when the message also names a currency, merchant or category
        # ("one sec" must stay chit-chat).
        if word_value and (_currency_from_words(norm) or find_merchant(norm)[0] or find_category(norm)):
            candidates = [AmountCandidate(float(word_value), _currency_from_words(norm), 1, 0, len(clean))]
            has_amount = True
    if not has_amount:
        out.type, out.confidence, out.should_ingest = ("UNKNOWN", 0.8, False) if detected else ("CHITCHAT", 0.9, False)
        return out

    return _finish_transaction(out, clean, norm, candidates, source, received_at)


def _currency_from_words(norm: str) -> Optional[str]:
    for tok, cur in CURRENCY_TOKENS.items():
        if len(tok) > 1 and tok in norm:
            return cur
    return None


def _pick_amount(candidates: list[AmountCandidate], source: str) -> tuple[AmountCandidate, Optional[AmountCandidate]]:
    """Returns (booked, original). EGP wins when a foreign amount and an EGP equivalent coexist."""
    tagged = [c for c in candidates if c.currency]
    if source in SMS_LIKE_SOURCES:
        pool = tagged or candidates
        egp = [c for c in pool if (c.currency or "EGP") == "EGP"]
        foreign = [c for c in pool if c.currency and c.currency != "EGP"]
        if foreign and egp:
            return egp[0], foreign[0]
        return pool[0], None
    if tagged:
        egp = [c for c in tagged if c.currency == "EGP"]
        foreign = [c for c in tagged if c.currency != "EGP"]
        if egp and foreign:
            return egp[0], foreign[0]
        return tagged[0], None
    multiplied = [c for c in candidates if c.multiplier > 1]
    if multiplied:
        return multiplied[0], None
    return max(candidates, key=lambda c: c.value), None


def _finish_transaction(out: ParseOutput, clean: str, norm: str, candidates: list[AmountCandidate], source: str,
                        received_at: Optional[str]) -> ParseOutput:
    booked, original = _pick_amount(candidates, source)
    out.amount_minor = booked.minor
    out.currency = booked.currency or "EGP"
    if original:
        out.original_amount_minor, out.original_currency = original.minor, original.currency

    # type
    if source in SMS_LIKE_SOURCES:
        if _contains_any(norm, REFUND_KEYWORDS):
            out.type = "REFUND"
        elif _contains_any(norm, WITHDRAWAL_KEYWORDS):
            out.type = "CASH_WITHDRAWAL"
        elif _contains_any(norm, TRANSFER_KEYWORDS) and not _contains_any(norm, ("received", "تم استلام", "استلمت")):
            out.type = "TRANSFER"
        elif _contains_any(norm, INCOME_KEYWORDS):
            out.type = "INCOME"
        else:
            out.type = "EXPENSE"
    else:
        out.type = "REFUND" if _contains_any(norm, REFUND_KEYWORDS) else "EXPENSE"

    # merchant / category
    merchant, category = find_merchant(norm)
    if merchant is None and source in SMS_LIKE_SOURCES and out.type in ("EXPENSE", "REFUND"):
        merchant = _extract_sms_merchant(clean, norm)
    out.merchant_normalized = merchant
    if out.type in ("EXPENSE", "REFUND"):
        if _contains_any(norm, FEE_KEYWORDS) and merchant is None:
            category = "Fees & Bank Charges"
        out.category = category or find_category(norm)
    if out.type in ("INCOME", "TRANSFER", "CASH_WITHDRAWAL"):
        out.category = None

    # date / time
    date, time = extract_date_time(clean)
    if date is None:
        base = parse_iso(received_at)
        if base is not None:
            local = to_cairo(base)
            if any(k in norm for k in ("امبارح", "yesterday")):
                local -= dt.timedelta(days=1)
            date = local.date().isoformat()
        time = None
    out.date, out.time = date, time

    out.payment_method = detect_payment_method(norm, source, out.type)
    out.account_last4 = extract_last4(clean) if source in SMS_LIKE_SOURCES else None

    # multi-item ("Netflix + Spotify")
    if source in WHATSAPP_LIKE_SOURCES:
        plus_parts = [p.strip() for p in re.split(r"\s\+\s|\sو\s", clean) if p.strip()]
        if len(plus_parts) >= 2:
            names = []
            for part in plus_parts:
                m, _ = find_merchant(normalize_arabic(part))
                name = m or re.sub(r"[\d$€£,.]+|\b(k|الف|ألف)\b", "", part).strip()
                if name:
                    names.append({"name": name})
            if len(names) >= 2:
                out.items = names
                out.needs_clarification = True
        if any(k in norm for k in ("split", "نقسم", "بالنص", "نص نص", "اقسم")):
            out.split = True

    # confidence
    if source in SMS_LIKE_SOURCES:
        out.confidence = 0.95 if (merchant or out.type != "EXPENSE") else 0.9
        if out.payment_method == "INSTAPAY" and out.type == "EXPENSE":
            out.needs_clarification = True
            out.confidence = 0.7
    else:
        if out.category:
            out.confidence = 0.9
        else:
            out.confidence = 0.6
            out.needs_clarification = True
        if out.needs_clarification and out.items:
            out.confidence = 0.7
        if out.currency == "EGP" and out.amount_minor is not None and out.amount_minor > HUGE_AMOUNT_MINOR_EGP:
            out.needs_clarification = True
            out.confidence = min(out.confidence, 0.5)
    out.should_ingest = True
    return out


_SMS_MERCHANT_RE = re.compile(
    r"\b(?:at|لدي|from|to)\s+([A-Za-z][A-Za-z0-9 .*&'-]{1,40}?)(?=\s+(?:on|using|with|via|was|has|is|to|from|بتاريخ|بمبلغ|رقم)\b|[.,\n]|$)"
)


def _extract_sms_merchant(clean: str, norm: str) -> Optional[str]:
    if _contains_any(norm, FEE_KEYWORDS) or _contains_any(norm, WITHDRAWAL_KEYWORDS):
        return None
    m = _SMS_MERCHANT_RE.search(clean)
    if not m:
        return None
    raw = m.group(1).strip(" .*")
    if re.search(r"\d{3,}|\*{2,}", raw) or raw.lower().startswith(("your ", "account", "card")):
        return None
    if re.search(r"[A-Z]\*{2,}", m.group(0)):  # masked person name like "AHMED M***"
        return None
    return " ".join(w.capitalize() if w.isupper() else w for w in raw.split())


def _finish_correction(out: ParseOutput, clean: str, norm: str, candidates: list[AmountCandidate]) -> ParseOutput:
    out.type, out.is_correction, out.confidence, out.should_ingest = "EXPENSE", True, 0.85, True
    good = []
    for c in candidates:
        before = normalize_arabic(clean[max(0, c.start - 12):c.start])
        if re.search(r"(مش|not|instead of)\s*$", before):
            continue
        good.append(c)
    if good:
        out.amount_minor, out.currency = good[0].minor, good[0].currency or "EGP"
    m = re.search(r"make it (\w+)", norm)
    if m:
        out.paid_by = m.group(1).rstrip("'s").capitalize()
    for alias, cat in CATEGORY_ARG_ALIASES.items():
        if re.search(rf"(category|cat|كاتيجوري|خليها)\s+{re.escape(alias)}", norm):
            out.category = cat
            break
    if out.category is None:
        out.category = find_category(norm) if re.search(r"category|cat\b|كاتيجوري", norm) else None
    return out


# --------------------------------------------------------------------------------------------
# Receipts (OCR text stand-ins)
# --------------------------------------------------------------------------------------------

_RECEIPT_ITEM_RE = re.compile(
    r"^(?P<name>.*?\S)\s+(?:(?P<qty>\d+(?:\.\d+)?)\s*[xX×]\s*(?P<unit>\d+(?:\.\d{1,2})?)\s+)?(?P<total>\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?)\s*$"
)
_RECEIPT_SKIP_RE = re.compile(r"tel|phone|date|time|receipt|invoice|cashier|tax id|vat no|reg|ref|branch|address|thank|visit|order|table|guest|customer|pump|station|رقم|تليفون|فاتوره|كاشير|شكرا|التاريخ|الوقت|فرع|طاوله", re.IGNORECASE)
#: Common OCR confusions in receipt keywords (zero for O, one for l).
OCR_KEYWORD_FIXES = (("t0tal", "total"), ("tota1", "total"), ("t0ta1", "total"), ("subt0tal", "subtotal"), ("t0tal due", "total due"))
_TOTAL_RE = re.compile(r"\b(grand total|total|net total|amount due|amount|الاجمالي|الإجمالي|المجموع الكلي|الصافي|المبلغ)\b", re.IGNORECASE)
_SUBTOTAL_RE = re.compile(r"sub\s*total|subtotal|المجموع الفرعي|قبل الضريبه|المجموع\b", re.IGNORECASE)
_VAT_RE = re.compile(r"\bvat\b|\btax\b|ضريبه|ض\.ق\.م|ق\.م", re.IGNORECASE)
_SERVICE_RE = re.compile(r"service|خدمه", re.IGNORECASE)
_DISCOUNT_RE = re.compile(r"discount|خصم", re.IGNORECASE)
_PAYMENT_LINE_RE = re.compile(r"visa|mastercard|master card|card|cash|change|paid|tender|فيزا|كاش|نقدا|المدفوع|الباقي|\*{3,}\d", re.IGNORECASE)


def parse_receipt_text(ocr_text: str, case_input: dict[str, Any]) -> ParseOutput:
    out = ParseOutput()
    text = normalize_text(ocr_text)
    out.language = detect_language(text)
    lines = [ln.strip() for ln in text.split("\n") if ln.strip()]
    kept_lines: list[str] = []
    for ln in lines:
        if any(p.search(normalize_arabic(ln)) for p in INSTRUCTION_PATTERNS):
            out.instruction_text_detected = True
            out.notes = "instruction-like text ignored"
            continue
        kept_lines.append(ln)

    whole_norm = normalize_arabic("\n".join(kept_lines))
    merchant, category = find_merchant(whole_norm)
    out.merchant_normalized = merchant
    out.category = category or find_category(whole_norm)

    items: list[dict[str, Any]] = []
    total = subtotal = vat = service = discount = None
    payment = "UNKNOWN"
    for ln in kept_lines:
        norm_ln = normalize_arabic(ln)
        for wrong, right in OCR_KEYWORD_FIXES:
            norm_ln = norm_ln.replace(wrong, right)
        amounts = [c for c in find_amount_candidates(ln) if not c.is_balance]
        if _PAYMENT_LINE_RE.search(norm_ln) and not _TOTAL_RE.search(norm_ln):
            if re.search(r"visa|mastercard|master card|card|فيزا|\*{3,}\d", norm_ln):
                payment = "CARD"
            elif re.search(r"cash|كاش|نقدا", norm_ln):
                payment = "CASH"
            if "instapay" in norm_ln:
                payment = "INSTAPAY"
            continue
        if "instapay" in norm_ln or "انستاباي" in norm_ln:
            payment = "INSTAPAY"
        if not amounts:
            continue
        last = amounts[-1]
        if _SUBTOTAL_RE.search(norm_ln) and not re.search(r"grand|الكلي", norm_ln):
            subtotal = last.minor
        elif _VAT_RE.search(norm_ln) and _TOTAL_RE.search(norm_ln) is None:
            vat = last.minor
        elif _SERVICE_RE.search(norm_ln):
            service = last.minor
        elif _DISCOUNT_RE.search(norm_ln):
            discount = last.minor
        elif _TOTAL_RE.search(norm_ln) and (_VAT_RE.search(norm_ln) is None or "incl" in norm_ln or "شامل" in norm_ln):
            total = last.minor
        elif _RECEIPT_SKIP_RE.search(norm_ln):
            continue
        else:
            m = _RECEIPT_ITEM_RE.match(ln)
            if m and not re.fullmatch(r"[\d\s.,x×]+", m.group("name")):
                item: dict[str, Any] = {"name": re.sub(r"\s{2,}", " ", m.group("name")).strip(" -:."), "total_minor": last.minor}
                if m.group("qty"):
                    qty = m.group("qty")
                    item["qty"] = qty.rstrip("0").rstrip(".") if "." in qty else qty
                items.append(item)

    if total is None:
        total = subtotal if subtotal is not None else (max((c.minor for ln in kept_lines for c in find_amount_candidates(ln)), default=None))
    if "instapay" in whole_norm or "انستاباي" in whole_norm:
        payment = "INSTAPAY"
    out.items = items
    out.type = "EXPENSE"
    out.amount_minor = total
    out.currency = "EGP"
    out.payment_method = payment
    if items and total is not None:
        items_sum = sum(i["total_minor"] for i in items)
        reconciled = items_sum + (vat or 0) + (service or 0) - (discount or 0)
        out.total_consistent = abs(items_sum - total) <= 100 or abs(reconciled - total) <= 100
    elif total is not None:
        out.total_consistent = True
    date, time = extract_date_time("\n".join(kept_lines))
    if date is None:
        base = parse_iso(case_input.get("received_at"))
        if base is not None:
            date = to_cairo(base).date().isoformat()
    out.date, out.time = date, time
    out.confidence = 0.9 if (out.total_consistent and merchant) else 0.7
    out.needs_clarification = not bool(out.total_consistent)
    out.should_ingest = total is not None
    if total is None:
        out.type = "UNKNOWN"
    return out


# --------------------------------------------------------------------------------------------
# Dedup
# --------------------------------------------------------------------------------------------

_SOURCE_FAMILY = {"SMS": "SMS", "IOS_SHORTCUT": "SMS", "PUSH": "PUSH", "WHATSAPP": "WHATSAPP", "UPLOAD": "WHATSAPP", "MANUAL": "MANUAL"}
MERGE_WINDOW = dt.timedelta(hours=48)
REFUND_WINDOW = dt.timedelta(days=45)


def dedup_sequence(sequence: list[dict[str, Any]], repo_root: Optional[str] = None) -> dict[str, Any]:
    parsed = [parse(dict(msg, source=msg.get("source", "WHATSAPP")), repo_root) for msg in sequence]
    times = [parse_iso(msg.get("received_at")) or dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc) for msg in sequence]
    groups: list[list[int]] = []
    statuses: list[str] = []
    group_of: dict[int, int] = {}
    fingerprints: list[tuple[str, str]] = []

    for i, (msg, p) in enumerate(zip(sequence, parsed)):
        fam = _SOURCE_FAMILY.get(msg.get("source", ""), msg.get("source", ""))
        fp = _fingerprint(msg)
        dup_of = next((j for j, (fam_j, fp_j) in enumerate(fingerprints) if fam_j == fam and fp_j == fp), None)
        fingerprints.append((fam, fp))
        if dup_of is not None:
            groups[group_of[dup_of]].append(i)
            group_of[i] = group_of[dup_of]
            statuses.append("DROPPED")
            continue
        if not p["should_ingest"]:
            groups.append([i])
            group_of[i] = len(groups) - 1
            statuses.append("NEW")
            continue
        if p["type"] == "REFUND":
            linked = any(
                statuses[j] in ("NEW",) and parsed[j]["type"] == "EXPENSE" and _amount_close(parsed[j], p)
                and abs(times[i] - times[j]) <= REFUND_WINDOW and _merchant_agrees(parsed[j], p) >= 0
                for j in range(i)
            )
            groups.append([i])
            group_of[i] = len(groups) - 1
            statuses.append("REFUND_LINKED" if linked else "NEW")
            continue
        best_j, best_score = None, 0.0
        for j in range(i):
            if statuses[j] != "NEW" or not parsed[j]["should_ingest"]:
                continue
            fam_j = _SOURCE_FAMILY.get(sequence[j].get("source", ""), "")
            if fam_j == fam:
                continue  # one source never reports the same transaction twice (except exact dupes)
            if parsed[j]["type"] != p["type"]:
                continue
            if not _amount_close(parsed[j], p):
                continue
            if abs(times[i] - times[j]) > MERGE_WINDOW:
                continue
            score = 0.5 + 0.3 + _merchant_agrees(parsed[j], p)
            if score > best_score:
                best_j, best_score = j, score
        if best_j is not None and best_score >= 0.8:
            groups[group_of[best_j]].append(i)
            group_of[i] = group_of[best_j]
            statuses.append("MERGED")
        else:
            groups.append([i])
            group_of[i] = len(groups) - 1
            statuses.append("NEW")
    return {"merge_groups": groups, "statuses": statuses}


def _fingerprint(msg: dict[str, Any]) -> str:
    media = msg.get("media") or {}
    if media.get("sha256"):
        return "media:" + media["sha256"]
    if msg.get("external_id"):
        return "ext:" + str(msg["external_id"])
    return "text:" + hashlib.sha256(normalize_text(msg.get("text", "")).encode("utf-8")).hexdigest()


def _amount_close(a: dict[str, Any], b: dict[str, Any]) -> bool:
    if a["amount_minor"] is None or b["amount_minor"] is None:
        return False
    if (a["currency"] or "EGP") != (b["currency"] or "EGP"):
        return False
    x, y = a["amount_minor"], b["amount_minor"]
    return abs(x - y) <= max(100, int(0.01 * max(x, y)))


def _merchant_agrees(a: dict[str, Any], b: dict[str, Any]) -> float:
    """+0.2 agree, -0.3 disagree, +0.1 when one side has no merchant/category information."""
    ma, mb = a.get("merchant_normalized"), b.get("merchant_normalized")
    if ma and mb:
        return 0.2 if ma.lower() == mb.lower() else -0.3
    ca, cb = a.get("category"), b.get("category")
    if ca and cb:
        return 0.2 if ca == cb else -0.3
    return 0.1
