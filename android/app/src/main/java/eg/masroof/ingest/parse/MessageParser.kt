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
    private const val TIME_BEFORE_WINDOW = 24
    private const val LAST4_LOOKBACK = 40

    // ---- amounts -------------------------------------------------------------------------------------------------
    private const val NUM = "\\d{1,3}(?:\\.\\d{3})+,\\d{1,2}|\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:[.,]\\d+)?"
    private const val CUR =
        "(?:egp|l\\.e\\.?|le|e£|جنيها|جنيه|جنية|ج\\.م\\.?|جم|usd|\\$|دولار|eur|€|يورو|gbp|£|sar|sr|ريال|riyal|aed|dhs|dh|درهم" +
            "|kwd|qar|jpy|yen|try|tl|cad|chf|cny|omr|bhd|jod)"
    private val currencyThenNumber = Regex("(?<![a-z])($CUR)[ :]?($NUM)(?![\\d]|[a-z\\p{L}])", ci)
    private val numberThenCurrency = Regex("(?<![\\d.,*-])($NUM) ?($CUR)(?![a-z])", ci)
    private val keywordAmount = Regex("(?:amount(?: of)?|value|مبلغ|بمبلغ|بقيمه|قيمه)[ :]*($NUM)(?![\\d])", ci)
    private val tokenAfter = Regex("^ ?([^ \\d.,;:!?()]+)")
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
            "% off|\\boffers?\\b|cashback|\\bpoints\\b|rewards|subscribe|t&c|terms apply|congratulations|\\bpromo|coupon|discount|" +
                "installments up to|https?:|www\\.|عرض|عروض|نقاط|اشترك|الشروط والاحكام|مبروك|اربح|كوبون|خصم يصل|تقسيط",
            ci,
        )
    private val outAnchor =
        Regex(
            "\\btransaction (?:of|was made|has been made|approved|completed)\\b" +
                "|\\b(?:debited|debit|deducted|withdraw(?:n|al)?|charged|spent|paid|pay|sent" +
                "|send|purchased?|payment|transfer(?:red)?|used for|pos)\\b" +
                "|تم خصم|خصم|تم دفع|دفع|شراء|سداد|تحويل|تم ارسال|ارسال|ارسلت|حولت|سحب|مدفوعات|عمليه",
            ci,
        )
    private val inAnchor =
        Regex(
            "\\brefund(?:ed)? of\\b|you(?: have)? received|\\breceived (?:egp|le|an?|\\d|usd)" +
                "|credited with|(?:has|have) been credited to your|were credited" +
                "|\\bdeposit(?:ed)?\\b|\\bsalary\\b|\\bincoming\\b|ايداع|تم استلام|تم استقبال|استقبال تحويل|تم رد|رد مبلغ|استرداد|مرتجع|استلمت|وارد|راتب|مرتب|تم اضافه|اضافه مبلغ|استلام حواله",
            ci,
        )
    private val ambiguousReceipt = Regex("استلام (?:تحويلك|حوالتك|التحويل)|received your transfer", ci)
    private val refund = Regex("\\brefund(?:ed)?\\b|استرداد|مرتجع|رد مبلغ", ci)
    private val withdrawal = Regex("\\batm\\b|cash withdrawal|cash advance|\\bwithdraw(?:n|al)?\\b|سحب نقدي|ماكينه الصراف|تم سحب", ci)
    private val fee =
        Regex(
            "\\b(?:annual fee|monthly fee|service fee|maintenance fee|card fee|sms fee|fee of|fees of|a fee|commission)\\b" +
                "|خصم (?:رسوم|عموله|مصاريف)|(?:رسوم|عموله) (?:الخدمه|شهريه|سنويه|الصيانه|بطاقه|البطاقه)",
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
        val own: Boolean,
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

    /** A card or account named right after "to"/"إلى"/beneficiary is the other party's, never the household's. */
    private val recipientBefore =
        Regex(
            "(?:\\bto|(?<![\\p{L}\\d])الي|\\bbeneficiary|(?<![\\p{L}\\d])المستفيد)\\s+" +
                "(?:(?:the\\s+)?(?:a/c|acct|account|card|wallet|حساب|بطاقه|محفظه)(?!\\p{L})\\s*)?" +
                "(?:(?:no\\.?|number|رقم)\\s*)?$",
            ci,
        )

    private val phoneNumber = Regex("(?<![\\d])(?:\\+?20)?01[0125]\\d{8}(?!\\d)")
    private val counterpartyBefore =
        Regex("(?:(?<![\\p{L}\\d])الي|(?<![\\p{L}\\d])من|(?<![\\p{L}\\d])ل?رقم|\\bto|\\bfrom)\\s*(?:رقم|no\\.?|number)?\\s*$", ci)

    // ---- dates ---------------------------------------------------------------------------------------------------
    private val dateFull = Regex("(?<![\\d/.:-])(\\d{1,2})([/.-])(\\d{1,2})\\2(\\d{4}|\\d{2})(?![\\d/:])")
    private val dateIso = Regex("(?<![\\d/.:-])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?![\\d])")
    private val dateShort = Regex("(?<![\\d/.:-])(\\d{1,2})/(\\d{1,2})(?![\\d/:]|\\.\\d)")
    private val dateCompact =
        Regex("(?<![\\d/.:-])(\\d{1,2})(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(\\d{4}|\\d{2})(?![\\d])", ci)
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
    private val secretLabel =
        Regex(
            "\\b(?:otp|o\\.t\\.p|pin|codes?|password|passcode|cvv|cvc|token|secret|verification|verify|one[- ]?time|3d ?secure|3ds|" +
                "activation|authenti[a-z]*)\\b|كود|رمز|تحقق|سري|كلمه المرور|كلمه السر|لا تشارك",
            ci,
        )
    private val enPrep =
        mapOf(
            "out" to Regex("(?<![\\p{L}\\d])(?:at|to)[ ]+", ci),
            "transfer" to Regex("(?<![\\p{L}\\d])to[ ]+", ci),
            "in" to Regex("(?<![\\p{L}\\d])from[ ]+", ci),
        )
    private val arPrep =
        mapOf(
            "out" to Regex("(?<![\\p{L}\\d])(?:لدي|عند|الي|لصالح|ل)[ ]+"),
            "transfer" to Regex("(?<![\\p{L}\\d])(?:الي|لصالح|للمستفيد|ل)[ ]+"),
            "in" to Regex("(?<![\\p{L}\\d])من[ ]+"),
        )

    /** The first plausible amount in minor units, to prefill the manual form from a review item (never uploaded). */
    fun suggestAmountMinor(text: String): Long? {
        val t = SecretDetector.fold(TextNormalizer.normalizeText(text))
        return findAmounts(t).firstOrNull { !it.balance && !it.ambiguous }?.minor?.takeIf { it in 1..MAX_AMOUNT_MINOR }
    }

    /** Parses one message. [received] is the SMS received time or the notification post time. */
    fun parse(msg: IncomingMessage): ParseOutcome {
        val norm = TextNormalizer.normalizeText(msg.text)
        if (norm.isBlank()) return ParseOutcome.Ignore("empty")
        val t = SecretDetector.fold(norm)
        val secret = SecretDetector.reason(norm)
        val amounts = findAmounts(t)
        val hasOut = outAnchor.containsMatchIn(t)
        val hasIn = inAnchor.containsMatchIn(t)
        val moneyLike = amounts.any { !it.balance } && (hasOut || hasIn)
        if (secret != null) {
            // An OTP that reads like a purchase is kept (masked) for the user; a plain OTP is dropped without a trace.
            return if (moneyLike) ParseOutcome.Review("secret", SecretDetector.mask(norm)) else ParseOutcome.Ignore("otp")
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
        val shown = maskRanges(codeMasked, unexplainedRanges(t, amounts))
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
        if (promoWords.containsMatchIn(t) && !strongTransaction(t)) return ParseOutcome.Ignore("promo")
        val direction = firstDirection(t) ?: return review("no_template")
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
        val record =
            ParsedRecord(
                localId = "", // keyed by LocalIdHasher in the pipeline, never by the parser
                source = msg.source,
                kind = kind,
                amountMinor = amount.minor,
                currency = currency,
                occurredAtMillis = occurredAt(t, msg.receivedAtMillis),
                instrumentLast4 = last4(t),
                senderId = uploadSenderId(sender, institution),
                institution = institution,
                paymentMethod = paymentMethod(sender, institution, t, kind),
                merchant = merchant(norm, t, kind),
            )
        return ParseOutcome.Record(record)
    }

    private fun strongTransaction(t: String) =
        Regex(
            "\\b(?:debited|purchase|paid|spent|withdrawn)\\b|تم خصم|تم شراء|تم دفع",
            ci,
        ).containsMatchIn(t)

    /** The direction is whichever verb comes first: "transfer ... was received" is outgoing, "received ... sent by" incoming. */
    private fun firstDirection(t: String): Boolean? {
        val out = outAnchor.find(t)?.range?.first
        val inn = inAnchor.find(t)?.range?.first
        return when {
            out == null && inn == null -> null
            out == null -> false
            inn == null -> true
            else -> out < inn
        }
    }

    /** Fee wording decides only when it comes first: "تحويل ... رسوم الخدمة 1 جنيه" is a transfer that mentions its fee. */
    private val transferVerbs = Regex("\\btransfer(?:red)?\\b|\\bsent\\b|\\bsend\\b|تحويل|ارسال|ارسلت|حولت", ci)

    private fun feeIsTheTransaction(t: String): Boolean {
        val feeAt = fee.find(t)?.range?.first ?: return false
        val other = listOfNotNull(transferVerbs.find(t)?.range?.first, paymentWords.find(t)?.range?.first)
        return other.none { it < feeAt }
    }

    private fun kindOf(
        t: String,
        outgoing: Boolean,
    ): TxKind =
        when {
            refund.containsMatchIn(t) -> TxKind.REFUND
            withdrawal.containsMatchIn(t) && outgoing -> TxKind.CASH_WITHDRAWAL
            outgoing && feeIsTheTransaction(t) -> TxKind.FEE
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
                    minor == null || groupedDigits.containsMatchIn(after),
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
        for (p in listOf(dateFull, dateIso, dateShort)) p.findAll(t).forEach { blank(it.range) }
        Regex("(?<![\\d:])\\d{1,2}:\\d{2}(?::\\d{2})?(?![\\d:])").findAll(t).forEach { blank(it.range) }
        last4Patterns.forEach { p -> p.regex.findAll(t).forEach { blank(it.groups[1]!!.range) } }
        dateCompact.findAll(t).forEach { blank(it.range) }
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
    private fun last4(t: String): String? {
        val candidates =
            last4Patterns
                .flatMap { p ->
                    p.regex
                        .findAll(t)
                        .map { m -> Triple(m.range.first, m.groupValues[1], p.own) }
                        .toList()
                }.sortedBy { it.first }
        for ((start, digits, own) in candidates) {
            if (!own && recipientBefore.containsMatchIn(t.substring(maxOf(0, start - LAST4_LOOKBACK), start))) continue
            return digits
        }
        return null
    }

    private class DateCandidate(
        val start: Int,
        val range: IntRange,
        val dates: List<LocalDate>,
    )

    private fun occurredAt(
        t: String,
        receivedMillis: Long,
    ): Long {
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
                    .filter { !it.isBefore(earliest) && !it.isAfter(latest) }
                    .distinct()
            // Exactly one reading inside the window decides; two (or none) leave the received time.
            if (inWindow.size == 1) return inWindow.first().toEpochMilli()
        }
        return receivedMillis
    }

    private fun instantOf(
        t: String,
        range: IntRange,
        date: LocalDate,
        received: Instant,
        receivedDate: LocalDate,
    ): Instant? {
        val after = timeAfter.find(t.substring(range.last + 1, minOf(t.length, range.last + 1 + TIME_WINDOW)))
        val time = after ?: timeBefore.find(t.substring(maxOf(0, range.first - TIME_BEFORE_WINDOW), range.first))
        return when {
            time != null -> toInstant(date, time)
            date == receivedDate -> received
            else -> date.atTime(LocalTime.of(NOON, 0)).atZone(cairo).toInstant()
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
            if (core.isEmpty() || core in stopWords) break
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
        if (SecretDetector.reason(label) != null || SecretDetector.hasCodeLikeToken(label)) return null
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
