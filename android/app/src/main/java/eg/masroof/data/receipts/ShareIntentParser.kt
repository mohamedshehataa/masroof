package eg.masroof.data.receipts

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** What a share intent asks for: one slot per item (null = refused, nothing is read from it), at most [ShareIntentParser.MAX_ITEMS]. */
data class SharePlan(
    val items: List<Uri?>,
    /** More than ten were shared; only the first ten are taken. */
    val overflow: Boolean,
)

/**
 * REQ-RCPT-022. Reads only `EXTRA_STREAM` of an `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent: never `EXTRA_TEXT`, never clip data,
 * never any other extra. Only `content://` URIs of another app's authority are accepted; `file://`, schemeless and Masroof's own
 * authorities are refused without being opened.
 */
object ShareIntentParser {
    const val MAX_ITEMS = 10

    @Suppress("TooGenericExceptionCaught") // a hostile extra can throw BadParcelableException, ClassCastException, ...
    fun parse(
        intent: Intent,
        ownPackage: String,
    ): SharePlan {
        val streams: List<Uri?> =
            try {
                when (intent.action) {
                    Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                    Intent.ACTION_SEND_MULTIPLE ->
                        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                    else -> emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
        return SharePlan(streams.take(MAX_ITEMS).map { it?.takeIf { uri -> accepted(uri, ownPackage) } }, streams.size > MAX_ITEMS)
    }

    fun accepted(
        uri: Uri,
        ownPackage: String,
    ): Boolean {
        if (!"content".equals(uri.scheme, ignoreCase = true)) return false
        val authority = uri.authority?.lowercase().orEmpty()
        if (authority.isEmpty() || '@' in authority) return false
        return !authority.contains(ownPackage.lowercase())
    }
}
