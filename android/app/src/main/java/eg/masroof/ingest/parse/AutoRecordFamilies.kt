package eg.masroof.ingest.parse

/**
 * The confidence gate (design change of round 15). The generic engine in [MessageParser] always extracts fields, but a
 * message becomes an uploaded record WITHOUT a human only when it is a proven family: a strict regex over the structure of
 * the first clause (not a vote of signals), and the family's own capture of the amount and last four must agree with what the
 * engine extracted. Everything else is a review item that carries the extraction as a suggestion (one tap to save), or matches
 * a template the user confirmed on this phone (see [TemplateShape]).
 *
 *  - F1 card purchase: "Your card ending 4512 was charged EGP 560.00 at MERCHANT" and the Arabic "تم خصم 250 جنيه من بطاقتكم
 *    المنتهية ب 4512 لدى MERCHANT" (plus the mirrored order variants)
 *  - F2 HSBC account line: "From HSBC: 24OCT26 MERCHANT Purchase from 012-345***-678 EGP 2,345.50- Your available balance ..."
 *  - F3 the owner's instant-transfer bank shapes (out and in)
 *  - F4 InstaPay app notifications (package com.egyptianbanks.instapay): sent, received, paid
 */
object AutoRecordFamilies {
    private val ci = setOf(RegexOption.IGNORE_CASE)

    private const val NUM = "\\d[\\d,]*(?:\\.\\d{1,2})?"
    private const val CUR = "(?:egp|le|l\\.e|جنيه|جم|ج\\.م)"
    private const val AMT = "(?:$CUR\\s*$NUM|$NUM\\s*$CUR)"
    private const val DATE = "(?:\\d{1,2}[/.-](?:\\d{1,2}|[a-z]{3})[/.-]\\d{2,4}|\\d{1,2}\\s+[a-z]{3}\\s+\\d{4})"
    private const val TIME = "\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[ap]m)?"

    // a merchant or counterparty never holds a card-number-like run or a fee or credit word; the slot validator does the rest
    private const val MERCHANT =
        "(?:(?!\\d{4})(?!(?:fees?|commission|رسوم|عموله|deposit|salary|refund(?:ed)?|reversal)\\b)[^;:(]){1,60}?"
    private const val CPARTY = "(?:[^;:(]){1,60}?"

    /**
     * CLOSED WORLD: after the merchant or counterparty the clause must end, or continue only with one of these phrases (a date, a
     * time, "via InstaPay", "successfully", a reference). Any other word, symbol or punctuation is absorbed by the slot, and the slot
     * validator then rejects it: nothing outside this list can follow a recorded merchant.
     */
    private const val OK_TAIL =
        "(?:\\s+(?:(?:on|في|يوم|بتاريخ)\\s+$DATE|(?:at|الساعه)?\\s*$TIME|(?:via|عبر)\\s+(?:instapay|انستاباي)|" +
            "(?:was\\s+)?successful(?:ly)?|بنجاح|completed|تمت\\s+بنجاح|has\\s+been\\s+completed\\s+successfully|" +
            "for\\s+(?:more\\s+than\\s+)?\\d+\\s+items?|in\\s+\\d+\\s+installments?|" +
            "(?:ref|rrn|رقم\\s+المعامله)\\s*:?\\s*[a-z0-9]+))*"
    private const val NAME = "[\\p{L}][\\p{L} .'-]{1,50}?"
    private const val TAIL3 =
        "(?:\\s+(?:في|يوم|رقم|المعامله|للمزيد|اتصل|ب|$TIME|$DATE|(?=[a-z0-9]*\\d)[a-z0-9]{6,12}|\\d{3,6}))*"
    private const val PREFIX = "(?:[\\p{L}][\\p{L}\\d .&-]{0,25}:\\s*)?(?:dear\\s+customer,?\\s*)?"
    private const val DATE_TAIL = "(?:\\s+on\\s+$DATE(?:\\s+(?:at\\s+)?$TIME)?)?"

