package eg.masroof.ingest

import java.util.Locale

/** What an incoming message is, decided on the device before anything is stored or uploaded. */
enum class MessageClass { OTP, PROMO, OTHER }

/**
 * On-device OTP and promotion hygiene filter (REQ-SMS-030, REQ-SMS-032). It is NOT what keeps codes secret:
 * [SmsRedactor] masks every digit sequence that is not an amount, date, time or card last four before anything is
 * queued, so a code that slips past this filter is already unreadable.
 *
 * An OTP is recognised by strong signals only. A message with a currency amount and a purchase/debit verb is a
 * transaction even if it carries "Auth code", "Approval code", a reference code or a security footer: the footer
 * sentences are ignored for it and its codes are masked. Mirrors the server's `MessageClassifier.java`.
 */
object OtpPromoFilter {
    private val ci = setOf(RegexOption.IGNORE_CASE)
    private val strong =
        Regex(
            "\\b[o0][ ._-]{0,2}t[ ._-]{0,2}p\\b" +
                "|one[ _-]?time" +
                "|\\b(?:verification|authentication|secur(?:e|ity)|activation|login|secret|confirmation" +
                "|temporary|dynamic)[ _-]?(?:code|number|key|pin|password|token)" +
                "|\\b(?:passcode|password|passkey|pin|token)\\b" +
                "|\\bcode is\\b|\\byour code\\b" +
                "|3[ _-]?d[ _-]?secure|\\b3ds\\b" +
                "|\\b(?:approve|authori[sz]e)\\b" +
                "|\\bconfirm\\b.{0,30}\\b(?:with|using)\\b" +
                "|\\b(?:enter|use|type|input)\\b.{0,40}(?<![\\d])\\d{4,8}(?![\\d])" +
                "|(?<![\\d])\\d{4,8}(?![\\d]).{0,40}\\bto (?:confirm|complete|verify)\\b" +
                "|\\bis your\\b.{0,30}\\b(?:code|pin|number|key|token|otp)\\b" +
                "|(?:do not|don't|dont|never) share|expires? in|valid for \\d+ ?(?:min|sec|hour)" +
                "|(?:رمز|كود) (?:ال)?(?:تحقق|تاكيد|تفعيل|دخول|امان)" +
                "|الرمز السري|رمز سري|الرقم السري|رقم سري|كلمه السر|كلمه المرور|كلمه سر|لمره واحده" +
                "|تحقق|وافق|للموافقه|الموافقه" +
                "|(?:ادخل|استخدم|اكتب)\\s+(?<![\\d])\\d{4,8}(?![\\d])" +
                "|(?<![\\d])\\d{4,8}(?![\\d]).{0,40}(?:لتاكيد|لاتمام)" +
                "|لا\\s*تشارك|لا\\s*تقم\\s*بمشاركه|لا\\s*تفصح|لا\\s*تعطي|صالح لمده|تنتهي صلاحيه",
            ci,
        )
    private val footer =
        Regex(
            "(?:never|do not|don't|dont|not)\\s+share|will never ask|for security|call\\s+\\d{3,5}" +
                "|if (?:you did not|this was not|it was not)|beware|fraud|scam" +
                "|لا\\s*تشارك|لا\\s*تقم\\s*بمشاركه|لا\\s*تعطي|لا\\s*تفصح|للاستفسار|اتصل",
            ci,
        )
    private val sentenceBreak = Regex("(?:\\. |[!\\n؟])")
    private val purchaseVerb =
        Regex(
            "\\b(?:purchase|purchased|debited|spent|charged|withdrawn|used for|paid)\\b" +
                "|تم خصم|تم شراء|تم سحب|تم دفع|خصم مبلغ|عمليه شراء",
            ci,
        )
    private val digitsOnly = Regex("^[^\\p{L}\\p{N}]*\\d{4,8}[^\\p{L}\\p{N}]*$")
    private val promoWords =
        listOf(
            "offer",
            "% off",
            "cashback",
            "points",
            "rewards",
            "subscribe",
            "t&c",
            "terms apply",
            "congratulations",
            "promo",
            "coupon",
            "discount",
            "installments up to",
            "http",
            "www.",
            "عرض",
            "عروض",
            "نقاط",
            "اشترك",
            "الشروط والاحكام",
            "مبروك",
            "اربح",
            "كوبون",
            "خصم يصل",
            "تقسيط",
            "للمزيد",
        )
    private val transactionWords =
        listOf(
            "purchase",
            "debited",
            "credited",
            "withdrawal",
            "withdrawn",
            "spent",
            "paid",
            "payment of",
            "received",
            "refund",
            "used for",
            "transaction",
            "transferred",
            "تم خصم",
            "تم شراء",
            "تم سحب",
            "تم دفع",
            "تم ايداع",
            "تم استلام",
            "تم تحويل",
            "استرداد",
        )

    fun classify(body: String): MessageClass {
        val text = normalize(body)
        if (digitsOnly.matches(text)) return MessageClass.OTP
        val transaction = purchaseVerb.containsMatchIn(text) && SmsRedactor.hasCurrencyAmount(text)
        val signals = if (transaction) withoutFooters(text) else text
        return when {
            strong.containsMatchIn(signals) -> MessageClass.OTP
            promoWords.any { text.contains(it) } && transactionWords.none { text.contains(it) } -> MessageClass.PROMO
            else -> MessageClass.OTHER
        }
    }

    private fun withoutFooters(text: String): String =
        sentenceBreak
            .split(text)
            .filterNot { footer.containsMatchIn(it) }
            .joinToString("") { "$it. " }

    /** Normalized (see [SmsRedactor.normalizeText]), letter-folded and lower-cased: what the matching runs on. */
    internal fun normalize(input: String): String =
        SmsRedactor.fold(SmsRedactor.normalizeText(input)).lowercase(Locale.ROOT)
}
