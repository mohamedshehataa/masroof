package eg.masroof.ingest.parse

import java.util.Locale

/**
 * Decides whether a message carries something secret: a one-time password, PIN, activation or verification code, or an
 * unexplained long number. A secret message is never turned into a record and never leaves the phone (REQ-SMS-030).
 *
 * Secrecy no longer rests on this class alone: only a structured record is ever uploaded and its single free-text field
 * is a digit-free label. This detector exists so that an OTP that happens to read like a purchase ("Your OTP for
 * purchase of EGP 500 at AMAZON is 889900") is not booked as an expense. It is deliberately aggressive: a false
 * positive costs the user one tap in the local "Needs review" list, a false negative would create a phantom expense.
 *
 * Input is the output of [TextNormalizer.normalizeText] (ASCII digits, no invisible characters).
 */
@Suppress("MaxLineLength")
object SecretDetector {
    private val ci = setOf(RegexOption.IGNORE_CASE)

    private val strong =
        Regex(
            "(?<![a-z])[o0][ ._-]{0,2}t[ ._-]{0,2}p(?![a-z])|(?<![a-z])p[ ._-]{0,2}i[ ._-]{0,2}n(?![a-z])" +
                "|\\brequires? (?:your )?approval|\\bapproval (?:is )?(?:required|needed|pending)|\\bneeds? approval" +
                "|\\bapproval\\s*[:#-]\\s*\\d" +
                "|one[ _-]?time|\\bonetime\\b" +
                "|\\bverif[a-z]*|\\bauthenticat[a-z]*" +
                "|\\b(?:secur(?:e|ity)|activation|login|log in|sign in|signin|secret|confirmation|temporary|dynamic|access|" +
                "payment|transaction|txn)[ _-]?(?:code|number|key|pin|password|token)\\b" +
                "|\\b(?:passcode|password|passkey|pin|token|cvv|cvc|cvv2|mpin|tpin)\\b" +
                "|\\bcode is\\b|\\byour code\\b|\\bthe code\\b" +
                "|3[ _-]?d[ _-]?secure|\\b3ds\\b" +
                "|\\b(?:approve|authori[sz]e|confirm|enter|type|input|submit|reply)\\b[^.!?\\n]{0,40}\\b(?:with|using|code|pin|otp)\\b" +
                "|\\b(?:enter|use|type|input)\\b[^.!?\\n]{0,40}(?<![\\d])\\d{4,8}(?![\\d])" +
                "|(?<![\\d])\\d{4,8}(?![\\d])[^.!?\\n]{0,40}\\bto (?:confirm|complete|verify|approve|proceed|activate|login|log in)\\b" +
                "|\\bis your\\b[^.!?\\n]{0,30}\\b(?:code|pin|number|key|token|otp|password)\\b" +
                "|\\bexpires? in\\b|\\bvalid for \\d+ ?(?:min|sec|hour)|\\bvalid (?:until|till) \\d{1,2}:\\d{2}" +
                "|(?:رمز|كود|رقم) (?:ال)?(?:تحقق|تاكيد|تفعيل|دخول|امان|سري|مؤقت|التعريف الشخصي)" +
                "|الرمز السري|رمز سري|الرقم السري|رقم سري|كلمه السر|كلمه المرور|كلمه سر|لمره واحده|مره واحده" +
                "|تحقق|التحقق|تفعيل|للموافقه|الموافقه علي|تاكيد العمليه|لتاكيد|لاتمام|لاكمال" +
                "|(?:ادخل|استخدم|اكتب|ارسل)\\s+(?:الرمز|الكود|رقم)?\\s*(?<![\\d])\\d{4,8}(?![\\d])" +
                "|(?<![\\d])\\d{4,8}(?![\\d]).{0,40}(?:لتاكيد|لاتمام|للدخول|للتحقق)" +
                "|صالح لمده|تنتهي صلاحيه|صلاحيه الرمز|صلاحيه الكود",
            ci,
        )