    private const val CARD_EN =
        "(?:your\\s+)?(?:(?:credit|debit|prepaid|[a-z]{2,8})\\s+)?card(?:\\s+(?:ending(?:\\s+(?:with|in))?|no\\.?|number|#))?" +
            "\\s*[*x•#.\\s]*(?<l4>\\d{4})"

    private val cardPurchaseEn =
        listOf(
            Regex(
                "^$PREFIX$CARD_EN\\s+(?:was|has\\s+been)\\s+(?:used\\s+for|charged|debited)" +
                    "(?:\\s+(?:with|by|for))?\\s+(?:a\\s+purchase\\s+of\\s+)?$AMT" +
                    "$DATE_TAIL\\s+at\\s+(?<m>$MERCHANT)$OK_TAIL$",
                ci,
            ),
            Regex(
                "^$PREFIX$CARD_EN\\s+(?:was|has\\s+been)\\s+used\\s+at\\s+(?<m>$MERCHANT)\\s+for\\s+$AMT$OK_TAIL$",
                ci,
            ),
        )

    /** "... وسيتم إضافة كاش باك 25 جنيه إلى حسابك": a promised reward after the merchant is a trailer, not a status. */
    private const val REWARD_TAIL = "(?:\\s+و?سيتم\\s+[^;:]*?(?:كاش\\s*باك|نقاط)[^;:]*)?"

    private const val CARD_AR =
        "(?:من|ب|علي)\\s*(?:ال)?بطاق(?:ه|ت(?:ك|كم))(?:\\s+(?:الائتمانيه|الائتمان|الخصم))?\\s+(?:المنتهيه\\s+ب|المنتهي\\s+ب|رقم)?" +
            "\\s*[*•x]*\\s*(?<l4>\\d{4})"

    private val cardPurchaseAr =
        listOf(
            Regex(
                "^$PREFIX" + "تم\\s+(?:اجراء\\s+)?(?:خصم|شراء|سحب|عمليه(?:\\s+ناجحه)?)\\s+(?:(?:بمبلغ|بقيمه|مبلغ)\\s*)?$AMT\\s*" +
                    "$CARD_AR\\s+لدي\\s+(?<m>$MERCHANT)$OK_TAIL$REWARD_TAIL$",
                ci,
            ),
            // the mirrored order: the merchant before the card
            Regex(
                "^$PREFIX" +
                    "تم\\s+(?:خصم|شراء)\\s+(?:(?:بمبلغ|مبلغ)\\s*)?$AMT\\s+(?:لدي|من)\\s+(?<m>$MERCHANT)\\s+$CARD_AR" +
                    "$OK_TAIL$",
                ci,
            ),
        )

    private const val ACCOUNT = "[\\d*x\\-]{4,}(?:\\s[\\d*x\\-]+)*"
    private val hsbcPurchase =
        Regex(
            "^(?:from\\s+hsbc:\\s*)?\\d{2}[a-z]{3}\\d{2}\\s+(?<m>$MERCHANT)\\s+purchase\\s+from\\s+$ACCOUNT" +
                "\\s+egp\\s*(?<amt>$NUM)-\\s*your\\s+available\\s+balance(?:\\s+is)?\\s+egp\\s*$NUM$",
            ci,
        )
    private val hsbcAtm =
        Regex(
            "^(?:from\\s+hsbc:\\s*)?\\d{2}[a-z]{3}\\d{2}\\s+atm\\s+cash\\s+withdrawal\\s+from\\s+$ACCOUNT" +
                "\\s+egp\\s*(?<amt>$NUM)-\\s*your\\s+available\\s+balance(?:\\s+is)?\\s+egp\\s*$NUM$",
            ci,
        )

