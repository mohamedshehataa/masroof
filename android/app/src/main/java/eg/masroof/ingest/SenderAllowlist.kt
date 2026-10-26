package eg.masroof.ingest

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale

/** A bank, wallet or fintech sender family. [prefixes] match a normalized sender id by prefix, [exact] by equality. */
data class KnownSender(
    val name: String,
    val prefixes: List<String> = emptyList(),
    val exact: List<String> = emptyList(),
)

/** Default allowlist of Egyptian banks and wallets (REQ-SMS-040). Sender ids are "verify current"; users can add more. */
object DefaultSenders {
    val all: List<KnownSender> =
        listOf(
            KnownSender("CIB", prefixes = listOf("CIB")),
            KnownSender("NBE", prefixes = listOf("NBE", "NATIONALBANK", "ALAHLI", "AHLY")),
            KnownSender("Banque Misr", prefixes = listOf("BANQUEMISR", "BMISR", "MISR"), exact = listOf("BM")),
            KnownSender("QNB", prefixes = listOf("QNB")),
            KnownSender("HSBC", prefixes = listOf("HSBC")),
            KnownSender("Banque du Caire", prefixes = listOf("BANQUEDUCAIRE", "CAIROBANK"), exact = listOf("BDC")),
            KnownSender("AAIB", prefixes = listOf("AAIB")),
            KnownSender("Alex Bank", prefixes = listOf("ALEXBANK")),
            KnownSender("ADIB", prefixes = listOf("ADIB")),
            KnownSender("FAB", exact = listOf("FAB")),
            KnownSender("Emirates NBD", prefixes = listOf("EMIRATESNBD", "ENBD")),
            KnownSender("Egyptian Gulf Bank", prefixes = listOf("EGBANK")),
            KnownSender("SAIB", prefixes = listOf("SAIB")),
            KnownSender("Blom", prefixes = listOf("BLOM")),
            KnownSender("Mashreq", prefixes = listOf("MASHREQ")),
            KnownSender("Credit Agricole", prefixes = listOf("CREDITAGRICOLE")),
            KnownSender("Faisal Islamic", prefixes = listOf("FAISAL")),
            KnownSender("Telda", prefixes = listOf("TELDA")),
            KnownSender("InstaPay", prefixes = listOf("INSTAPAY")),
            KnownSender("Vodafone Cash", prefixes = listOf("VODAFONE", "VFCASH"), exact = listOf("VF")),
            KnownSender("Orange Cash", prefixes = listOf("ORANGE")),
            KnownSender("e& money", prefixes = listOf("E&", "ETISALAT", "EMONEY")),
            KnownSender("WE Pay", prefixes = listOf("WEPAY")),
            KnownSender("Fawry", prefixes = listOf("FAWRY")),
            KnownSender("valU", prefixes = listOf("VALU")),
            KnownSender("CashCall", prefixes = listOf("CASHCALL")),
            KnownSender("Souhoola", prefixes = listOf("SOUHOOLA")),
            KnownSender("Meeza", prefixes = listOf("MEEZA")),
        )

    fun match(key: String): KnownSender? = all.firstOrNull { s -> s.exact.contains(key) || s.prefixes.any { key.startsWith(it) } }
}

/** Sender id as compared: upper case, letters/digits/& only. */
fun senderKey(sender: String): String =
    sender
        .uppercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() || it == '&' }

/**
 * Which senders are read at all (REQ-SMS-040/041). Defaults can be switched off, other senders added. A sender that is
 * only digits (a phone number) is never allowed by default: OTP and promo risk.
 */
class SenderPolicy(
    private val added: Set<String> = emptySet(),
    private val disabledDefaults: Set<String> = emptySet(),
) {
    fun isAllowed(sender: String): Boolean {
        val key = senderKey(sender)
        if (key.isEmpty()) return false
        if (added.contains(key)) return true
        val known = DefaultSenders.match(key) ?: return false
        return !disabledDefaults.contains(known.name)
    }
}

/** Settings the receiver can read synchronously and cheaply (SharedPreferences, no database). */
interface SenderSettings {
    fun policy(): SenderPolicy

    fun addSender(sender: String)

    fun removeSender(sender: String)

    fun setDefaultEnabled(
        name: String,
        enabled: Boolean,
    )

    fun suggestions(): List<String>

    fun suggest(sender: String)

    fun dismissSuggestion(sender: String)
}

class PrefsSenderSettings(
    private val prefs: SharedPreferences,
) : SenderSettings {
    override fun policy() = SenderPolicy(set(ADDED), set(DISABLED))

    private fun set(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty().toSet()

    private fun edit(
        key: String,
        change: (MutableSet<String>) -> Unit,
    ) {
        val next = set(key).toMutableSet()
        change(next)
        prefs.edit { putStringSet(key, next) }
    }

    override fun addSender(sender: String) {
        edit(ADDED) { it.add(senderKey(sender)) }
        dismissSuggestion(sender)
    }

    override fun removeSender(sender: String) = edit(ADDED) { it.remove(senderKey(sender)) }

    override fun setDefaultEnabled(
        name: String,
        enabled: Boolean,
    ) = edit(DISABLED) { if (enabled) it.remove(name) else it.add(name) }

    override fun suggestions(): List<String> = set(SUGGESTED).sorted()

    override fun suggest(sender: String) {
        val clean = sender.trim().take(MAX_SENDER)
        if (clean.isNotEmpty() && !policy().isAllowed(clean)) edit(SUGGESTED) { if (it.size < MAX_SUGGESTIONS) it.add(clean) }
    }

    override fun dismissSuggestion(sender: String) = edit(SUGGESTED) { it.removeAll { s -> senderKey(s) == senderKey(sender) } }

    private companion object {
        const val ADDED = "added"
        const val DISABLED = "disabled_defaults"
        const val SUGGESTED = "suggested"
        const val MAX_SENDER = 40
        const val MAX_SUGGESTIONS = 50
    }
}
