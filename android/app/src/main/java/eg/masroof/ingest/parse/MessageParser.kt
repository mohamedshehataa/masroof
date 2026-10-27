package eg.masroof.ingest.parse

import eg.masroof.ingest.DefaultSenders
import eg.masroof.ingest.senderKey
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * The on-device rules engine (REQ-SMS-010..012, REQ-NTF-002). It turns one bank/wallet SMS or app notification into a
 * [ParsedRecord], or says why it did not. It is deterministic and rules-only; the message text is read here and then
 * forgotten: only the structured record, whose single free-text field is a digit-free label, can leave the phone.
 *
 * A record is produced ONLY when (1) the message is not secret ([SecretDetector]), (2) a known direction template matches
 * and (3) an amount with an unambiguous currency is found. Anything else is a [ParseOutcome.Review] (kept on the phone)
 * or a [ParseOutcome.Ignore] (OTP, promotion, declined payment, statement reminder).
 */
@Suppress("LargeClass", "CyclomaticComplexMethod", "SpreadOperator", "MaxLineLength", "LoopWithTooManyJumpStatements")
object MessageParser {
    private val cairo: ZoneId = ZoneId.of("Africa/Cairo")
    private val ci = setOf(RegexOption.IGNORE_CASE)

    private const val MAX_LABEL = 40
    private const val MAX_LABEL_TOKENS = 5
    private const val MAX_DATE_AGE_DAYS = 45L
    private const val MAX_AMOUNT_MINOR = 10_000_000_000_000L
    private const val NOON = 12
    private const val TWO_DIGIT_YEAR_BASE = 2000
    private const val NOON_HOURS = 12
    private const val LOOKBACK = 30
    private const val DATE_LOOKBACK = 25
    private const val TIME_WINDOW = 24
    private val PLAUSIBLE_YEARS = 1990..2100
    private const val TIME_BEFORE_WINDOW = 24
    private const val LAST4_LOOKBACK = 40
    private const val ROLE_LOOKBACK = 16

    // ---- amounts -------------------------------------------------------------------------------------------------
    private const val NUM = "\\d{1,3}(?:\\.\\d{3})+,\\d{1,2}|\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:[.,]\\d+)?"
    private const val CUR =
        "(?:egp|l\\.e\\.?|le|e£|جنيها|جنيه|جنية|ج\\.م\\.?|جم|usd|\\$|دولار|eur|€|يورو|gbp|£|sar|sr|ريال|riyal|aed|dhs|dh|درهم" +
            "|kwd|qar|jpy|yen|try|tl|cad|chf|cny|omr|bhd|jod)"
    private val currencyThenNumber = Regex("(?<![a-z])($CUR)[ :]?($NUM)(?![\\d]|[a-z\\p{L}])", ci)
    private val numberThenCurrency = Regex("(?<![\\d.,*-])($NUM) ?($CUR)(?![a-z])", ci)
    private val keywordAmount = Regex("(?:amount(?: of)?|value|مبلغ|بمبلغ|بقيمه|قيمه)[ :]*($NUM)(?![\\d])", ci)
    private val tokenAfter = Regex("^ ?([^ \\d.,;:!?()]+)")
    private val lastFourMarker =
        Regex("(?:\\bending(?:\\s+(?:with|in|by))?|\\bends(?:\\s+(?:with|in))?|(?:المنتهيه|المنتهي)\\s*ب)[ :*•x#.]*$", ci)
    private val balanceBefore =
        Regex("(?:balance|\\bbal\\b|avl|avail\\w*|limit|(?:amt|amount) due|رصيد\\w*|الرصيد|المتاح|remaining)[^\\d]{0,18}$", ci)
    private val amountStopWords =
        setOf(
            "at",
            "to",
            "from",
            "on",
            "in",
            "for",
            "using",
            "via",
            "with",
            "by",
            "was",
            "has",
            "is",
            "and",
            "of",
            "لدي",
            "عند",
            "من",
            "الي",
            "في",
            "علي",
            "على",
            "عبر",
            "بتاريخ",
            "لصالح",
            "و",
            "ب",
            "بنجاح",
            "تم",
            "هو",
            "ل",
            "من",
        )
    private val foreignWord = Regex("\\b(?:usd|eur|gbp|sar|aed|kwd|qar|jpy|cad|chf|cny|yen|riyal|dhs)\\b|\\$|€|دولار|يورو|ريال|درهم", ci)
    private val continuesWithSeparator = Regex("^[.,]\\d")
    private val groupedDigits = Regex("^ \\d{3}(?!\\d)")

    private val currencyOf =
        mapOf(
            "egp" to "EGP",
            "le" to "EGP",
            "l.e" to "EGP",
            "l.e." to "EGP",
            "e£" to "EGP",
            "جنيها" to "EGP",
            "جنيه" to "EGP",
            "جنية" to "EGP",
            "ج.م" to "EGP",
            "ج.م." to "EGP",
            "جم" to "EGP",
            "usd" to "USD",
            "$" to "USD",
            "دولار" to "USD",
            "eur" to "EUR",
            "€" to "EUR",
            "يورو" to "EUR",
            "gbp" to "GBP",
            "£" to "GBP",
            "sar" to "SAR",
            "sr" to "SAR",
            "ريال" to "SAR",
            "riyal" to "SAR",
            "aed" to "AED",
            "dhs" to "AED",
            "dh" to "AED",
            "درهم" to "AED",
            "kwd" to "KWD",
            "qar" to "QAR",
            "jpy" to "JPY",
            "yen" to "JPY",
            "try" to "TRY",
            "tl" to "TRY",
            "cad" to "CAD",
            "chf" to "CHF",
            "cny" to "CNY",
            "omr" to "OMR",
            "bhd" to "BHD",
            "jod" to "JOD",
        )

    private class Amount(
        val start: Int,
        val end: Int,
        val minor: Long?,
        val currency: String?,
        val balance: Boolean,
        val bareEgp: Boolean,
        val ambiguous: Boolean,
    )

    // ---- classification --------------------------------------------------------------------------------------------
    private val declined =
        Regex(
            "\\b(?:declined|rejected|unsuccessful|insufficient|failed|failure|not approved|denied|unable to)\\b" +
                "|تم رفض|رفض العمليه|فشل|غير ناجح|تعذر|مرفوض|غير كاف",
            ci,
        )
    private val reversal = Regex("\\b(?:reversal|reversed|voided|cancell?ed transaction)\\b|تم عكس|عكس عمليه|الغاء عمليه", ci)
    private val statement =
        Regex(
            "statement is ready|e-?statement|كشف حساب|payment due|\\bdue date\\b|\\breminder\\b|تاريخ الاستحقاق|تذكير|minimum payment",
            ci,
        )
    private val statementStrong =
        Regex(
            "statement date|total amt due|min(?:imum|\\.)? amt due|total amount due|minimum amount due|\\bamt due\\b" +
                "|الحد الادني للسداد|المبلغ المستحق|الحد الادني للمبلغ",
            ci,
        )
    private val transactionHeadline =
        Regex("\\b(?:debited|purchase|spent|withdrawn|withdrawal|charged|used for)\\b|تم خصم|تم شراء|تم دفع", ci)
    private val promoWords =
        Regex(
            "% off|خصم\\s*\\d+\\s*%|\\d+\\s*%\\s*خصم|%\\s*\\d+\\s*خصم|احصل\\s+علي\\s+خصم|" +
                // fixed-amount offers: "خصم 100 جنيه على ...", "وفر 50 جنيه", "get EGP 50 off", "EGP 100 cashback on ..."
                "خصم\\s*[\\d.,]+\\s*(?:جنيه|جم|egp|le)?\\s+علي|وفر\\s*[\\d.,]+" +
                "|\\b(?:egp|le)\\s*[\\d.,]+\\s+off\\b|[\\d.,]+\\s*(?:egp|le)\\s+off\\b" +
                "|[\\d.,]+\\s*(?:egp|le|جنيه)\\s+cashback|" +
                "\\boffers?\\b|cashback|\\bpoints\\b|rewards|subscribe|t&c|terms apply|congratulations|\\bpromo|coupon|discount|" +
                "installments up to|https?:|www\\.|عرض|عروض|نقاط|اشترك|الشروط والاحكام|مبروك|اربح|كوبون|خصم يصل|تقسيط",
            ci,
        )
    private val outAnchor =
        Regex(
            "\\btransaction (?:of|was made|has been made|approved|completed)\\b" +
                "|\\b(?:debited|debit|deducted|withdraw(?:n|al)?|charged|spent|paid|pay|sent" +
                "|send|purchased?|payment|transfer(?:red)?|used for|pos)\\b" +
                "|تم خصم|خصم(?!\\s*\\d+\\s*%)|تم دفع|دفع|شراء|سداد|تحويل|تم ارسال|ارسال|ارسلت|حولت|سحب|مدفوعات|عمليه|تم استخدام" +
                "|$CARD_AT",
            ci,
        )
    private val inAnchor =
        Regex(
            "\\brefund(?:ed)? of\\b|you(?: have)? received|\\breceived (?:egp|le|an?|\\d|usd)" +
                "|(?:was|is|has been|have been|were) credited\\b(?! to a/c)|\\bcredited by\\b|\\bcredited\\s+to\\b|\\bmoney received\\b" +
                "|\\b(?:paid|sent|transferred|credited)\\s+to\\s+you\\b|\\bto\\s+you\\s+by\\b|\\b(?:paid|sent|transferred)\\s+you\\b" +
                "|(?<![\\p{L}\\d])لكم(?![\\p{L}])|\\brefunded\\b|\\breceived\\s+(?:to|into|in)\\s+your\\b" +
                "|credited with|(?:has|have) been credited to your|were credited" +
                "|\\bdeposit(?:ed)?\\b|\\bsalary\\b|\\bincoming\\b|ايداع|تم استلام|تم استقبال|استقبال تحويل|تم رد|رد مبلغ|استرداد|مرتجع|استلمت|وارد|راتب|مرتب|تم اضافه|اضافه مبلغ|استلام حواله",
            ci,
        )
    private val ambiguousReceipt = Regex("استلام (?:تحويلك|حوالتك|التحويل)|received your transfer", ci)
    private val refund = Regex("\\brefund(?:ed)?\\b|استرداد|مرتجع|رد مبلغ", ci)
    private val withdrawal = Regex("\\batm\\b|cash withdrawal|cash advance|\\bwithdraw(?:n|al)?\\b|سحب نقدي|ماكينه الصراف|تم سحب", ci)
    private val fee =
        Regex(
            "\\b(?:annual fee|monthly fee|service fee|maintenance fee|card fee|sms fee|fee of|fees of|a fee|fees?|commission)\\b" +
                "|\\b(?:transfer|sms|card|service|annual|monthly|maintenance|instapay)\\s+(?:fees?|charges?)\\b" +
                "|(?<![\\p{L}])مصاريف(?![\\p{L}])|خصم (?:رسوم|عموله|مصاريف)|(?:رسوم|عموله) (?:الخدمه|شهريه|سنويه|الصيانه|بطاقه|البطاقه)|(?<![\\p{L}])رسوم(?![\\p{L}])",
            ci,
        )
    private val transferWords =
        Regex(
            "\\btransfer(?:red)?\\b|\\bsent\\b|\\bsend\\b|credited to (?!your)|تحويل|ارسال|ارسلت|حولت|instapay|انستاباي|\\bipa\\b|انستا باي",
            ci,
        )
    private val paymentWords =
        Regex("payment|\\bpaid\\b|\\bpay\\b|purchase|spent|\\bbill\\b|merchant|\\bqr\\b|charged|دفع|سداد|شراء|فاتوره|تاجر|مشتريات", ci)
    private val ownAccount = Regex("from your (?:a/c|account|card|wallet)|من حسابك|من بطاقتك|من محفظتك", ci)
    private val instapay = Regex("instapay|insta pay|\\bipn\\b|\\bipa\\b|انستاباي|انستا باي|تحويل لحظي", ci)