    private val instantOut =
        Regex(
            "^$PREFIX" + "تم\\s+تنفيذ\\s+تحويل\\s+لحظي\\s+من\\s+حسابكم\\s+(?:رقم\\s+)?[*•x]*(?<l4>\\d{4})\\s+بمبلغ\\s+$AMT$TAIL3$",
            ci,
        )
    private val instantIn =
        Regex(
            "^$PREFIX" +
                "تم\\s+استقبال\\s+تحويل\\s+لحظي\\s+الي\\s+حسابكم\\s+(?:رقم\\s+)?[*•x]*(?<l4>\\d{4})" +
                "\\s+بمبلغ\\s+$AMT\\s+من\\s+(?<m>$NAME)$TAIL3$",
            ci,
        )

    private val instapayHeader = Regex("^(?:instapay|انستاباي|تم\\s+التحويل\\s+بنجاح|تم\\s+الدفع\\s+بنجاح)$", ci)
    private val instapayOut =
        listOf(
            Regex("^you\\s+(?:have\\s+)?sent\\s+$AMT\\s+to\\s+(?<m>$CPARTY)$OK_TAIL$", ci),
            Regex("^(?:instapay\\s+)?payment\\s+of\\s+$AMT\\s+to\\s+(?<m>$CPARTY)$OK_TAIL$", ci),
            Regex("^تم\\s+تحويل\\s+$AMT\\s+الي\\s+(?<m>$CPARTY)$OK_TAIL$", ci),
            Regex("^تم\\s+دفع\\s+$AMT\\s+الي\\s+(?<m>$CPARTY)$OK_TAIL$", ci),
        )
    private val instapayIn =
        listOf(
            Regex("^you\\s+(?:have\\s+)?received\\s+$AMT\\s+from\\s+(?<m>$CPARTY)$OK_TAIL$", ci),
            Regex("^تم\\s+استلام\\s+$AMT\\s+من\\s+(?<m>$CPARTY)$OK_TAIL$", ci),
        )

    /**
     * A clause after the principal one must BE noise end to end (not merely contain a noise word): a balance or limit line, a hotline
     * line, a security footer, a cashback promise, a fee note, a thank-you or a reference. "Transaction not authorized, please call
     * 19666" holds a hotline but is not one.
     */
    private val noiseClauses =
        listOf(
            Regex(
                "(?:your\\s+)?(?:available\\s+)?(?:balance|limit|credit\\s+limit)" +
                    "(?:\\s+(?:is|will\\s+be\\s+updated\\s+shortly))?(?:\\s+(?:egp\\s*$NUM|$NUM\\s*egp))?",
            ),
            Regex(
                "(?:not\\s+you\\s*\\??\\s*)?(?:for\\s+(?:more\\s+info(?:rmation)?|security|assistance)\\s*,?\\s*)?" +
                    "(?:please\\s+)?call\\s+\\d{3,6}" +
                    "(?:\\s+if\\s+(?:this\\s+was\\s+not\\s+you|you\\s+did\\s+not\\s+make\\s+this\\s+transaction))?",
            ),
            Regex("(?:never|do\\s+not|don't)\\s+share\\s+[a-z ,/']{1,50}"),
            Regex(
                "(?:you\\s+(?:will\\s+)?earn(?:ed)?\\s+)?(?:egp\\s*$NUM\\s+)?cashback(?:\\s+(?:of\\s+)?egp\\s*$NUM)?" +
                    "(?:\\s+(?:that\\s+)?(?:will\\s+be\\s+)?credited(?:\\s+to\\s+your\\s+(?:account|card))?)?" +
                    "(?:\\s+within\\s+\\d+\\s+days)?",
            ),
            Regex("(?:track|manage|discover|explore|visit|download)\\s+[a-z ]{1,40}(?:\\s+(?:at|on)\\s+\\S+)?"),
            Regex("احصل\\s+علي\\s+[\\p{L}\\d% ]{1,60}"),
            Regex("(?:اكتشف|تابع|استمتع)\\s+[\\p{L} ]{1,30}(?:\\s+(?:علي|عند)\\s+\\S+)?"),
            Regex("(?:service\\s+|foreign\\s+transaction\\s+|annual\\s+)?fees?(?:\\s+of)?\\s+egp\\s*$NUM"),
            Regex("(?:enjoy|earn|get)\\s+[a-z0-9% ]{1,40}[!.]?"),
            Regex("thank(?:s|\\s+you)(?:\\s+for\\s+[a-z ]{1,30})?"),
            Regex(
                "(?:ref|rrn|auth(?:orization)?(?:\\s+code)?|approval(?:\\s+code)?|txn\\s+id|trx\\s+id)" +
                    "\\s*(?:no\\.?)?\\s*:?\\s*[a-z0-9]{3,16}",
            ),
            Regex(
                "(?:رصيدك|الرصيد|الحد)(?:\\s+(?:الحالي|المتاح|الائتماني))*(?:\\s+[بل]بطاقتكم)?" +
                    "(?:\\s+(?:$NUM\\s*(?:جنيه|جم)?|egp\\s*$NUM))?",
            ),
            Regex("(?:للمزيد\\s+)?(?:ا)?تصل\\s+ب\\s*\\d{3,6}"),
            // "If you did not make this transaction, call 19666 to block your card" (and the Arabic wording)
            Regex(
                "if\\s+you\\s+did\\s+not\\s+make\\s+this\\s+transaction\\s*,?\\s*(?:please\\s+)?call\\s+\\d{3,6}" +
                    "(?:\\s+to\\s+(?:block|freeze)\\s+your\\s+card)?",
            ),
            Regex(
                "(?:اذا\\s+لم\\s+تقم\\s+بهذه\\s+العمليه|في\\s+حاله\\s+عدم\\s+قيامك\\s+بهذه\\s+العمليه)\\s*[,،]?\\s*" +
                    "(?:ا)?تصل\\s+ب\\s*\\d{3,6}(?:\\s+ل[\\p{L} ]{1,30})?",
            ),
            Regex("(?:لا\\s+تشارك|احذر)\\s+[\\p{L} ]{1,60}"),
            Regex("شكرا\\s+لاستخدامكم\\s+[\\p{L} ]{1,30}"),
            Regex("كاش\\s*باك[\\p{L}\\d .]{0,60}"),
            Regex("و?سيتم\\s+[\\p{L} ]{0,20}كاش\\s*باك[\\p{L}\\d .]{0,60}"),
        )