    /** Any remaining "code"/"key"/"كود"/"رمز" is secret unless it is a recognised reference code of a finished transaction. */
    private val weakCode = Regex("\\b(?:codes?|keys?)\\b|كود|رمز|\\bsms code\\b", ci)

    /** Reference codes of a completed transaction (never an OTP): "Auth code 123456", "كود العملية 123456". */
    private val benignCode =
        Regex(
            "\\b(?:auth(?:ori[sz]ation)?|approval|approved|txn|trx|transaction|ref(?:erence)?|fawry|operation|tracking|" +
                "invoice|booking|order|receipt|confirmation|rrn|stan|payment) ?(?:code|no\\.?|number|id|ref)\\b[ :#.-]*\\d{0,12}" +
                "|(?:كود|رمز|رقم|الرقم) (?:ال)?(?:عمليه|شحن|موافقه|مرجع|المرجعي|فوري|ايصال|فاتوره|طلب|حجز)(?: ل?فوري)?[ :#.-]*\\d{0,12}",
            ci,
        )

    private val completion =
        Regex(
            "\\b(?:successful(?:ly)?|completed|was made|has been|have been|was used|were|was debited|approved)\\b" +
                "|(?<![\\d/])\\d{1,2}[/.-]\\d{1,2}(?:[/.-]\\d{2,4})?(?![\\d/])|(?:^|\\s)تم\\s|بنجاح|بتاريخ",
            ci,
        )
    private val pending =
        Regex(
            "\\b(?:confirm|approve|authori[sz]e|enter|use|complete|submit|proceed|verify|activate|to pay|pending|will be)\\b" +
                "|للدفع|لتاكيد|لاتمام|ادخل|استخدم|للموافقه|لاكمال|سيتم",
            ci,
        )

    /** Security footers, removed only when they hold no digit: "Never share your PIN or OTP with anyone". */
    private val footer =
        Regex(
            "(?:never|do not|don't|dont|not)\\s+(?:to\\s+)?share[^.!?\\n]*" +
                "|will never ask[^.!?\\n]*|for (?:your )?security[^.!?\\n]*" +
                "|beware[^.!?\\n]*|if (?:you did not|this was not|it was not)[^.!?\\n]*" +
                "|لا\\s*تشارك[^.!?\\n]*|لا\\s*تقم\\s*بمشاركه[^.!?\\n]*|لا\\s*تفصح[^.!?\\n]*|لا\\s*تعطي[^.!?\\n]*|للاستفسار[^.!?\\n]*|اتصل[^.!?\\n]*",
            ci,
        )

    private val call = Regex("(?:call|dial|hotline|اتصل|الخط الساخن|للاستفسار)[ :]*\\d{3,5}", ci)

    /** Number labels whose number is a harmless reference, not a code. */
    private val referenceNumber =
        Regex(
            "\\b(?:ref(?:erence)?|trx|txn|trans(?:action)?|operation|approval|authori[sz]ation|auth|invoice" +
                "|rrn|tid|mid|terminal(?: id| no\\.?| number)?|id|no\\.?|number)[ :#.-]*\\d{4,14}" +
                "|(?:رقم|الرقم) (?:ال)?(?:مرجع|المرجعي|عمليه|العمليه|فاتوره|طلب)[^\\d]{0,12}\\d{4,14}",
            ci,
        )

    private val digitsOnly = Regex("^[^\\p{L}\\p{N}]*\\d{4,8}[^\\p{L}\\p{N}]*$")

    /** Why a message is secret, or null when it is not. */
    fun reason(normalized: String): String? {
        val text = fold(normalized)
        if (digitsOnly.matches(text)) return "digits_only"
        val body = withoutFooters(text)
        val finished = completion.containsMatchIn(body) && !pending.containsMatchIn(body)
        val cleaned = if (finished) withoutBenignCodes(body) else body
        return when {
            strong.containsMatchIn(cleaned) -> "otp_signal"
            weakCode.containsMatchIn(cleaned) -> "code_word"
            else -> null
        }
    }