    // ---- instrument last four -----------------------------------------------------------------------------------
    private class Last4Pattern(
        val regex: Regex,
        /** A bare number in an own-account slot: accepted only in the instant-transfer shape. */
        val own: Boolean,
        /** An own noun phrase with an explicit marker ("Your card ending 1234", "حسابكم ***7781"). */
        val ownExplicit: Boolean = false,
    )

    private val last4Patterns =
        listOf(
            Last4Pattern(Regex("\\b(?:ending|ends)(?: with| in| by)?[ :]*[x*•.#]*[ ]?(\\d{4})(?!\\d)", ci), false),
            Last4Pattern(
                Regex(
                    "(?:card|a/c|acct|account|acc|wallet|بطاق\\w*|حساب\\w*|محفظ\\w*)[^\\d]{0,25}?(?:[x*•]{2,}|\\.{3}) ?(\\d{4})(?!\\d)",
                    ci,
                ),
                false,
            ),
            Last4Pattern(Regex("(?:المنتهيه|المنتهي|تنتهي|منتهيه)\\s*ب\\s*(\\d{4})(?!\\d)", ci), false),
            // WHITELIST: a number directly attached to the household's own noun phrase
            Last4Pattern(
                Regex(
                    "(?<![\\p{L}])your\\s+(?:[\\p{L}]+\\s+){0,2}?(?:account|a/c|acct|wallet|card)" +
                        "(?:\\s+(?:ending(?:\\s+(?:with|in|by))?)[ :#]*[x*•X.]*|[ :]*#[x*•X.]*|[ :]*[x*•X.]{2,})\\s?(\\d{4})(?!\\d)",
                    ci,
                ),
                false,
                true,
            ),
            Last4Pattern(
                Regex(
                    "(?<![\\p{L}\\d])[لب]?(?:حساب|محفظت|بطاقت)(?:ك|كم)" +
                        "(?:\\s+(?:رقم\\s*)?(?:(?:المنتهيه|المنتهي|منتهيه)\\s*(?:ب\\s*)?)[x*•]*|\\s*(?:رقم\\s*)?[x*•]{2,})\\s?(\\d{4})(?!\\d)",
                    ci,
                ),
                false,
                true,
            ),
            Last4Pattern(Regex("(?<![\\p{L}\\d])[لب]?(?:حساب|محفظت|بطاقت)(?:ك|كم)\\s+رقم\\s*(\\d{4})(?!\\d)", ci), true),
            Last4Pattern(Regex("\\byour\\s+(?:account|a/c|acct)\\s+(?:no\\.?|number)\\s*(\\d{4})(?!\\d)", ci), true),
            // "your account": possessive, so it is the household's instrument even after "to"/"إلى"
            Last4Pattern(
                Regex(
                    "(?<![\\p{L}\\d])(?:من|الي|from|to)\\s+(?:your\\s+)?(?:حساب(?:ك|كم)|account)\\s+(?:رقم\\s*)?(\\d{4})(?!\\d)" +
                        "(?=\\s*(?:بمبلغ|بقيمه|مبلغ|ب\\s*\\d|by\\b|with\\b|for\\b|(?:egp|le|جم|جنيه|ج\\.م)\\s*\\d" +
                        "|\\d[\\d.,]*\\s*(?:egp|le|جم|جنيه|ج\\.م)))",
                    ci,
                ),
                true,
            ),
        )

    private val phoneNumber = Regex("(?<![\\d])(?:\\+?20)?01[0125]\\d{8}(?!\\d)")
    private val counterpartyBefore =
        Regex("(?:(?<![\\p{L}\\d])الي|(?<![\\p{L}\\d])من|(?<![\\p{L}\\d])ل?رقم|\\bto|\\bfrom)\\s*(?:رقم|no\\.?|number)?\\s*$", ci)

    // ---- dates ---------------------------------------------------------------------------------------------------
    private val dateFull = Regex("(?<![\\d/.:-])(\\d{1,2})([/.-])(\\d{1,2})\\2(\\d{4}|\\d{2})(?![\\d/:])")
    private val dateIso = Regex("(?<![\\d/.:-])(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})(?![\\d])")
    private val dateShort = Regex("(?<![\\d/.:-])(\\d{1,2})/(\\d{1,2})(?![\\d/:]|\\.\\d)")
    private val dateCompact =
        Regex("(?<![\\d/.:-])(\\d{1,2})[ -]?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[ ,-]*(\\d{4}|\\d{2})(?![\\d])", ci)
    private val dateMonthFirst =
        Regex("(?<![a-z])(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.? (\\d{1,2}),? (\\d{4})(?![\\d])", ci)
    private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val timeBefore =
        Regex("(\\d{1,2}):(\\d{2})(?::\\d{2})? ?(am|pm|ص|م)?(?: ?(?:يوم|on|at|في|بتاريخ))* ?$", ci)
    private val timeAfter = Regex("^ ?(?:at |في |الساعه )?(\\d{1,2}):(\\d{2})(?::\\d{2})? ?(am|pm|ص|م)?(?![\\p{L}\\d])", ci)
    private val dateNot =
        Regex("(?:until|till|valid|due|expiry|expires?|exp|statement|استحقاق|حتي|صالح|صلاحيه|ينتهي)[^\\d]{0,12}$", ci)

