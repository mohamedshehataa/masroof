package eg.masroof.ingest

import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ingest.parse.SecretDetector
import eg.masroof.ingest.parse.TextNormalizer

/** One received SMS (multi-part messages already joined). Never logged: [toString] hides the text. */
data class IncomingSms(
    val sender: String,
    val body: String,
    val timestampMillis: Long,
) {
    override fun toString() = "IncomingSms(ts=$timestampMillis)"

    fun toMessage() = IncomingMessage(CaptureSource.SMS, sender, body, timestampMillis)
}

/** Seam to WorkManager so the receiver logic is unit-testable. */
interface SmsWorkScheduler {
    /** Hand one allowlisted, non-OTP message (SMS or notification) to the background pipeline. */
    fun enqueueIntake(message: IncomingMessage)

    /** Only the review list's Save uses this until STORY-172 makes it local; no capture ever schedules an upload. */
    fun scheduleUpload()

    fun scheduleUploadRetry(
        delaySeconds: Long,
        attempt: Int,
    )
}

/**
 * What the BroadcastReceiver does: a sender lookup in SharedPreferences, a cheap secrecy check, and a WorkManager
 * enqueue. No parsing, no database, no network (REQ-SMS-020/021). A plain OTP and a message of a sender that is not
 * allowlisted never leave this function: an unknown money-looking sender only leaves a suggestion (its name).
 */
class SmsHandoff(
    private val settings: SenderSettings,
    private val scheduler: SmsWorkScheduler,
    private val gate: CaptureGate,
) {
    fun handOff(messages: List<IncomingSms>) {
        // Capture switched off (or an erase running): read nothing, suggest nothing, queue nothing. No session is involved.
        if (messages.isEmpty() || !gate.isOpen() || !settings.smsCaptureEnabled()) return
        val policy = settings.policy()
        for (sms in messages) {
            if (policy.isAllowed(sms.sender)) {
                if (!MessageHygiene.isPlainSecret(sms.body)) scheduler.enqueueIntake(sms.toMessage().capped())
            } else if (isSuggestible(sms)) {
                settings.suggest(sms.sender)
            }
        }
    }

    private fun isSuggestible(sms: IncomingSms): Boolean =
        sms.sender.any { it.isLetter() } && MoneyLike.looksLikeMoney(sms.body) && !MessageHygiene.isPlainSecret(sms.body)
}

/** WorkManager input data is capped at 10 KB; a bank message is far shorter. */
private const val MAX_TEXT = 2000

internal fun IncomingMessage.capped(): IncomingMessage {
    val clipped = text.take(MAX_TEXT)
    val normalized = TextNormalizer.normalizeText(clipped)
    // a code-like digit run is masked BEFORE the text goes into WorkManager's input data (which WorkManager stores in its database)
    return copy(text = if (SecretDetector.hasCodeLikeToken(normalized)) SecretDetector.maskLongCodes(normalized) else clipped)
}

/** Cheap keyword-level checks usable from the receiver. */
object MessageHygiene {
    /** A secret (OTP, PIN, verification code) that is not also a money message: dropped before WorkManager sees it. */
    fun isPlainSecret(text: String): Boolean {
        val norm = TextNormalizer.normalizeText(text)
        return SecretDetector.isSecret(norm) && !MoneyLike.looksLikeMoney(text)
    }
}

/** Cheap check that a text reads like a bank message: an amount next to an Egyptian-pound word. */
object MoneyLike {
    private val amountThenCurrency = Regex("\\d[\\d.,]*\\s*(?:egp|le|l\\.e|جنيه|ج\\.م|جم)(?![a-z])")
    private val currencyThenAmount = Regex("(?:egp|l\\.e|جنيه|ج\\.م)\\s*\\d")

    fun looksLikeMoney(body: String): Boolean {
        val text = SecretDetector.fold(TextNormalizer.normalizeText(body))
        return amountThenCurrency.containsMatchIn(text) || currencyThenAmount.containsMatchIn(text)
    }
}