    fun isSecret(normalized: String): Boolean = reason(normalized) != null

    /** Blanks reference numbers, hotline numbers and recognised transaction codes (used for the leftover-digits check). */
    fun stripReferences(text: String): String = benignCode.replace(call.replace(referenceNumber.replace(text, " "), " "), " ")

    private fun withoutFooters(text: String): String =
        footer.replace(text) { m -> if (m.value.any { it.isDigit() }) m.value else " " }

    private val amountBefore = Regex("\\d[\\d.,]*\\s?(?:egp|le|l\\.e|جنيه|ج\\.م|جم)|(?:egp|le|l\\.e|جنيه|ج\\.م)\\s?\\d")
    private val purposeAfter = Regex("^\\W*(?:for|to|pay|لشراء|للشراء|لدفع|لاتمام|لعمليه)", ci)

    /**
     * A reference code is harmless only as a trailing receipt detail: it must come AFTER the amount and must not be
     * followed by a purpose ("for purchase of ...", "لشراء ..."), which is how an OTP for a pending payment reads.
     */
    private fun withoutBenignCodes(text: String): String =
        benignCode.replace(text) { m ->
            val before = text.substring(0, m.range.first)
            val after = text.substring(m.range.last + 1)
            if (amountBefore.containsMatchIn(before) && !purposeAfter.containsMatchIn(after)) " " else m.value
        }

    /** Look-alike letters (Cyrillic, Greek) an attacker could use to dodge the word lists. */
    private val confusables =
        mapOf(
            'а' to 'a',
            'е' to 'e',
            'о' to 'o',
            'р' to 'p',
            'с' to 'c',
            'х' to 'x',
            'у' to 'y',
            'і' to 'i',
            'ј' to 'j',
            'ѕ' to 's',
            'к' to 'k',
            'ο' to 'o',
            'α' to 'a',
            'ρ' to 'p',
            'ι' to 'i',
            'ν' to 'v',
            'Ο' to 'o',
            'Р' to 'p',
            'О' to 'o',
            'А' to 'a',
            'Е' to 'e',
            'С' to 'c',
            'Т' to 't',
            'Н' to 'h',
            'Х' to 'x',
            'І' to 'i',
        )

    /** Lower-cases ASCII only and maps look-alikes to Latin, one char for one char, so indexes line up with the input. */
    fun fold(text: String): String =
        buildString(text.length) {
            for (c in TextNormalizer.fold(text)) {
                val latin = confusables[c] ?: c
                append(if (latin in 'A'..'Z') latin.lowercaseChar() else latin)
            }
        }

    private val codeLikeToken =
        Regex("otp|\\bpin\\b|cvv|passcode|password|token|verif|approv|secur|code|3ds|رمز|كود|تحقق|سري", ci)

    /** True when the text has any code-like word at all (substring, no word boundary): used to mask review text. */
    fun hasCodeLikeToken(normalized: String): Boolean = codeLikeToken.containsMatchIn(fold(normalized))

    /** Masks bare runs of four or more digits but keeps formatted amounts ("1,250.00") and numbers next to a currency. */
    fun maskCodes(text: String): String =
        Regex("(?<![\\d.,])\\d{4,}(?![\\d]|[.,]\\d)").replace(text) { m ->
            val after = text.substring(m.range.last + 1).trimStart().lowercase()
            val isAmount = after.startsWith("egp") || after.startsWith("le ") || after.startsWith("جنيه") || after.startsWith("ج.م")
            if (isAmount) m.value else "•".repeat(m.value.length)
        }

    /** The text with every run of four or more digits masked: how a secret message is kept in the local review list. */
    fun mask(text: String): String =
        Regex("\\d(?:[\\d .,/:-]*\\d)?").replace(text) { m ->
            if (m.value.count(Char::isDigit) >=
                MASK_FROM
            ) {
                "•".repeat(m.value.length)
            } else {
                m.value
            }
        }

    private const val MASK_FROM = 4

    @Suppress("unused")
    private val locale = Locale.ROOT
}