    // ---- merchant label -----------------------------------------------------------------------------------------
    private val stopWords =
        setOf(
            "on",
            "at",
            "with",
            "using",
            "via",
            "from",
            "for",
            "card",
            "ending",
            "ends",
            "a/c",
            "account",
            "acct",
            "avl",
            "avail",
            "available",
            "balance",
            "bal",
            "ref",
            "trx",
            "txn",
            "date",
            "dated",
            "is",
            "was",
            "has",
            "have",
            "are",
            "were",
            "will",
            "successful",
            "successfully",
            "completed",
            "approved",
            "declined",
            "and",
            "your",
            "you",
            "by",
            "in",
            "time",
            "thank",
            "thanks",
            "if",
            "until",
            "till",
            "valid",
            "confirm",
            "code",
            "pin",
            "otp",
            "expires",
            "remaining",
            "limit",
            "to",
            "the",
            "has",
            "been",
            "transaction",
            "purchase",
            "requires",
            "into",
            "via",
            "through",
            "لحساب",
            "خلال",
            "عبر",
            "طريق",
            "كاش",
            "كاشباك",
            "rrn",
            "tid",
            "mid",
            "terminal",
            "credited",
            "debited",
            "approval",
            "needs",
            "يوم",
            "reference",
            "paid",
            "id",
            "no",
            "بتاريخ",
            "بنجاح",
            "عبر",
            "من",
            "في",
            "الرصيد",
            "رصيدك",
            "رصيد",
            "المتاح",
            "بطاقه",
            "بطاقتك",
            "البطاقه",
            "حساب",
            "حسابك",
            "رقم",
            "كود",
            "رمز",
            "هو",
            "تم",
            "الي",
            "الى",
            "و",
            "ثم",
            "مع",
            "قيمه",
            "بمبلغ",
            "مبلغ",
            "الساعه",
            "وتم",
            "باستخدام",
            "المنتهيه",
            "ب",
        )
    private val badFirst =
        setOf(
            "your",
            "you",
            "account",
            "a/c",
            "acct",
            "card",
            "wallet",
            "the",
            "my",
            "iban",
            "an",
            "a",
            "this",
            "that",
            "it",
            "حساب",
            "حسابك",
            "بطاقه",
            "بطاقتك",
            "البطاقه",
            "محفظه",
            "محفظتك",
            "رقم",
            "مبلغ",
            "الحساب",
            "حسابكم",
        )
    private val trailingClause = Regex("^(?:وسيتم|وتم|وسوف|وستتم|ب(?:بطاقت|حساب|محفظت)(?:ك|كم))")
    private val secretLabel =
        Regex(
            "\\b(?:otp|o\\.t\\.p|pin|codes?|password|passcode|cvv|cvc|token|secret|verification|verify|one[- ]?time|3d ?secure|3ds|" +
                "activation|authenti[a-z]*)\\b|كود|رمز|تحقق|سري|كلمه المرور|كلمه السر|لا تشارك",
            ci,
        )
    private val enPrep =
        mapOf(
            "out" to Regex("(?<![\\p{L}\\d])(?:at|to)[ ]+|(?<![\\p{L}\\d])merchant\\s*:\\s*", ci),
            "transfer" to Regex("(?<![\\p{L}\\d])to[ ]+", ci),
            "in" to Regex("(?<![\\p{L}\\d])from[ ]+", ci),
        )
    private val arPrep =
        mapOf(
            "out" to Regex("(?<![\\p{L}\\d])(?:لدي|عند|الي|لصالح|ل)[ ]+"),
            "transfer" to Regex("(?<![\\p{L}\\d])(?:الي|لصالح|للمستفيد|ل)[ ]+"),
            "in" to Regex("(?<![\\p{L}\\d])من[ ]+"),
        )

    /** Boilerplate trailers are cut before anything is classified (to the end of their sentence). */
    private val trailer =
        Regex(
            "https?://\\S+|(?<!(?:at|لدى|لدي|عند|مطعم|مقهى|محل|شركة|شركه)\\s)(?:www\\.\\S+|(?:للمزيد|لمزيد|حمل\\s+تطبيق|زوروا|زور\\s" +
                "|احذر\\s+من|لمعرفه\\s+(?:المزيد|احدث)|لمعرفة\\s+(?:المزيد|احدث)" +
                "|اشترك\\s+في|استمتع\\s+ب|تابعنا|visit\\s|download\\s+(?:the\\s+)?(?:[\\p{L}]+\\s+){0,3}?app" +
                "|for\\s+more\\s+(?:info|information|details)" +
                "|call\\s+customer|customer\\s+service|follow\\s+us)(?:[^.!؟\\n]|\\.(?!\\s|$))*)",
            ci,
        )

    /**
     * Clause borders (principal clause vs fee note): sentence punctuation, the "... وتم خصم رسوم" form and an inline fee joined
     * by and / plus / with / including / و / شامل ("... from your account and a service fee of EGP 3.20 was charged").
     */
    private val clauseBorder =
        Regex(
            "(?<=[.;؛])\\s+|\\s+(?=و?تم\\s+(?:خصم|تحصيل)\\s+(?:رسوم|مصاريف|عموله))" +
                "|\\s+(?=(?:and|plus|with|including|incl\\.?|شامل)\\s+(?:(?:a|an|the|additional)\\s+)?" +
                "(?:[\\p{L}\\-]+\\s+){0,2}?(?:fees?|charges?|commission|رسوم|مصاريف|عموله)(?![\\p{L}]))" +
                "|\\s+(?=و(?:رسوم|مصاريف|عموله)(?![\\p{L}]))",
            ci,
        )

    /** Clause borders that survive only in the raw text: new lines, the Arabic comma and semicolon, " - " between sentences. */
    private val rawBorder =
        Regex("[\\r\\n]+|\\s*(?:(?<![0-9\u0660-\u0669\u06F0-\u06F9])[،؛]|[،؛](?![0-9\u0660-\u0669\u06F0-\u06F9]))\\s*|\\s+[-–—]\\s+")

    /** A clause whose head is a fee noun ("Transfer fee EGP 5.00", "رسوم تحويل 1.00 جنيه", "Cash advance fee of EGP 60.00 applies"). */
    private val feeHead =
        Regex(
            "^\\s*(?:(?:و?تم\\s+(?:خصم|تحصيل)|and|plus|with|including|incl|شامل|و)\\s*)?(?:(?:a|an|the|additional)\\s+)?" +
                "(?:[\\p{L}\\-]+\\s+){0,3}?(?:fees?|charges?|commission|رسوم|مصاريف|عموله)(?![\\p{L}])",
            ci,
        )

    /** A parenthetical that names a fee: "(EGP 1,000.00 transfer + EGP 5.00 fees)". */
    private val feeParenthetical = Regex("\\((?=[^)]*(?:fees?|charges?|رسوم|مصاريف|عموله))[^)]*\\)", ci)

    /** A long digit run that is not an amount ("Charges 482105 EGP"): never dropped silently with a fee note. */
    private val longDigitRun = Regex("(?<![\\d.,])\\d{5,}(?![\\d]|[.,]\\d)")

    private val strongVerb =
        Regex("\\btransfer(?:red)?\\b|\\bsent\\b|\\bsend\\b|withdraw|\\batm\\b|purchase|\\bpaid\\b|payment|تحويل|سحب|شراء|دفع|سداد", ci)

    /**
     * The PRINCIPAL clause is the first one with a transaction verb and an amount (account and card numbers are not amounts); a
     * clause headed by a fee noun is a fee note. Its amount is dropped (no second record) and it never changes the kind. A fee
     * clause BEFORE the principal is dropped only when the principal is a transfer, withdrawal, payment or purchase. A message
     * with no such principal ("Transfer fee of EGP 10.00 has been charged ...") stays a fee message.
     */
    private fun dropFeeNotes(input: String): FeeSplit {
        // "(EGP 1,000.00 transfer + EGP 5.00 fees)": the fee amounts go, the word "transfer" stays
        val norm =
            feeParenthetical
                .replace(
                    input,
                ) { if (transferVerbs.containsMatchIn(SecretDetector.fold(it.value))) " transfer " else " " }
                .trim()
        val borders = clauseBorder.findAll(norm).toList()
        if (borders.isEmpty()) return FeeSplit(norm, false)
        val ends = borders.map { it.range.first to it.range.last + 1 } + (norm.length to norm.length)
        val clauses = mutableListOf<String>()
        val separators = mutableListOf<String>()
        var start = 0
        for ((end, next) in ends) {
            clauses += norm.substring(start, end)
            separators += norm.substring(end, next)
            start = next
        }
        val folded = clauses.map { SecretDetector.fold(it) }
        val principal =
            folded.indices.firstOrNull { i ->
                !feeHead.containsMatchIn(folded[i]) && hasTransactionVerb(folded[i]) && findAmounts(folded[i]).any { !it.balance }
            } ?: return FeeSplit(norm, false)
        val out = StringBuilder()
        var pendingSeparator = ""
        var suspicious = false
        for (i in clauses.indices) {
            val droppable =
                feeHead.containsMatchIn(folded[i]) &&
                    !boughtFee.containsMatchIn(folded[i]) &&
                    (i > principal || strongVerb.containsMatchIn(folded[principal]))
            if (droppable) {
                if (longDigitRun.containsMatchIn(clauses[i])) suspicious = true
                // "... at ZOOBA incl. service charge": the connector belongs to the fee note
                val trimmed = connector.replace(out.toString(), "")
                out.setLength(0)
                out.append(trimmed)
            } else {
                out.append(pendingSeparator).append(clauses[i])
            }
            pendingSeparator = if (droppable && out.isNotEmpty() && !out.endsWith(".")) ". " else separators[i]
        }
        return FeeSplit(out.toString(), suspicious)
    }

    /** [suspicious]: a dropped fee note held a long digit run that is no amount; the message goes to review. */
    private class FeeSplit(
        val text: String,
        val suspicious: Boolean,
    )

    private val connector = Regex("[\\s.,]*(?<![\\p{L}\\d])(?:(?:incl|including|plus|and|with|و|شامل)[\\s.]*)?$", ci)

    private fun hasTransactionVerb(t: String) =
        outStrong.containsMatchIn(t) || transferVerbs.containsMatchIn(t) || withdrawal.containsMatchIn(t) || paymentWords.containsMatchIn(t)

    /** The first plausible amount in minor units, to prefill the manual form from a review item (never uploaded). */
    fun suggestAmountMinor(text: String): Long? {
        val t = SecretDetector.fold(TextNormalizer.normalizeText(text))
        return findAmounts(t).firstOrNull { !it.balance && !it.ambiguous }?.minor?.takeIf { it in 1..MAX_AMOUNT_MINOR }
    }