    private fun isNoise(clause: String): Boolean = clause.isBlank() || noiseClauses.any { it.matches(clause.trim()) }

    private val numbers = Regex(NUM)

    /**
     * The matching family ("F1".."F4") or null when the message must be confirmed by the user first. [norm] is the same text as
     * [t] with its case kept (the two are aligned character by character): a merchant or counterparty must LOOK like a name.
     */
    fun family(
        msg: IncomingMessage,
        norm: String,
        t: String,
        record: ParsedRecord,
    ): String? {
        // the status gate runs first: a failed, cancelled, pending, requested ... message is never recorded automatically
        // (on the whole text too: a fee-note clause such as "charge disputed" is cut before classification, its status is not)
        if (StatusGate.blocks(t) || StatusGate.blocks(SecretDetector.fold(TextNormalizer.normalizeText(msg.text)).lowercase())) return null
        val clauses = splitClauses(t)
        val originals = splitClauses(norm)
        if (clauses.isEmpty() || originals.size != clauses.size) return null
        val first = clauses.first()
        val rest = clauses.drop(1)
        val onlyNoise = rest.all(::isNoise)
        return when {
            msg.source == CaptureSource.NOTIFICATION -> instapayNotification(msg, clauses, originals, record)
            record.kind != TxKind.PURCHASE && record.kind != TxKind.CASH_WITHDRAWAL && !instantKind(record) -> null
            hsbc(first, originals.first(), record) -> if (onlyNoise) "F2" else null
            instant(first, originals.first(), record) -> if (onlyNoise) "F3" else null
            card(first, originals.first(), record) -> if (onlyNoise || cardTailIsNoise(rest, originals.drop(1))) "F1" else null
            else -> null
        }
    }

    /** "... at GOURMET EGYPT - Y. Your available limit ...", "BTECH - NASR CITY": up to two name tokens after the merchant. */
    private const val MAX_SUFFIX_TOKENS = 2

