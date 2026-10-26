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

    // a merchant or counterparty stops at "and" / "then" / و / "(" and never holds a card-number-like run or a fee or credit word
    private const val MERCHANT =
        "(?:(?!\\s+(?:and|then|ثم)\\s)(?!\\s+و[\\p{L}])(?!\\d{4})(?!(?:fees?|commission|رسوم|عموله|deposit|salary|refund|reversal)\\b)" +
            "[^;:(]){1,60}?"
    private const val CPARTY = "(?:(?!\\s+(?:and|then|ثم)\\s)(?!\\s+و[\\p{L}])[^;:?!(]){1,60}"
    private const val NAME = "[\\p{L}][\\p{L} .'-]{1,40}?"
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
                    "$DATE_TAIL\\s+at\\s+$MERCHANT$DATE_TAIL$",
                ci,
            ),
            Regex(
                "^$PREFIX$CARD_EN\\s+(?:was|has\\s+been)\\s+used\\s+at\\s+$MERCHANT\\s+for\\s+$AMT$DATE_TAIL$",
                ci,
            ),
        )

    private const val AR_DATE_TAIL = "(?:\\s+(?:يوم|بتاريخ)\\s+$DATE(?:\\s+(?:الساعه\\s+)?$TIME)?)?"

    /** "... وسيتم إضافة كاش باك 25 جنيه إلى حسابك": a promised reward after the merchant is a trailer, not a status. */
    private const val REWARD_TAIL = "(?:\\s+و?سيتم\\s+[^;:]*?(?:كاش\\s*باك|نقاط)[^;:]*)?"

    private const val CARD_AR =
        "(?:من|ب|علي)\\s*(?:ال)?بطاق(?:ه|ت(?:ك|كم))(?:\\s+(?:الائتمانيه|الائتمان|الخصم))?\\s+(?:المنتهيه\\s+ب|المنتهي\\s+ب|رقم)?" +
            "\\s*[*•x]*\\s*(?<l4>\\d{4})"

    private val cardPurchaseAr =
        listOf(
            Regex(
                "^$PREFIX" + "تم\\s+(?:اجراء\\s+)?(?:خصم|شراء|سحب|عمليه(?:\\s+ناجحه)?)\\s+(?:(?:بمبلغ|بقيمه|مبلغ)\\s*)?$AMT\\s*" +
                    "$CARD_AR\\s+لدي\\s+$MERCHANT$AR_DATE_TAIL$REWARD_TAIL$",
                ci,
            ),
            // the mirrored order: the merchant before the card
            Regex(
                "^$PREFIX" +
                    "تم\\s+(?:خصم|شراء)\\s+(?:(?:بمبلغ|مبلغ)\\s*)?$AMT\\s+(?:لدي|من)\\s+$MERCHANT\\s+$CARD_AR" +
                    "$AR_DATE_TAIL$",
                ci,
            ),
        )

    private const val ACCOUNT = "[\\d*x\\-]{4,}(?:\\s[\\d*x\\-]+)*"
    private val hsbcPurchase =
        Regex(
            "^(?:from\\s+hsbc:\\s*)?\\d{2}[a-z]{3}\\d{2}\\s+$MERCHANT\\s+purchase\\s+from\\s+$ACCOUNT" +
                "\\s+egp\\s*($NUM)-\\s*your\\s+available\\s+balance.*$",
            ci,
        )
    private val hsbcAtm =
        Regex(
            "^(?:from\\s+hsbc:\\s*)?\\d{2}[a-z]{3}\\d{2}\\s+atm\\s+cash\\s+withdrawal\\s+from\\s+$ACCOUNT" +
                "\\s+egp\\s*($NUM)-\\s*your\\s+available\\s+balance.*$",
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
                "تم\\s+استقبال\\s+تحويل\\s+لحظي\\s+الي\\s+حسابكم\\s+(?:رقم\\s+)?[*•x]*(?<l4>\\d{4})\\s+بمبلغ\\s+$AMT\\s+من\\s+$NAME$TAIL3$",
            ci,
        )

    private val instapayHeader = Regex("^(?:instapay|انستاباي|تم\\s+التحويل\\s+بنجاح|تم\\s+الدفع\\s+بنجاح)$", ci)
    private val instapayOut =
        listOf(
            Regex("^you\\s+(?:have\\s+)?sent\\s+$AMT\\s+to\\s+$CPARTY$", ci),
            Regex("^(?:instapay\\s+)?payment\\s+of\\s+$AMT\\s+to\\s+$CPARTY$", ci),
            Regex("^تم\\s+تحويل\\s+$AMT\\s+الي\\s+$CPARTY$", ci),
            Regex("^تم\\s+دفع\\s+$AMT\\s+الي\\s+$CPARTY$", ci),
        )
    private val instapayIn =
        listOf(
            Regex("^you\\s+(?:have\\s+)?received\\s+$AMT\\s+from\\s+$CPARTY$", ci),
            Regex("^تم\\s+استلام\\s+$AMT\\s+من\\s+$CPARTY$", ci),
        )

    /** Balance, limit, fee, hotline and thank-you clauses are allowed after the principal clause of the card families. */
    private val noise =
        Regex(
            "balance|limit|avail|remaining|fee|charge|commission|call|hotline|ref|thank|share|otp|pin\\b|never|beware|www|offer|cashback|" +
                "earn|enjoy|points|discover|visit|auth|approval|code|txn|trx|رمز|كود|رصيد|المتاح|حد|رسوم|مصاريف|عموله|اتصل|شكرا|مرجع|" +
                "لا تشارك|احذر|الرمز|عروض|عرض|" +
                "استمتع|اكتشف|تابع|احصل|خصم",
            ci,
        )

    private val numbers = Regex(NUM)

    /** The matching family ("F1".."F4") or null when the message must be confirmed by the user first. */
    fun family(
        msg: IncomingMessage,
        t: String,
        record: ParsedRecord,
    ): String? {
        // the status gate runs first: a failed, cancelled, pending, requested ... message is never recorded automatically
        // (on the whole text too: a fee-note clause such as "charge disputed" is cut before classification, its status is not)
        if (StatusGate.blocks(t) || StatusGate.blocks(SecretDetector.fold(TextNormalizer.normalizeText(msg.text)).lowercase())) return null
        val clauses = splitClauses(t)
        if (clauses.isEmpty()) return null
        val first = clauses.first()
        val rest = clauses.drop(1)
        val onlyNoise = rest.all { noise.containsMatchIn(it) || it.isBlank() }
        return when {
            msg.source == CaptureSource.NOTIFICATION -> instapayNotification(msg, clauses, record)
            record.kind != TxKind.PURCHASE && record.kind != TxKind.CASH_WITHDRAWAL && !instantKind(record) -> null
            hsbc(first, record) -> if (onlyNoise) "F2" else null
            instant(first, record) -> if (onlyNoise) "F3" else null
            card(first, record) -> if (onlyNoise || cardTailIsNoise(rest)) "F1" else null
            else -> null
        }
    }

    /** "... at GOURMET EGYPT - Y. Your available limit ...": a short name after the merchant belongs to the merchant. */
    private val merchantSuffix = Regex("^[a-z][a-z .&'-]{0,25}$", ci)
    private val notASuffix = Regex("refund|revers|void|cancel|credit|cashback|return|fee|charge", ci)

    private fun cardTailIsNoise(rest: List<String>): Boolean {
        val head = rest.firstOrNull() ?: return true
        val tail =
            if (merchantSuffix.matches(head) &&
                !notASuffix.containsMatchIn(head) &&
                !noise.containsMatchIn(head)
            ) {
                rest.drop(1)
            } else {
                rest
            }
        return tail.all { noise.containsMatchIn(it) || it.isBlank() }
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

    private fun card(
        clause: String,
        record: ParsedRecord,
    ): Boolean {
        if (record.kind != TxKind.PURCHASE) return false
        val regexes = cardPurchaseEn + cardPurchaseAr
        val m = regexes.firstNotNullOfOrNull { it.find(clause) } ?: return false
        return m.groups["l4"]?.value == record.instrumentLast4 && amountAgrees(clause, record)
    }

    private fun hsbc(
        clause: String,
        record: ParsedRecord,
    ): Boolean {
        val purchase = hsbcPurchase.find(clause)
        val atm = hsbcAtm.find(clause)
        val m = purchase ?: atm ?: return false
        val kindOk = if (purchase != null) record.kind == TxKind.PURCHASE else record.kind == TxKind.CASH_WITHDRAWAL
        return kindOk && MessageParser.minorOf(m.groupValues[1]) == record.amountMinor
    }

    private fun instant(
        clause: String,
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
        return kindOk && m.groups["l4"]?.value == record.instrumentLast4 && amountAgrees(clause, record)
    }

    private fun instapayNotification(
        msg: IncomingMessage,
        clauses: List<String>,
        record: ParsedRecord,
    ): String? {
        if (msg.sender != NotificationApps.INSTAPAY_CANDIDATE) return null
        val body = clauses.filterNot { instapayHeader.matches(it) }
        if (body.size != 1) return null
        val clause = body.single()
        val out = instapayOut.any { it.matches(clause) } && (record.kind == TxKind.TRANSFER_OUT || record.kind == TxKind.PURCHASE)
        val inn = instapayIn.any { it.matches(clause) } && (record.kind == TxKind.INCOME || record.kind == TxKind.TRANSFER_IN)
        return if ((out || inn) && amountAgrees(clause, record)) "F4" else null
    }
}