    /** Parses one message. [received] is the SMS received time or the notification post time. */
    fun parse(msg: IncomingMessage): ParseOutcome {
        // Secrets are looked for in the WHOLE text: a code cue inside a boilerplate trailer or URL still makes it a secret
        val full = TextNormalizer.normalizeText(msg.text).trim()
        // New lines, the Arabic comma and " - " are clause borders; normalization would turn them into plain spaces or commas, so the
        // raw text is split first and the normalized clauses are joined with a sentence border
        val clauses =
            msg.text
                .split(rawBorder)
                .map { TextNormalizer.normalizeText(it).trim().trimEnd('.', ';') }
                .filter { it.isNotBlank() }
        val joined = if (clauses.size > 1) clauses.joinToString(". ") else full
        val split = dropFeeNotes(trailer.replace(joined, " ").trim())
        val norm = split.text.trim()
        if (norm.isBlank()) return ParseOutcome.Ignore("empty")
        val t = SecretDetector.fold(norm)
        val secret = SecretDetector.reason(full)
        if (split.suspicious && secret == null) return ParseOutcome.Review("unexplained_number", SecretDetector.mask(full))
        val amounts = findAmounts(t)
        val hasOut = outAnchor.containsMatchIn(t)
        val hasIn = inAnchor.containsMatchIn(t)
        val moneyLike = amounts.any { !it.balance } && (hasOut || hasIn)
        if (secret != null) {
            // An OTP that reads like a purchase is kept (masked) for the user; a plain OTP is dropped without a trace.
            // a promo code in a discount offer ("use code VF50") is not a transaction either: dropped, nothing kept
            if (isOffer(t)) return ParseOutcome.Ignore("promo")
            // a completed top-up or transfer that merely carries a promo code in its footer is kept (masked), never dropped
            val completed = hasCompletedClause(t)
            return if (moneyLike || completed) ParseOutcome.Review("secret", SecretDetector.mask(full)) else ParseOutcome.Ignore("otp")
        }
        return classify(msg, norm, t, amounts, hasOut, hasIn)
    }

    private fun classify(
        msg: IncomingMessage,
        norm: String,
        t: String,
        amounts: List<Amount>,
        hasOut: Boolean,
        hasIn: Boolean,
    ): ParseOutcome {
        // A review item that merely looks code-like keeps its amounts but never a bare 4+ digit run.
        val codeMasked = if (SecretDetector.hasCodeLikeToken(norm)) SecretDetector.maskCodes(norm) else norm
        val ownDigits =
            last4Patterns.filter { it.own }.flatMap { p ->
                p.regex
                    .findAll(t)
                    .map { it.groups[1]!!.range }
                    .toList()
            }
        val malformed =
            amounts.filter { it.ambiguous }.map { a ->
                var end = a.end
                while (end < t.length && t[end] in "0123456789.,") end++
                a.start until end
            }
        val shown = maskRanges(codeMasked, unexplainedRanges(t, amounts) + ownDigits + malformed)
        val review = { reason: String -> ParseOutcome.Review(reason, shown) }
        if (declined.containsMatchIn(t)) return ParseOutcome.Ignore("declined")
        if (reversal.containsMatchIn(t)) return review("reversal")
        // A card statement or payment reminder is never a transaction, even if it says "ignore if paid".
        if (statementStrong.containsMatchIn(t) && !transactionHeadline.containsMatchIn(t)) return ParseOutcome.Ignore("info")
        if (ambiguousReceipt.containsMatchIn(t)) return review("ambiguous_direction")
        if (!hasOut && !hasIn) {
            return when {
                statement.containsMatchIn(t) -> ParseOutcome.Ignore("info")
                promoWords.containsMatchIn(t) -> ParseOutcome.Ignore("promo")
                else -> review("no_template")
            }
        }
        if (promoWords.containsMatchIn(t) && !strongTransaction(t)) {
            // Safety net: a message with an amount and a debit/credit verb is never dropped silently.
            // (an offer's "get EGP 50 cashback credited to your account" is a promise, not an own-account movement)
            val ownRef = hasOwnRef(t)
            if (hasCompletedClause(t)) return review("promo_with_transaction")
            // A discount offer's own words (خصم, دفع, شراء) are not a completed transaction; only a credit or a transfer verb is
            // and neither is the offer's condition: "your first InstaPay transfer", "when you transfer EGP 1,000"
            val offer = offerCondition.replace(t, " ")
            val verb = explicitIn.containsMatchIn(offer) || transferVerbs.containsMatchIn(offer)
            return if ((verb || ownRef) && amounts.any { !it.balance }) review("promo_with_transaction") else ParseOutcome.Ignore("promo")
        }
        val direction =
            when (decideDirection(t)) {
                Dir.IN -> false
                Dir.OUT -> true
                Dir.CONFLICT -> return review("direction_conflict")
                Dir.NONE -> return review("no_template")
            }
        val amount = chooseAmount(t, amounts) ?: return review("no_amount")
        if (amount.ambiguous || amount.minor == null) return review("ambiguous_amount")
        val currency = amount.currency ?: return review("unsupported_currency")
        if (currency != "EGP") return review("unsupported_currency")
        if (amount.bareEgp && foreignWord.containsMatchIn(t)) return review("unsupported_currency")
        if (amount.minor !in 1..MAX_AMOUNT_MINOR) return review("out_of_range")
        val kind = kindOf(t, direction)
        if (hasUnexplainedNumber(t, amounts)) return review("unexplained_number")
        val sender = msg.sender
        val institution = institutionOf(sender)
        val (occurredAtMillis, timeKnown) = occurredAt(t, msg.receivedAtMillis)
        val record =
            ParsedRecord(
                localId = "", // keyed by LocalIdHasher in the pipeline, never by the parser
                source = msg.source,
                kind = kind,
                amountMinor = amount.minor,
                currency = currency,
                occurredAtMillis = occurredAtMillis,
                timeKnown = timeKnown,
                instrumentLast4 = last4(t, kind, direction),
                senderId = uploadSenderId(sender, institution),
                institution = institution,
                paymentMethod = paymentMethod(sender, institution, t, kind),
                merchant = merchant(norm, t, kind),
            )
        // Confidence gate: only a proven family is uploaded without a human; the rest is a review item with this as its suggestion
        if (AutoRecordFamilies.family(msg, norm, t, record) != null) return ParseOutcome.Record(record)
        return ParseOutcome.Review("unconfirmed", shown, record)
    }

    /** The condition of an offer: "your first InstaPay transfer", "when you transfer", "عند الدفع". Not a completed transaction. */
    private val offerCondition =
        Regex(
            "\\b(?:first|next|any|every|each)\\s+(?:[\\p{L}]+\\s+){0,2}?(?:transfers?|payments?|purchases?|orders?)\\b" +
                "|\\b(?:when|if|once)\\s+you\\s+\\p{L}+|عند\\s+(?:ال)?(?:تحويل|دفع|شراء)|(?:اول|اي|كل)\\s+(?:عمليه\\s+)?(?:تحويل|دفع|شراء)",
            ci,
        )

    /** "You will get EGP 50 cashback credited to your account": a promise in the future, not an own-account movement. */
    private val offerPromise =
        Regex("\\bwill\\s+(?:get|receive)\\b|\\bget\\s+(?:egp\\s*)?[\\d.,]+\\s*(?:egp\\s*)?(?:cashback|off)\\b|احصل\\s+علي", ci)

    private fun hasOwnRef(t: String): Boolean =
        !offerPromise.containsMatchIn(t) &&
            (sourceIsOwn.containsMatchIn(t) || destinationIsOwn.containsMatchIn(t) || ownSubjectCredited.containsMatchIn(t))

    private val completedVerb =
        Regex(
            "تم\\s+(?:شحن|تحويل|ايداع|استلام|استقبال|اضافه|سحب|دفع|سداد|شراء|خصم)|" +
                "\\b(?:was|has\\s+been|have\\s+been)\\s+(?:credited|debited|charged|sent|transferred|topped\\s+up|paid)\\b",
            ci,
        )

    /** A completed transaction in a clause of its own that carries no offer word: a top-up with a promo footer is still a top-up. */
    private fun hasCompletedClause(t: String): Boolean =
        t.split(Regex("(?<=\\.)\\s+")).any { c ->
            !promoWords.containsMatchIn(c) && completedVerb.containsMatchIn(c) && c.any { it.isDigit() }
        }

    /** A discount or cashback offer: an offer word, no completed transaction verb and no own-account reference. */
    private fun isOffer(t: String): Boolean =
        promoWords.containsMatchIn(t) &&
            !strongTransaction(t) &&
            !hasCompletedClause(t) &&
            !hasOwnRef(t) &&
            !(explicitIn.containsMatchIn(offerCondition.replace(t, " ")) || transferVerbs.containsMatchIn(offerCondition.replace(t, " ")))

    /** "your next purchase", "first payment": an offer's future condition, not a completed transaction. */
    private val futurePurchase = Regex("\\b(?:next|first|any|every|each|future)\\s+(?:online\\s+)?(?:purchases?|payments?)\\b", ci)

    private fun strongTransaction(text: String): Boolean {
        val t = futurePurchase.replace(text, " ")
        // "Cashback of EGP 25.00 has been credited to your account" is a completed credit; "get EGP 100 cashback credited" is an offer
        return strongTransactionWords(t) || (completedCredit.containsMatchIn(t) && !offerLead.containsMatchIn(t))
    }

