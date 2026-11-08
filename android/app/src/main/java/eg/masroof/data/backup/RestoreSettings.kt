@file:Suppress("ThrowsCount", "NestedBlockDepth", "ComplexCondition") // one named failure per check of REQ-SEC-068

package eg.masroof.data.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Settings of a checked backup: store name (see [BackupPrefsInventory]) to key to a typed value (Boolean, Int, String, Set). */
class RestoredSettings(
    val stores: Map<String, Map<String, Any>>,
)

/** The type each allowlisted key has in its store: a crafted file can never put a string where the app reads a boolean. */
object RestoreSettingKeys {
    const val BOOL = "bool"
    const val INT = "int"
    const val STRING = "string"
    const val SET = "stringSet"

    val TYPES: Map<String, Map<String, String>> =
        mapOf(
            BackupPrefsInventory.STORE_SETTINGS to
                mapOf("arabic_digits" to BOOL, "digits_set" to BOOL, "month_start_day" to INT, "include_cash_withdrawals" to BOOL),
            BackupPrefsInventory.STORE_ANSWERS to
                mapOf(
                    "answered_entries" to SET,
                    "entry_links" to SET,
                    "ignored_cards" to SET,
                    "card_aliases" to SET,
                    "atm_choice" to STRING,
                    "kept_apart_pairs" to SET,
                    "merged_away" to SET,
                ),
            BackupPrefsInventory.STORE_SUBSCRIPTIONS to mapOf("anchor_days" to SET, "dismissed_suggestions" to SET, "price_notices" to SET),
            "shared:masroof_senders" to
                mapOf("sms_capture_enabled" to BOOL, "added" to SET, "disabled_defaults" to SET, "suggested" to SET),
            "shared:masroof_notifications" to
                mapOf("notifications_enabled" to BOOL, "packages_added" to SET, "packages_removed" to SET),
            "shared:masroof_captured_alerts" to mapOf("captured_alerts" to BOOL),
            "shared:masroof_ui" to mapOf("language" to STRING),
        )

    private const val MAX_SET = 200_000
    private const val MAX_DEPTH = 8
    private const val MAX_STRING = 4_096
    private const val MAX_DAY = 28

    /**
     * Only keys on the IN list of [TYPES] are kept; anything else in the file (a store or a key that is OUT, or one a newer app added)
     * is ignored, so a session, a keyset or a device id can never come from a file. A known key with the wrong type or an absurd value
     * refuses the file (check 6).
     */
    fun parse(text: String): RestoredSettings {
        try {
            if (!RestoreRows.depthOk(text, MAX_DEPTH)) throw invalid()
            val root = JSONObject(text)
            val stores = root.getJSONObject("stores")
            val result = LinkedHashMap<String, Map<String, Any>>()
            for (store in stores.keys()) {
                val types = TYPES[store] ?: continue
                val values = LinkedHashMap<String, Any>()
                val obj = stores.getJSONObject(store)
                for (key in obj.keys()) {
                    val expected = types[key] ?: continue
                    val item = obj.getJSONObject(key)
                    if (item.getString("type") != expected) throw invalid()
                    values[key] = value(expected, item, key)
                }
                result[store] = values
            }
            return RestoredSettings(result)
        } catch (e: JSONException) {
            throw invalid(e)
        }
    }

    private fun value(
        type: String,
        item: JSONObject,
        key: String,
    ): Any =
        when (type) {
            BOOL -> item.get("value") as? Boolean ?: throw invalid()
            INT ->
                (item.get("value") as? Int ?: throw invalid()).also {
                    if (key == "month_start_day" && it !in 1..MAX_DAY) throw invalid()
                }
            STRING ->
                (item.get("value") as? String ?: throw invalid()).also {
                    if (it.length > MAX_STRING || (key == "language" && it != "ar" && it != "en")) throw invalid()
                }
            else -> stringSet(item.get("value") as? JSONArray ?: throw invalid())
        }

    private fun stringSet(array: JSONArray): Set<String> {
        if (array.length() > MAX_SET) throw invalid()
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            out += (array.get(i) as? String ?: throw invalid()).also { if (it.length > MAX_STRING) throw invalid() }
        }
        return out
    }

    private fun invalid(cause: Throwable? = null): RestoreException =
        RestoreException(RestoreFailure.INVALID_CONTENT, step = 6).also { if (cause != null) it.addSuppressed(cause) }
}
