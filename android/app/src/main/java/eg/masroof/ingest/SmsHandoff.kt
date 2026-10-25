package eg.masroof.ingest

import java.security.MessageDigest

/** One received SMS (multi-part messages already joined). Never logged: [toString] hides the text. */
data class IncomingSms(
    val sender: String,
    val body: String,
    val timestampMillis: Long,
) {
    override fun toString() = "IncomingSms(ts=$timestampMillis)"
}

/** Seam to WorkManager so the receiver logic is unit-testable. */
interface SmsWorkScheduler {
    /** Hand one allowlisted, non-OTP message to the background pipeline. */
    fun enqueueIntake(sms: IncomingSms)

    fun scheduleUpload()

    fun scheduleUploadRetry(
        delaySeconds: Long,
        attempt: Int,
    )
}

/**
 * What the BroadcastReceiver does: a sender lookup in SharedPreferences, a keyword check, and a WorkManager enqueue. No
 * parsing, no database, no network (REQ-SMS-020/021). OTPs and messages of senders that are not allowlisted never leave
 * this function: an unknown money-looking sender only leaves a suggestion (its name), never its text.
 */
class SmsHandoff(
    private val settings: SenderSettings,
    private val scheduler: SmsWorkScheduler,
) {
    fun handOff(messages: List<IncomingSms>) {
        if (messages.isEmpty()) return
        val policy = settings.policy()
        for (sms in messages) {
            if (policy.isAllowed(sms.sender)) {
                if (OtpPromoFilter.classify(sms.body) != MessageClass.OTP) scheduler.enqueueIntake(sms.copy(body = sms.body.take(MAX_BODY)))
            } else if (isSuggestible(sms)) {
                settings.suggest(sms.sender)
            }
        }
    }

    private fun isSuggestible(sms: IncomingSms): Boolean =
        sms.sender.any { it.isLetter() } &&
            MoneyLike.looksLikeMoney(sms.body) &&
            OtpPromoFilter.classify(sms.body) == MessageClass.OTHER
}

/** Cheap check that a text reads like a bank message: an amount next to an Egyptian-pound word. */
object MoneyLike {
    private val amountThenCurrency = Regex("\\d[\\d.,]*\\s*(?:egp|le|l\\.e|جنيه|ج\\.م|جم)(?![a-z])")
    private val currencyThenAmount = Regex("(?:egp|l\\.e|جنيه|ج\\.م)\\s*\\d")

    fun looksLikeMoney(body: String): Boolean {
        val text = OtpPromoFilter.normalize(body)
        return amountThenCurrency.containsMatchIn(text) || currencyThenAmount.containsMatchIn(text)
    }
}

/** Stable id of a message so the live receiver and a later backfill agree: sender, sent-second and normalized text. */
fun smsId(sms: IncomingSms): String {
    val material = "${senderKey(sms.sender)}|${sms.timestampMillis / MILLIS_PER_SECOND}|${OtpPromoFilter.normalize(sms.body)}"
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    return digest.take(ID_BYTES).joinToString("") { "%02x".format(it) }
}

private const val MILLIS_PER_SECOND = 1000L
private const val ID_BYTES = 16

/** WorkManager input data is capped at 10 KB; the server accepts 2000 characters anyway. */
private const val MAX_BODY = 2000