    private val completedCredit = Regex("\\b(?:was|has been|have been)\\s+credited\\b|\\bcredited\\s+(?:to|in|into)\\s+your\\b", ci)
    private val offerLead = Regex("\\b(?:get|earn|win|spend|when|if|offer|enjoy)\\b|\\bwill\\s+(?:get|receive)\\b|احصل|اربح|عرض", ci)

    private fun strongTransactionWords(t: String) =
        Regex(
            "\\b(?:debited|purchase|paid|spent|withdrawn|charged|used for|(?:has|have) been used|was used)\\b" +
                "|تم خصم|تم شراء|تم دفع|تم سحب|تم استخدام",
            ci,
        ).containsMatchIn(t)

    // ---- direction: ROLE phrases only; no role, or two roles, is a review item ---------------------------------------------
    private enum class Dir { IN, OUT, CONFLICT, NONE }

    private const val CARD_AT =
        "عمليه[^;!?]{0,60}?ب(?:حساب|بطاقت)(?:ك|كم)[^;!?]{0,80}?(?<![\\p{L}\\d])(?:لدي|عند)\\s+\\p{L}" +
            "|(?<![\\p{L}\\d])(?:ب|على)\\s*بطاقت(?:ك|كم)[^;!?]{0,60}?(?<![\\p{L}\\d])(?:لدي|عند)\\s+\\p{L}" +
            "|\\bon\\s+your\\s+(?:[\\p{L}]+\\s+){0,2}?card\\b[^;!?]{0,40}?\\bat\\s+\\p{L}" +
            "|\\b(?:was|been)\\s+used\\s+at\\b|\\b(?:charge|authorization)\\s+of\\b"
    private const val OWN_NOUN = "(?:حساب|محفظت|بطاقت)(?:ك|كم)"
    private const val OWN_EN = "your\\s+(?:[\\p{L}]+\\s+){0,2}?(?:account|a/c|acct|wallet|card)"

    /** The household's own account/wallet/card as the DESTINATION of a movement. */
    private val destinationIsOwn =
        Regex(
            "(?<![\\p{L}\\d])(?:ل|الي|في|على|لصالح)\\s*$OWN_NOUN(?!\\p{L})" +
                // ب + own noun is "with/in your card" (the instrument) unless a credit word comes first: "ايداع ... بحسابكم"
                "|(?:ايداع|اضافه|استلام|ائتمان|تحويل)[^;!?]{0,40}?(?<![\\p{L}\\d])ب\\s*$OWN_NOUN(?!\\p{L})" +
                "|\\b(?:to|into|in|on)\\s+your\\s+(?:[\\p{L}]+\\s+){0,2}?(?:account|a/c|acct|wallet|card)\\b",
            ci,
        )

    /** The household's own account/wallet/card as the SOURCE. */
    private val sourceIsOwn =
        Regex("(?<![\\p{L}\\d])من\\s*$OWN_NOUN(?!\\p{L})|\\bfrom\\s+$OWN_EN\\b", ci)

    /** "Your account ... was credited": the household's account is the one that received the money. */
    private val ownSubjectCredited =
        Regex(
            "$OWN_EN\\b(?:(?!\\b(?:to|from)\\b)[^.()]){0,30}?" +
                "\\b(?:(?:(?:was|has been|have been|is|were)\\s+)?credited|received\\s+(?:an?\\b|egp\\b|\\d))",
            ci,
        )

    /** Someone else as the destination: "to AHMED ALI", "الى فلان", "لحساب AHMED", "لرقم 010...", "لصالح X". */
    private val thirdPartyNamed =
        Regex(
            "\\b(?:to|towards)\\s+(?!(?:your|you|the|his|her|beneficiary|account|a/c|acct|card|wallet)\\b)(?![^\\s]*[*])\\p{L}" +
                "|\\b(?:to|towards)\\s+(?:\\+?20|0)1[0125]\\d{8}" +
                "|(?<![\\p{L}\\d])(?:الي|لصالح)\\s+(?!$OWN_NOUN|حساب(?![\\p{L}])|رقم|\\d|[*])\\S" +
                "|(?<![\\p{L}\\d])(?:الي|ل)\\s*(?:رقم\\s*)?(?:\\+?20|0)1[0125]\\d{8}" +
                "|(?<![\\p{L}\\d])(?:الي|ل)\\s*حساب\\s+(?!رقم|\\d|[*xX•])\\p{L}",
            ci,
        )

    /** Verbs that mean money left the household by themselves. A plain "transfer" is not one of them. */
    private val outStrong =
        Regex(
            "\\b(?:debited|debit(?!\\s+card)|deducted|withdraw(?:n|al)?|charged|spent|purchased?|used for|pos)\\b" +
                "|\\bpaid(?!\\s+(?:to\\s+)?you)\\b" +
                "|\\btransaction (?:of|was made|has been made|approved|completed)\\b" +
                "|تم خصم|خصم(?!\\s*\\d+\\s*%)|تم دفع|دفع|شراء|سداد|سحب|صادر|مدفوعات|تم استخدام" +
                // "عملية ببطاقتكم/بحسابكم ... لدى MERCHANT": a card or account operation at a named merchant is a purchase
                "|$CARD_AT",
            ci,
        )

    /** "Payment received (from X)" is incoming; "your payment ... has been received" is OUR payment being acknowledged. */
    private val paymentReceived = Regex("\\bpayment\\b(?:\\s+of\\s+[^;!?]{0,30}?)?\\s*:?\\s*(?:was\\s+|has\\s+been\\s+)?received\\b", ci)
    private val ourPayment = Regex("\\byour\\s+(?:[\\p{L}]+\\s+){0,2}?payment\\b", ci)
    private val paymentWord = Regex("\\bpayment\\b", ci)

    private val explicitIn =
        Regex(
            "you(?: have)? received|\\breceived from\\b|\\bmoney received\\b|\\bincoming\\b|\\brefund(?:ed)?\\b|\\bsalary\\b" +
                "|\\b(?:paid|sent|transferred)\\s+(?:to\\s+)?you\\b|\\bto\\s+you\\s+by\\b|\\breceived\\s+(?:to|into|in)\\s+your\\b" +
                "|(?<![\\p{L}\\d])لكم(?![\\p{L}])|تم استلام|تم استقبال|استقبال تحويل|استلمت|وارد|راتب|مرتب|تم رد|رد مبلغ|استرداد|مرتجع" +
                "|\\d(?:[.,]\\d+)?\\+(?=\\s|$)",
            ci,
        )

    private val minusSign = Regex("\\d(?:[.,]\\d+)?-(?=\\s|$)")

    private val youDid = Regex("\\byou(?:\\s+have)?\\s+(?:transferred|sent|paid)\\b", ci)

    /** Something the household started itself: "Your InstaPay transfer ...", "You made a transfer ...". */
    private val ownInitiated =
        Regex(
            "\\btransfer\\b[^;!?]{0,80}\\b(?:via|at|through)\\s+atm\\b|تحويل[^;!?]{0,80}(?:ماكينه|atm)" +
                "|\\byour\\s+(?:instapay\\s+)?transfer\\b|\\byou\\s+(?:made|did|initiated|requested)\\s+(?:an?\\s+)?(?:[\\p{L}]+\\s+)?transfer\\b",
            ci,
        )

    private fun decideDirection(t: String): Dir {
        // "... وسيتم إضافة كاش باك", "will be credited ...": a promised future credit is not a signal
        val now = futureClause.replace(t, " ")
        // the household's own credit card is the beneficiary of a payment: money leaving the account, never income
        if (cardRepayment.containsMatchIn(now)) return Dir.OUT
        val debit =
            outStrong.containsMatchIn(now) ||
                (paymentWord.containsMatchIn(now) && !paymentReceived.containsMatchIn(now)) ||
                ourPayment.containsMatchIn(now)
        val transfer = transferVerbs.containsMatchIn(now)
        val ownSource = sourceIsOwn.containsMatchIn(now)
        val named = thirdPartyNamed.containsMatchIn(now)
        val hardDebit = outStrong.containsMatchIn(now)
        val ownDest =
            destinationIsOwn.containsMatchIn(now) && !hardDebit && !paymentContext.containsMatchIn(now) && !ourPayment.containsMatchIn(now)
        val strongIn =
            explicitIn.containsMatchIn(now) ||
                ownSubjectCredited.containsMatchIn(now) ||
                (paymentReceived.containsMatchIn(now) && !ourPayment.containsMatchIn(now))
        // credited / deposited / إيداع / إضافة count only with the household's own possessive (ownDest / ownSubjectCredited)
        val anyIn = strongIn || ownDest
        val anyOut =
            debit ||
                ownSource ||
                (transfer && named) ||
                minusSign.containsMatchIn(now) ||
                youDid.containsMatchIn(now) ||
                ownInitiated.containsMatchIn(now)
        return when {
            anyIn && anyOut -> if (isOwnToOwn(ownSource, ownDest, strongIn, debit)) Dir.OUT else Dir.CONFLICT
            anyIn -> Dir.IN
            anyOut -> Dir.OUT
            transfer -> Dir.CONFLICT // a transfer that says nothing about who gave or received
            else -> Dir.NONE
        }
    }

    /** Own account on both ends and nothing else: money moved between the household's own accounts. */
    private fun isOwnToOwn(
        ownSource: Boolean,
        ownDest: Boolean,
        strongIn: Boolean,
        debit: Boolean,
    ) = ownSource && ownDest && !strongIn && !debit