    private fun cardTailIsNoise(
        rest: List<String>,
        restOriginals: List<String>,
    ): Boolean {
        val head = rest.firstOrNull() ?: return true
        val original = restOriginals.firstOrNull().orEmpty()
        val tokens = original.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val isSuffix =
            tokens.size in 1..MAX_SUFFIX_TOKENS &&
                !isNoise(head) &&
                !StatusGate.blocks(head) &&
                tokens.none { StatusGate.isNonName(it) } &&
                nameTokens(tokens, allowStoreNumber = true) { latinNameToken(it) || it.all { c -> c in arabicLetter } }
        val tail = if (isSuffix) rest.drop(1) else rest
        return tail.all(::isNoise)
    }

    private fun instantKind(r: ParsedRecord) = r.kind == TxKind.TRANSFER_OUT || r.kind == TxKind.TRANSFER_IN || r.kind == TxKind.INCOME

    private fun splitClauses(t: String): List<String> =
        t
            .split(Regex("(?<=\\.)\\s+"))
            .map { it.trim().trimEnd('.').trim() }
            .filter { it.isNotEmpty() }

    private fun amountAgrees(
        text: String,
        record: ParsedRecord,
    ): Boolean = numbers.findAll(text).any { MessageParser.minorOf(it.value) == record.amountMinor }

    private val arabicLetter = '؀'..'ۿ'
    private const val MAX_LATIN_TOKENS = 6
    private const val MAX_ARABIC_TOKENS = 4

    private const val MAX_STORE_NUMBER_DIGITS = 3

    private fun isStoreNumber(token: String) = token.length in 1..MAX_STORE_NUMBER_DIGITS && token.all { it in '0'..'9' }

    /**
     * Every token is a name token, except that ONE purely numeric token of 1-3 digits (a branch or store number: "HARDEE S ELMERGHANY
     * 50", "SPINNEYS 3 ZAYED") is allowed when [allowStoreNumber] and a name token comes before it. A longer digit run is refused
     * here as well as by the MERCHANT regex; the label sent to the server stays digit-free (LabelNormalizer).
     */
    private fun nameTokens(
        tokens: List<String>,
        allowStoreNumber: Boolean,
        isName: (String) -> Boolean,
    ): Boolean {
        val numbers = tokens.count(::isStoreNumber)
        val numberOk = numbers == 0 || (allowStoreNumber && numbers == 1 && !isStoreNumber(tokens.first()))
        if (!numberOk) return false
        return tokens.all { isStoreNumber(it) || isName(it) }
    }

    /**
     * A merchant or counterparty slot must look like a name: nothing else may hide in it (closed world). [allowStoreNumber] is set
     * only for the card and HSBC merchant slots: a counterparty (InstaPay, instant transfer) is a person or handle, where a bare
     * number would be a phone or account fragment, so those slots stay strict.
     */
    private fun slotLooksLikeName(
        original: String,
        match: MatchResult,
        allowStoreNumber: Boolean = false,
    ): Boolean {
        // a family with no merchant or counterparty slot (the ATM line, the outgoing instant transfer) has no group "m"
        val group = runCatching { match.groups["m"] }.getOrNull()
        val range = group?.range ?: return true
        if (range.last >= original.length) return false
        val slot = original.substring(range).trim()
        val tokens = slot.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val hasLatin = tokens.any { tok -> tok.any { it in 'A'..'Z' || it in 'a'..'z' } }
        val hasArabic = tokens.any { tok -> tok.any { it in arabicLetter } }
        val plain = slot.all { it.isLetterOrDigit() || it in " .&'*-@/,#" }
        val statusFree = tokens.none { StatusGate.isNonName(it) || StatusGate.isStatusWord(it.lowercase()) }
        return when {
            !plain || !statusFree || (hasLatin && hasArabic) -> false
            hasArabic -> tokens.size <= MAX_ARABIC_TOKENS && nameTokens(tokens, allowStoreNumber) { tok -> tok.all { it in arabicLetter } }
            else -> tokens.size <= MAX_LATIN_TOKENS && nameTokens(tokens, allowStoreNumber, ::latinNameToken)
        }
    }

