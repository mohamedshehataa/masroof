package eg.masroof.ingest

import java.text.Normalizer
import java.util.Locale

/** What an incoming message is, decided on the device before anything is stored or uploaded. */
enum class MessageClass { OTP, PROMO, OTHER }

/**
 * On-device OTP and promotion filter (REQ-SMS-030, REQ-SMS-032). An OTP never leaves the device: any OTP keyword wins
 * over everything else, because a false positive costs one missed expense and a false negative leaks a code.
 * Keywords follow the egypt-bank-sms skill; the server runs the same check again.
 */
object OtpPromoFilter {
    private val otpWords =
        listOf(
            "otp",
            "one-time",
            "one time",
            "passcode",
            "verification code",
            "security code",
            "code is",
            "your code",
            "do not share",
            "don't share",
            "dont share",
            "never share",
            "password",
            "valid for",
            "expires in",
            "رمز",
            "كود",
            "لا تشارك",
            "لا تقم بمشاركه",
            "لا تفصح",
            "كلمه السر",
            "كلمه المرور",
            "صالح لمده",
            "تنتهي صلاحيه",
        )
    private val pin = Regex("\\bpin\\b")
    private val codeToConfirm = Regex("(?<!\\d)\\d{4,8}(?!\\d).{0,40}(?:to confirm|to complete|to verify|لتاكيد|لاتمام)")
    private val codeEnter =
        Regex("(?:\\b(?:enter|use|type|input)|ادخل|استخدم|اكتب)\\s+(?:the\\s+)?(?:code\\s+)?(?<!\\d)\\d{4,8}(?!\\d)")
    private val digitsOnly = Regex("^\\W*\\d{4,8}\\W*$")
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
        return when {
            otpWords.any { text.contains(it) } ||
                pin.containsMatchIn(text) ||
                codeToConfirm.containsMatchIn(text) ||
                codeEnter.containsMatchIn(text) ||
                digitsOnly.matches(text) -> MessageClass.OTP
            promoWords.any { text.contains(it) } && transactionWords.none { text.contains(it) } -> MessageClass.PROMO
            else -> MessageClass.OTHER
        }
    }

    /** NFKC, Western digits, no tatweel/harakat/bidi marks, folded Arabic letters, lower case, single spaces. */
    internal fun normalize(input: String): String {
        val out = StringBuilder(input.length)
        for (c in Normalizer.normalize(input, Normalizer.Form.NFKC)) {
            val code = c.code
            when {
                code in ARABIC_INDIC_ZERO..ARABIC_INDIC_NINE -> out.append('0' + (code - ARABIC_INDIC_ZERO))
                code in PERSIAN_ZERO..PERSIAN_NINE -> out.append('0' + (code - PERSIAN_ZERO))
                code == ARABIC_DECIMAL -> out.append('.')
                code == ARABIC_THOUSANDS || code == ARABIC_COMMA -> out.append(',')
                isIgnorable(code) -> Unit
                else -> out.append(fold(c))
            }
        }
        return out
            .toString()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isIgnorable(code: Int) =
        code == TATWEEL ||
            code in HARAKAT ||
            code == 0x0670 ||
            code in 0x06D6..0x06ED ||
            code in 0x200B..0x200F ||
            code in 0x202A..0x202E ||
            code in 0x2066..0x2069 ||
            code == 0x061C ||
            code == 0xFEFF ||
            code == 0x2060

    private fun fold(c: Char): Char =
        when (c.code) {
            0x0623, 0x0625, 0x0622, 0x0671 -> 'ا'
            0x0649, 0x06CC -> 'ي'
            0x0629 -> 'ه'
            0x06A9 -> 'ك'
            else -> c
        }

    private const val ARABIC_INDIC_ZERO = 0x0660
    private const val ARABIC_INDIC_NINE = 0x0669
    private const val PERSIAN_ZERO = 0x06F0
    private const val PERSIAN_NINE = 0x06F9
    private const val ARABIC_DECIMAL = 0x066B
    private const val ARABIC_THOUSANDS = 0x066C
    private const val ARABIC_COMMA = 0x060C
    private const val TATWEEL = 0x0640
    private val HARAKAT = 0x064B..0x065F
}