    private val futureClause = Regex("(?<![\\p{L}])(?:وسيتم|سيتم|وسوف|سوف|will(?: be)?)(?![\\p{L}])[^.!?؟\\n]*", ci)
    private val paymentContext = Regex("\\bbill\\b|فاتوره|سداد|شحن", ci)

    /** Fee wording decides only when it comes first: "تحويل ... رسوم الخدمة 1 جنيه" is a transfer that mentions its fee. */
    private val transferVerbs = Regex("\\btransfer(?:red)?\\b|\\bsent\\b|\\bsend\\b|تحويل|ارسال|ارسلت|حولت", ci)

    private val feeNoun =
        Regex(
            "\\b(?:instapay transfer|transfer|instapay|sms|card|service|annual|monthly|maintenance)\\s+(?:fees?|charges?|commission)\\b" +
                "|\\b(?:fees?|commission)\\b" +
                "|(?<![\\p{L}])(?:مصاريف|رسوم|عموله)(?:\\s+(?:تحويل(?:\\s+لحظي)?|الخدمه|شهريه|سنويه|الصيانه|بطاقه|البطاقه))?(?![\\p{L}])",
            ci,
        )

    /** A fee noun that names what was bought: an education payee. Membership/registration/SMS/service fees are bank charges. */
    private val boughtFee =
        Regex(
            "\\b(?:school|tuition|university|college)\\s+(?:fees?|payment)\\b" +
                "|(?:رسوم|مصاريف)\\s+(?:دراسيه|المدرسه|الجامعه|مدرسيه)",
            ci,
        )

    /** A merchant payee ("لدى X", "عند X") or a payment verb means something was paid for, not a bank charge. */
    private val payeeOrPayment = Regex("(?<![\\p{L}\\d])(?:لدي|عند)\\s+\\p{L}|\\bpaid\\b|\\bpayment\\b|سداد|تم دفع", ci)

    /** A named bank charge: it is a fee even when the text mentions the payment or transfer it belongs to. */
    private val bankFeeNoun =
        Regex(
            "\\b(?:late\\s+payment|payment\\s+processing|bill\\s+payment|cash\\s+advance|transfer|instapay|sms(?:\\s+alert)?|" +
                "service|annual|monthly|maintenance|card)\\s+(?:fees?|charges?)\\b|\\binterest\\s+(?:of|charges?)\\b" +
                "|(?<![\\p{L}])(?:رسوم|مصاريف|عموله)\\s+(?:(?:تاخير|تاخر)\\s+)?(?:سداد|تحويل|تاخير|سحب|رسايل|رسائل|خدمه|صيانه)" +
                "(?![\\p{L}])",
            ci,
        )

    private fun feeIsTheTransaction(t: String): Boolean {
        if (bankFeeNoun.containsMatchIn(t) && !boughtFee.containsMatchIn(t)) return true
        if (boughtFee.containsMatchIn(t) || thirdPartyNamed.containsMatchIn(t) || payeeOrPayment.containsMatchIn(t)) return false
        val feeAt = fee.find(t)?.range?.first ?: return false
        val other = listOfNotNull(transferVerbs.find(t)?.range?.first, paymentWords.find(t)?.range?.first)
        if (other.none { it < feeAt }) return true
        // "InstaPay transfer fee deducted", "مصاريف تحويل لحظي": take the fee noun phrase out; if no separate transfer or
        // payment is left, the fee itself is the transaction
        val rest = feeNoun.replace(t, " ")
        return !transferVerbs.containsMatchIn(rest) && !paymentWords.containsMatchIn(rest)
    }

    /** Paying the household's own credit card is a movement between its own accounts (PROMPT 4.3), not new spending. */
    private val cardRepayment =
        Regex(
            "credit\\s*card\\s+(?:payment|bill|repayment)" +
                "|payment\\s+(?:of\\s+[^;!?]{0,30}?)?(?:to|for|towards)\\s+your\\s+(?:[\\p{L}]+\\s+){0,2}?credit\\s*card" +
                "|سداد\\s+(?:ال)?بطاق[هة]\\s+(?:ال)?ائتمان|دفع\\s+(?:ال)?بطاق[هة]\\s+(?:ال)?ائتمان" +
                // the card is the OBJECT of the repayment (ل / الى before it); "باستخدام / ب + بطاقة الائتمان" is the instrument
                "|(?:سداد|تسديد|دفع)\\s+(?:مستحقات\\s+)?بطاق(?:ه|تك|تكم)\\s*(?:ال)?ائتمان" +
                "|(?:سداد|تسديد|دفع)\\s[^;!?]{0,40}?(?:(?<![\\p{L}\\d])(?:لل|ل(?:ال)?)|(?<![\\p{L}])الي\\s+(?:ال)?)" +
                "\\s*بطاق(?:ه|تك|تكم)\\s*(?:ال)?ائتمان" +
                // the card is the BENEFICIARY of the payment: "payment received for your credit card", "credit card payment received"
                "|payment\\s+(?:was\\s+|has\\s+been\\s+)?received\\s+(?:for|to|towards)\\s+your\\s+(?:[\\p{L}]+\\s+){0,2}?credit\\s*card" +
                "|received\\s+your\\s+payment\\s+(?:of\\s+[^;!?]{0,30}?)?(?:for|to|towards)\\s+(?:your\\s+)?(?:[\\p{L}]+\\s+){0,2}?credit\\s*card",
            ci,
        )

    private val cashWord = Regex("withdraw|سحب|cash", ci)

    /** "Transfer ... via ATM" is a transfer, not cash taken out. */
    private fun atmTransfer(t: String) = transferVerbs.containsMatchIn(t) && !cashWord.containsMatchIn(t)

    private fun kindOf(
        t: String,
        outgoing: Boolean,
    ): TxKind =
        when {
            refund.containsMatchIn(t) -> TxKind.REFUND
            outgoing && bankFeeNoun.containsMatchIn(t) && feeIsTheTransaction(t) -> TxKind.FEE
            withdrawal.containsMatchIn(t) && outgoing && !atmTransfer(t) -> TxKind.CASH_WITHDRAWAL
            outgoing && feeIsTheTransaction(t) -> TxKind.FEE
            outgoing && cardRepayment.containsMatchIn(t) -> TxKind.TRANSFER_OUT
            !outgoing -> if (ownAccount.containsMatchIn(t)) TxKind.TRANSFER_IN else TxKind.INCOME
            transferWords.containsMatchIn(t) && !paymentWords.containsMatchIn(t) -> TxKind.TRANSFER_OUT
            else -> TxKind.PURCHASE
        }

    // ---- amounts -------------------------------------------------------------------------------------------------
    private fun findAmounts(t: String): List<Amount> {
        val out = mutableListOf<Amount>()
        val taken = mutableSetOf<Int>()

        fun add(
            numRange: IntRange,
            span: IntRange,
            number: String,
            currency: String?,
            bare: Boolean,
        ) {
            if (!taken.add(numRange.first)) return
            val before = t.substring(maxOf(0, span.first - LOOKBACK), span.first)
            // the digits of "card ending 4512" are an instrument number, never an amount, even when a currency follows
            if (lastFourMarker.containsMatchIn(before)) return
            val after = t.substring(minOf(t.length, span.last + 1))
            val minor = toMinor(number)
            out +=
                Amount(
                    span.first,
                    span.last + 1,
                    minor,
                    currency,
                    balanceBefore.containsMatchIn(before),
                    bare,
                    minor == null || groupedDigits.containsMatchIn(after) || continuesWithSeparator.containsMatchIn(after),
                )
        }
        for (m in currencyThenNumber.findAll(t)) add(m.groups[2]!!.range, m.range, m.groupValues[2], currencyOf[m.groupValues[1]], false)
        for (m in numberThenCurrency.findAll(t)) add(m.groups[1]!!.range, m.range, m.groupValues[1], currencyOf[m.groupValues[2]], false)
        for (m in keywordAmount.findAll(t)) {
            val numRange = m.groups[1]!!.range
            val next = tokenAfter.find(t.substring(numRange.last + 1))?.groupValues?.get(1)
            val currency =
                when {
                    next == null || next in amountStopWords -> "EGP"
                    else -> currencyOf[next]
                }
            add(numRange, m.range, m.groupValues[1], currency, bare = next == null || next in amountStopWords)
        }
        return out.sortedBy { it.start }
    }

    private fun chooseAmount(
        t: String,
        amounts: List<Amount>,
    ): Amount? {
        val usable = amounts.filter { !it.balance }
        if (usable.isEmpty()) return null
        val anchorEnds = (outAnchor.findAll(t) + inAnchor.findAll(t)).map { it.range.last + 1 }.toList()
        return usable
            .filter { a -> anchorEnds.any { it <= a.start } }
            .minByOrNull { a -> a.start - anchorEnds.filter { it <= a.start }.max() }
            ?: usable.first()
    }

    internal fun minorOf(raw: String): Long? = toMinor(raw)