    /** "AHMED", "Sara", "WWW.NOON.COM", "amazon.eg", "*TRIP", "#12", "7-ELEVEN", a handle, a mobile number: a name, never a word. */
    private val instapayHandle = Regex("^[a-z0-9._-]{2,30}@instapay$")
    private val domainName = Regex("^[a-z0-9-]+(?:\\.[a-z0-9-]+)+$")
    private val storeNumber = Regex("^#\\d{1,4}$")
    private val egyptianMobile = Regex("^01[0125]\\d{8}$")

    private fun latinNameToken(raw: String): Boolean {
        val token = raw.trimEnd(',')
        if (listOf(instapayHandle, domainName, storeNumber, egyptianMobile).any { it.matches(token) }) {
            return true
        }
        val first = token.dropWhile { it == '*' }.firstOrNull() ?: return false
        val startsRight = first.isUpperCase() || (first.isDigit() && token.none { it.isLowerCase() })
        return startsRight && token.any { it.isLetter() }
    }

    private fun card(
        clause: String,
        original: String,
        record: ParsedRecord,
    ): Boolean {
        if (record.kind != TxKind.PURCHASE) return false
        val regexes = cardPurchaseEn + cardPurchaseAr
        val m = regexes.firstNotNullOfOrNull { it.find(clause) } ?: return false
        val proven = m.groups["l4"]?.value == record.instrumentLast4 && amountAgrees(clause, record)
        return proven && slotLooksLikeName(original, m, allowStoreNumber = true)
    }

    private fun hsbc(
        clause: String,
        original: String,
        record: ParsedRecord,
    ): Boolean {
        val purchase = hsbcPurchase.find(clause)
        val atm = hsbcAtm.find(clause)
        val m = purchase ?: atm ?: return false
        val kindOk = if (purchase != null) record.kind == TxKind.PURCHASE else record.kind == TxKind.CASH_WITHDRAWAL
        val proven = kindOk && MessageParser.minorOf(m.groups["amt"]!!.value) == record.amountMinor
        return proven && slotLooksLikeName(original, m, allowStoreNumber = true)
    }

    private fun instant(
        clause: String,
        original: String,
        record: ParsedRecord,
    ): Boolean {
        val out = instantOut.find(clause)
        val inn = instantIn.find(clause)
        val m = out ?: inn ?: return false
        val kindOk =
            if (out != null) {
                record.kind == TxKind.TRANSFER_OUT
            } else {
                record.kind == TxKind.INCOME || record.kind == TxKind.TRANSFER_IN
            }
        return kindOk && m.groups["l4"]?.value == record.instrumentLast4 && amountAgrees(clause, record) && slotLooksLikeName(original, m)
    }

    private fun instapayNotification(
        msg: IncomingMessage,
        clauses: List<String>,
        originals: List<String>,
        record: ParsedRecord,
    ): String? {
        if (msg.sender != NotificationApps.INSTAPAY_CANDIDATE) return null
        val bodyIndexes = clauses.indices.filterNot { instapayHeader.matches(clauses[it]) }
        if (bodyIndexes.size != 1) return null
        val clause = clauses[bodyIndexes.single()]
        val original = originals[bodyIndexes.single()]
        val outOk =
            (record.kind == TxKind.TRANSFER_OUT || record.kind == TxKind.PURCHASE) &&
                instapayOut.any { r -> r.find(clause)?.let { slotLooksLikeName(original, it) } == true }
        val inOk =
            (record.kind == TxKind.INCOME || record.kind == TxKind.TRANSFER_IN) &&
                instapayIn.any { r -> r.find(clause)?.let { slotLooksLikeName(original, it) } == true }
        return if ((outOk || inOk) && amountAgrees(clause, record)) "F4" else null
    }
}
