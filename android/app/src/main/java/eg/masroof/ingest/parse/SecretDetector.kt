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
            "\\bsecret\\b|(?<![a-z])[o0][ ._/|-]{0,2}t[ ._/|-]{0,2}ps?(?![a-z])|(?<![a-z])p[ ._/|-]{0,2}i[ ._/|-]{0,2}ns?(?![a-z])" +
                "|\\brequires? (?:your )?approval|\\bapproval (?:is )?(?:required|needed|pending)|\\bneeds? approval" +
                "|\\bapproval\\s*[:#-]\\s*\\d" +
                "|one[ _-]?time|\\bonetime\\b" +
                "|\\bverif[a-z]*|\\bauthenticat[a-z]*" +
                "|\\b(?:secur(?:e|ity)|activation|login|log in|sign in|signin|secret|confirmation|temporary|dynamic|access|" +
                "payment|transaction|txn)[ _-]?(?:code|number|key|pin|password|token)\\b" +
                "|\\b(?:passcodes?|passwords?|passkeys?|pins?|tokens?|cvvs?|cvcs?|cvv2|mpins?|tpins?)\\b" +
                "|\\bcode is\\b|\\byour code\\b|\\bthe code\\b" +
                "|3[ _-]?d[ _-]?secure|\\b3ds\\b" +
                "|\\b(?:approve|authori[sz]e|confirm|enter|type|input|submit|reply)\\b[^.!?\\n]{0,40}\\b(?:with|using|code|pin|otp)\\b" +
                "|\\b(?:enter|use|type|input)\\b[^.!?\\n]{0,40}(?<![\\d])\\d{4,8}(?![\\d])" +
                "|(?<![\\d])\\d{4,8}(?![\\d])[^.!?\\n]{0,40}\\bto (?:confirm|complete|verify|approve|proceed|activate|login|log in)\\b" +
                "|\\bis your\\b[^.!?\\n]{0,30}\\b(?:code|pin|number|key|token|otp|password)\\b" +
                "|\\bexpires? in\\b|\\bvalid for \\d+ ?(?:min|sec|hour)|\\bvalid (?:until|till) \\d{1,2}:\\d{2}" +
                "|(?:رمز|كود|رقم) (?:ال)?(?:تحقق|تاكيد|تفعيل|دخول|امان|سري|م[وؤ]قت|التعريف الشخصي)" +
                "|الرمز السري|رمز سري|الرقم السري|رقم سري|رقمك السري|كلمه السر|كلمه المرور|كلمه سر" +
                "|كلمه (?:ال)?مرور|كلمات (?:ال)?مرور|رقم (?:ال)?مرور|باسورد|الباسورد|باسوورد|رموز|اكواد|لمره واحده|مره واحده" +
                "|تحقق|التحقق|تفعيل|للموافقه|الموافقه علي|تاكيد العمليه|لتاكيد|لاتمام|لاكمال" +
                "|(?:ادخل|استخدم|اكتب|ارسل)\\s+(?:الرمز|الكود|رقم)?\\s*(?<![\\d])\\d{4,8}(?![\\d])" +
                "|(?<![\\d])\\d{4,8}(?![\\d]).{0,40}(?:لتاكيد|لاتمام|للدخول|للتحقق)" +
                "|صالح لمده|تنتهي صلاحيه|صلاحيه الرمز|صلاحيه الكود",
            ci,
        )

    /** Any remaining "code"/"key"/"كود"/"رمز" is secret unless it is a recognised reference code of a finished transaction. */
    private val weakCode = Regex("\\b(?:codes?|keys?)\\b|كود|رمز|رموز|اكواد|\\bsms code\\b", ci)

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

    private val call = Regex("(?:call|dial|hotline|اتصل|الخط الساخن|للاستفسار)(?:\\s*ب)?[ :]*\\d{3,6}", ci)

    /** Number labels whose number is a harmless reference, not a code. */
    private val referenceNumber =
        Regex(
            "\\b(?:ref(?:erence)?|trx|txn|trans(?:action)?|operation|approval|authori[sz]ation|auth|invoice" +
                "|rrn|tid|mid|terminal(?: id| no\\.?| number)?)[ :#.-]*(?=[a-z0-9]*\\d)[a-z0-9]{4,16}" +
                "|(?:رقم|الرقم) (?:ال)?(?:مرجع|المرجعي|مرجعي|عمليه|العمليه|معامله|المعامله|فاتوره|طلب)[^\\da-z]{0,12}(?=[a-z0-9]*\\d)[a-z0-9]{4,16}",
            ci,
        )

    private val digitsOnly = Regex("^[^\\p{L}\\p{N}]*\\d{4,8}[^\\p{L}\\p{N}]*$")

    /** Why a message is secret, or null when it is not. */
    fun reason(normalized: String): String? {
        for (variant in variants(fold(normalized))) reasonOf(variant)?.let { return it }
        return null
    }

    private val leetRun = Regex("[a-z0-9@$!]{2,12}")
    private val leetMap = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's', '!' to 'i')
    private val singleLetterGap = Regex("(?<=\\b\\p{L})[\\s\\p{P}\\p{S}]+(?=\\p{L}\\b)")

    /** "P1N", "c0de", "0TP": digits and symbols standing in for letters inside a word (detection only). */
    private fun leet(text: String): String =
        leetRun.replace(text) { m ->
            val v = m.value
            if (v.any { it in 'a'..'z' } && v.any { it in leetMap }) v.map { leetMap[it] ?: it }.joinToString("") else v
        }

    /** "O/T/P", "O-T-P", "P I N": single letters separated by punctuation or spaces are joined. */
    private fun compact(text: String): String = singleLetterGap.replace(text, "")

    private fun variants(folded: String): List<String> = listOf(folded, compact(folded), leet(folded), compact(leet(folded))).distinct()

    private fun reasonOf(text: String): String? {
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

    /** True when a "do not share" style footer appears anywhere (digits or not): a number nearby may be a secret. */
    fun hasSecurityFooter(text: String): Boolean = shareFooter.containsMatchIn(fold(text))

    /** Only the "never share it" kind of footer, not a hotline line ("call 19555"), which is harmless. */
    private val shareFooter =
        Regex(
            "(?:never|do not|don't|dont|not)\\s+(?:to\\s+)?share|will never ask|beware|لا\\s*تشارك|لا\\s*تقم\\s*بمشاركه|لا\\s*تفصح|لا\\s*تعطي",
            ci,
        )

    /** Blanks reference numbers, hotline numbers and recognised transaction codes (used for the leftover-digits check). */
    fun stripReferences(text: String): String {
        fun blank(
            regex: Regex,
            s: String,
        ) = regex.replace(s) { " ".repeat(it.value.length) }
        // length-preserving, so positions in the result line up with the original text
        return blank(benignCode, blank(call, blank(referenceNumber, text)))
    }

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
            'Τ' to 't',
            'Ρ' to 'p',
            'Ι' to 'i',
            'Ν' to 'n',
            'Α' to 'a',
            'Β' to 'b',
            'Ε' to 'e',
            'Η' to 'h',
            'Κ' to 'k',
            'Μ' to 'm',
            'Χ' to 'x',
            'Υ' to 'y',
            'Ζ' to 'z',
            'τ' to 't',
            'υ' to 'u',
            'κ' to 'k',
            'ν' to 'v',
            'ϲ' to 'c',
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
        Regex("otp|\\bpins?\\b|cvv|passcode|password|token|verif|approv|secur|code|3ds|رمز|كود|تحقق|سري|باسورد|مرور|رموز", ci)

    private val hintWord =
        Regex(
            "(?<![\\p{L}])(?:البن|بن|البين|بسورد|بسوورد|باس\\s*ورد|الشخصي|التعريفي|او\\s*تي\\s*بي|اوتيبي|ادخل|استخدم)(?![\\p{L}])" +
                "|keep it|private|confidential",
            ci,
        )

    /** Any secret-ish vocabulary at all (the code-like words plus the colloquial and disguised Arabic hints). */
    fun hasHintWord(text: String): Boolean = hasCodeLikeToken(text) || variants(fold(text)).any { hintWord.containsMatchIn(it) }

    /** True when the text has any code-like word at all (substring, no word boundary): used to mask review text. */
    fun hasCodeLikeToken(normalized: String): Boolean = variants(fold(normalized)).any { codeLikeToken.containsMatchIn(it) }

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