    private fun toMinor(raw: String): Long? {
        val s =
            when {
                Regex(
                    "\\d\\.\\d{3},\\d",
                ).containsMatchIn(raw) ||
                    Regex("^\\d+,\\d{1,2}$").matches(raw) -> raw.replace(".", "").replace(',', '.')
                raw.contains(',') && !raw.contains('.') && Regex(",\\d{3}$").containsMatchIn(raw) -> raw.replace(",", "")
                else -> raw.replace(",", "")
            }
        val grouped = if (Regex("^\\d{1,3}(\\.\\d{3})+$").matches(s)) s.replace(".", "") else s
        return try {
            val value = BigDecimal(grouped)
            if (value.scale() > 2) null else value.movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    // ---- leftover digits ----------------------------------------------------------------------------------------

    /** After removing everything the engine understands, a long digit run is a code or an account number: do not trust it. */
    private fun hasUnexplainedNumber(
        t: String,
        amounts: List<Amount>,
    ): Boolean = unexplainedRanges(t, amounts).isNotEmpty()

    /** Positions (in the folded text, which lines up with the original) of every 4+ digit run nothing explains. */
    private fun unexplainedRanges(
        t: String,
        amounts: List<Amount>,
    ): List<IntRange> {
        val chars = t.toCharArray()

        fun blank(range: IntRange) {
            for (i in range) if (i < chars.size) chars[i] = ' '
        }
        amounts.forEach { blank(it.start until it.end) }

        fun blankDate(
            m: MatchResult,
            yearGroup: Int,
        ) {
            blank(m.range)
            val year = m.groupValues[yearGroup]
            // "Oct 21, 7351": a four-digit year nobody could mean is a number nothing explains
            if (year.length == 4 && year.toInt() !in PLAUSIBLE_YEARS) for (i in m.groups[yearGroup]!!.range) chars[i] = t[i]
        }
        dateFull.findAll(t).forEach { blankDate(it, 4) }
        dateIso.findAll(t).forEach { blankDate(it, 1) }
        dateShort.findAll(t).forEach { blank(it.range) }
        Regex("(?<![\\d:])\\d{1,2}:\\d{2}(?::\\d{2})?(?![\\d:])").findAll(t).forEach { blank(it.range) }
        last4Patterns.forEach { p ->
            p.regex.findAll(t).forEach { m -> if (!p.own || bareAccepted(t, m.range.first)) blank(m.groups[1]!!.range) }
        }
        dateCompact.findAll(t).forEach { blankDate(it, 3) }
        dateMonthFirst.findAll(t).forEach { blankDate(it, 3) }
        // The other party's mobile number after to/from/إلى رقم is an explained counterparty, never a code.
        phoneNumber.findAll(t).forEach { m ->
            if (counterpartyBefore.containsMatchIn(t.substring(maxOf(0, m.range.first - LAST4_LOOKBACK), m.range.first))) blank(m.range)
        }
        val rest = SecretDetector.stripReferences(String(chars))
        return Regex("(?<![\\d])\\d{4,}(?![\\d])").findAll(rest).map { it.range }.toList()
    }

    /** Every digit of the given ranges becomes a bullet: a review item never shows a number nothing explains. */
    private fun maskRanges(
        text: String,
        ranges: List<IntRange>,
    ): String {
        val chars = text.toCharArray()
        for (r in ranges) for (i in r) if (i < chars.size && chars[i].isDigit()) chars[i] = '•'
        return String(chars)
    }

    // ---- last four / date / ids -------------------------------------------------------------------------------------

    /** A bare number after "your account" is trusted only when nothing in the message hints at a secret or a security footer. */
    private fun ownAccountTrusted(t: String): Boolean = !SecretDetector.hasHintWord(t) && !SecretDetector.hasSecurityFooter(t)

    /** The bare own-account number is accepted only in the instant-transfer shape: a transfer word comes before it. */
    private fun bareAccepted(
        t: String,
        start: Int,
    ): Boolean = ownAccountTrusted(t) && transferVerbs.containsMatchIn(t.substring(0, start))

    private enum class Role { SOURCE, DESTINATION, SUBJECT }

    private class Last4Candidate(
        val start: Int,
        val digits: String,
        val bare: Boolean,
        val own: Boolean,
        val role: Role,
    )

    private val sourcePrefix = Regex("(?:\\bfrom|(?<![\\p{L}\\d])من)\\s*$", ci)
    private val destinationPrefix = Regex("(?:\\bto|\\binto|\\bin|(?<![\\p{L}\\d])(?:الي|في|لصالح|ل))\\s*$", ci)

    private val bareSource = Regex("^(?:من|from)(?![\\p{L}])", ci)
    private val bareDestination = Regex("^(?:الي|to)(?![\\p{L}])", ci)

    private fun roleOf(
        t: String,
        start: Int,
    ): Role {
        val prefix = t.substring(maxOf(0, start - ROLE_LOOKBACK), start)
        return when {
            sourcePrefix.containsMatchIn(prefix) -> Role.SOURCE
            destinationPrefix.containsMatchIn(prefix) -> Role.DESTINATION
            else -> Role.SUBJECT
        }
    }

    /**
     * WHITELIST: for a transfer the instrument is a number attached to the household's own noun phrase (your card,
     * حسابكم ...). A counterparty's account, whatever the name or the wording around it, is never accepted. Two own
     * candidates: outgoing takes the source role, incoming the destination role, otherwise none. Purchases, withdrawals,
     * fees and refunds also accept a plain "card ending NNNN".
     */
    private fun last4(
        t: String,
        kind: TxKind,
        outgoing: Boolean,
    ): String? {
        val transferKind = kind == TxKind.TRANSFER_OUT || kind == TxKind.TRANSFER_IN || kind == TxKind.INCOME
        val repayment = kind == TxKind.TRANSFER_OUT && cardRepayment.containsMatchIn(t)
        val candidates =
            last4Patterns.flatMap { p ->
                p.regex
                    .findAll(t)
                    .map { m ->
                        val role =
                            when {
                                m.value.startsWith("ل") -> Role.DESTINATION
                                !p.own -> roleOf(t, m.range.first)
                                bareSource.containsMatchIn(m.value) -> Role.SOURCE
                                bareDestination.containsMatchIn(m.value) -> Role.DESTINATION
                                else -> roleOf(t, m.range.first)
                            }
                        Last4Candidate(m.range.first, m.groupValues[1], p.own, p.ownExplicit || p.own, role)
                    }.toList()
            }
        val accepted =
            candidates.filter { c ->
                when {
                    c.bare -> bareAccepted(t, c.start)
                    c.own -> true
                    // an explicitly incoming transfer "to account ***7781" with no other account in the text: that is ours
                    // repaying the household's own credit card: "credit card ending 4512" is the household's instrument
                    else ->
                        !transferKind ||
                            repayment ||
                            (!outgoing && c.role == Role.DESTINATION && candidates.size == 1)
                }
            }
        val distinct = accepted.map { it.digits }.distinct()
        if (distinct.size <= 1) return distinct.singleOrNull()
        val preferred = if (outgoing) Role.SOURCE else Role.DESTINATION
        val byRole = accepted.filter { it.role == preferred }.map { it.digits }.distinct()
        if (byRole.size == 1) return byRole.first()
        return accepted
            .filter { it.role == Role.SUBJECT }
            .map { it.digits }
            .distinct()
            .singleOrNull()
    }

    private class DateCandidate(
        val start: Int,
        val range: IntRange,
        val dates: List<LocalDate>,
    )

    /** When it happened, and whether that instant carries a time of day (false: a date-only message, stored at Cairo noon). */
    private fun occurredAt(
        t: String,
        receivedMillis: Long,
    ): Pair<Long, Boolean> {
        val received = Instant.ofEpochMilli(receivedMillis)
        val receivedDate = received.atZone(cairo).toLocalDate()
        val earliest = received.minusSeconds(MAX_DATE_AGE_DAYS * SECONDS_PER_DAY)
        val latest = received.plusSeconds(SECONDS_PER_DAY)
        val found = mutableListOf<DateCandidate>()
        dateFull.findAll(t).forEach { m ->
            val first = m.groupValues[1].toInt()
            val second = m.groupValues[3].toInt()
            val yearText = m.groupValues[4]
            val y = if (yearText.length == 2) TWO_DIGIT_YEAR_BASE + yearText.toInt() else yearText.toInt()
            // DD/MM/YYYY is Egyptian; a two-digit year with "/" may also be M/D/YY (some banks): both readings are tried
            // and the window decides, so "10/15/26" is 15 Oct and "10/6/26" is 6 Oct, not 10 Jun.
            val readings =
                if (yearText.length == 2 && m.groupValues[2] == "/") {
                    listOfNotNull(safeDate(y, second, first), safeDate(y, first, second)).distinct()
                } else {
                    listOfNotNull(safeDate(y, second, first))
                }
            found += DateCandidate(m.range.first, m.range, readings)
        }
        dateIso.findAll(t).forEach { m ->
            found +=
                DateCandidate(
                    m.range.first,
                    m.range,
                    listOfNotNull(safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())),
                )
        }
        dateCompact.findAll(t).forEach { m ->
            val yearText = m.groupValues[3]
            val y = if (yearText.length == 2) TWO_DIGIT_YEAR_BASE + yearText.toInt() else yearText.toInt()
            val month = monthNames.indexOf(m.groupValues[2].lowercase(Locale.ROOT)) + 1
            found += DateCandidate(m.range.first, m.range, listOfNotNull(safeDate(y, month, m.groupValues[1].toInt())))
        }
        dateMonthFirst.findAll(t).forEach { m ->
            val month = monthNames.indexOf(m.groupValues[1].lowercase(Locale.ROOT)) + 1
            found +=
                DateCandidate(m.range.first, m.range, listOfNotNull(safeDate(m.groupValues[3].toInt(), month, m.groupValues[2].toInt())))
        }
        dateShort.findAll(t).forEach { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            var date = safeDate(receivedDate.year, mo, d)
            if (date != null && date.isAfter(receivedDate.plusDays(1))) date = safeDate(receivedDate.year - 1, mo, d)
            found += DateCandidate(m.range.first, m.range, listOfNotNull(date))
        }
        for (c in found.sortedBy { it.start }) {
            if (c.dates.isEmpty()) continue
            if (dateNot.containsMatchIn(t.substring(maxOf(0, c.start - DATE_LOOKBACK), c.start))) continue
            val inWindow =
                c.dates
                    .mapNotNull { date -> instantOf(t, c.range, date, received, receivedDate) }
                    .filter { !it.first.isBefore(earliest) && !it.first.isAfter(latest) }
                    .distinctBy { it.first }
            // Exactly one reading inside the window decides; two (or none) leave the received time.
            if (inWindow.size == 1) return inWindow.first().first.toEpochMilli() to inWindow.first().second
        }
        return receivedMillis to true
    }

    private fun instantOf(
        t: String,
        range: IntRange,
        date: LocalDate,
        received: Instant,
        receivedDate: LocalDate,
    ): Pair<Instant, Boolean>? {
        val after = timeAfter.find(t.substring(range.last + 1, minOf(t.length, range.last + 1 + TIME_WINDOW)))
        val time = after ?: timeBefore.find(t.substring(maxOf(0, range.first - TIME_BEFORE_WINDOW), range.first))
        return when {
            time != null -> toInstant(date, time)?.let { it to true }
            date == receivedDate -> received to true
            else -> date.atTime(LocalTime.of(NOON, 0)).atZone(cairo).toInstant() to false
        }
    }

    private fun toInstant(
        date: LocalDate,
        time: MatchResult,
    ): Instant? =
        try {
            var hour = time.groupValues[1].toInt()
            val marker = time.groupValues[3].lowercase(Locale.ROOT)
            if ((marker == "pm" || marker == "م") && hour < NOON_HOURS) hour += NOON_HOURS
            if ((marker == "am" || marker == "ص") && hour == NOON_HOURS) hour = 0
            LocalDateTime.of(date, LocalTime.of(hour, time.groupValues[2].toInt())).atZone(cairo).toInstant()
        } catch (_: DateTimeException) {
            null
        }

    private fun safeDate(
        y: Int,
        m: Int,
        d: Int,
    ): LocalDate? =
        try {
            LocalDate.of(y, m, d)
        } catch (_: DateTimeException) {
            null
        }

    private const val SECONDS_PER_DAY = 86_400L

    // ---- sender -------------------------------------------------------------------------------------------------
    fun institutionOf(sender: String): String? = NotificationApps.institutionOf(sender) ?: DefaultSenders.match(senderKey(sender))?.name

    private val senderIdPattern = Regex("^[A-Za-z][A-Za-z._&-]{0,19}$")

    /** A sender id the server accepts: the sender itself when it is clean, otherwise the institution, otherwise a constant. */
    fun uploadSenderId(
        sender: String,
        institution: String?,
    ): String {
        if (sender.count { it == '.' } >= 2) {
            // An Android package name: never upload it (truncated or not); use the institution's clean id.
            val inst = (institution ?: "").replace(Regex("[^A-Za-z._&-]"), "").take(20)
            return if (senderIdPattern.matches(inst)) inst else "APP"
        }
        val key = sender.trim().replace(Regex("[^A-Za-z._&-]"), "").take(20)
        if (senderIdPattern.matches(key)) return key
        val inst = (institution ?: "").replace(Regex("[^A-Za-z._&-]"), "").take(20)
        return if (senderIdPattern.matches(inst)) inst else "BANK"
    }

    private val walletInstitutions = setOf("Vodafone Cash", "Orange Cash", "e& money", "WE Pay", "Fawry")

    private fun paymentMethod(
        sender: String,
        institution: String?,
        t: String,
        kind: TxKind,
    ): String? =
        when {
            institution == "InstaPay" ||
                instapay.containsMatchIn(
                    t,
                ) ||
                instapay.containsMatchIn(sender.lowercase(Locale.ROOT)) -> "INSTAPAY"
            institution in walletInstitutions -> "WALLET"
            Regex("card|بطاق", ci).containsMatchIn(t) -> "CARD"
            kind == TxKind.TRANSFER_OUT || kind == TxKind.TRANSFER_IN -> "BANK_TRANSFER"
            else -> null
        }

    // ---- merchant / counterparty label ----------------------------------------------------------------------------
    private fun merchant(
        norm: String,
        t: String,
        kind: TxKind,
    ): String? {
        // A bank charge has no counterparty: "fee for your transfer to AHMED" must not label the fee with the person
        if (kind == TxKind.FEE) return null
        val role =
            when (kind) {
                TxKind.INCOME, TxKind.TRANSFER_IN, TxKind.REFUND -> "in"
                TxKind.TRANSFER_OUT -> "transfer"
                else -> "out"
            }
        hsbcLabel(norm, t)?.let { return it }
        for (prep in listOf(enPrep.getValue(role), arPrep.getValue(role))) {
            for (m in prep.findAll(t)) {
                val label = labelAt(norm, t, m.range.last + 1)
                if (label != null) return label
            }
        }
        return null
    }

    /** HSBC account SMS: "<DDMONYY> <MERCHANT> Purchase from <masked account> EGP <amount>-". Not for ATM lines. */
    private val hsbcMerchant =
        Regex("(?<![\\d/.:-])\\d{1,2}(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\d{2,4}\\s+(?!atm\\b)(\\S.*?)\\s+purchase\\b", ci)

    private fun hsbcLabel(
        norm: String,
        t: String,
    ): String? {
        val m = hsbcMerchant.find(t) ?: return null
        return labelAt(norm, t, m.groups[1]!!.range.first)
    }

    private fun labelAt(
        norm: String,
        t: String,
        start: Int,
    ): String? {
        val tokens = mutableListOf<String>()
        var i = start
        while (i < t.length && tokens.size < MAX_LABEL_TOKENS) {
            while (i < t.length && t[i] == ' ') i++
            if (i >= t.length) break
            var j = i
            while (j < t.length && t[j] != ' ') j++
            val raw = norm.substring(i, j)
            val clean = LabelNormalizer.normalize(raw)
            i = j
            if (raw.any { Character.isDigit(it) }) break
            // A token of symbols only ("|", "®") carries no name: skip it instead of ending the label.
            if (clean.isEmpty()) continue
            val core = SecretDetector.fold(clean).trim(*PUNCT)
            if (core.isEmpty() || core in stopWords || SecretDetector.hasHintWord(clean)) break
            // "... لدى امازون وسيتم إضافة ...", "... ببطاقتكم ...": the label ends where the sentence moves on
            if (trailingClause.containsMatchIn(core)) break
            if (core.any { Character.isDigit(it) }) break
            if (tokens.isEmpty() && core in badFirst) return null
            tokens += clean.trim(*PUNCT)
            if (raw.last() in ".,;:!?،") break
        }
        var label = LabelNormalizer.normalize(tokens.joinToString(" ").trim(*PUNCT))
        label =
            label
                .codePoints()
                .filter { !Character.isDigit(it) }
                .toArray()
                .let { String(it, 0, it.size) }
        if (label.length > MAX_LABEL) label = label.take(MAX_LABEL).substringBeforeLast(' ', label.take(MAX_LABEL))
        if (label.count { it.isLetter() } < 2) return null
        if (secretLabel.containsMatchIn(SecretDetector.fold(label))) return null
        if (SecretDetector.reason(label) != null || SecretDetector.hasHintWord(label)) return null
        return label.trim()
    }

    private val PUNCT = charArrayOf('*', '•', '.', ',', ';', ':', '!', '?', '(', ')', '،', '"', '\'', '-', '_', '/')
}

/** Android packages whose notifications are read (REQ-NTF-001). Candidates only: see docs/DECISIONS.md. */
object NotificationApps {
    /** InstaPay by the Egyptian Banks Company. Verify on a device with `adb shell pm list packages | grep -i insta`. */
    const val INSTAPAY_CANDIDATE = "com.egyptianbanks.instapay"

    val defaults: Map<String, String> = mapOf(INSTAPAY_CANDIDATE to "InstaPay")

    fun institutionOf(sender: String): String? = defaults[sender]

    private val deniedPrefixes =
        listOf(
            // this app's own notifications ("Recorded 500 EGP ...") must never be read back as a bank message
            "eg.masroof",
            "com.whatsapp",
            "org.telegram",
            "org.thunderdog.challegram",
            "com.facebook",
            "com.instagram",
            "com.snapchat",
            "com.google.android.gm",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "com.android.messaging",
            "com.microsoft.office.outlook",
            "com.google.android.apps.dynamite",
            "com.viber",
            "org.signal",
            "org.thoughtcrime.securesms",
            "jp.naver.line",
            "com.imo.android",
            "com.discord",
            "com.twitter",
            "com.skype",
            "com.tencent.mm",
            "com.android.email",
            "com.yahoo.mobile.client",
            "ch.protonmail",
            "com.slack",
            "com.microsoft.teams",
            "com.truecaller",
            "com.android.dialer",
        )

    /** Messaging, social and email apps are never read, even if the user types their package name. */
    fun isDenied(pkg: String): Boolean = deniedPrefixes.any { pkg == it || pkg.startsWith("$it.") }
}
