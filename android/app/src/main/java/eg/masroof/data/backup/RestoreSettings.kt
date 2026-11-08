package eg.masroof.data.backup

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import eg.masroof.data.answers.AnswerPrefs
import eg.masroof.data.answers.answerStore
import eg.masroof.data.bills.subscriptionStore
import eg.masroof.data.dataStore
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The settings of a checked backup: store name (see [BackupPrefsInventory]) to key to a typed value (Boolean, Int, String, Set<String>). */
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
    private const val MAX_STRING = 4_096
    private const val MAX_DAY = 31

    /**
     * Only keys on the IN list of [TYPES] are kept; anything else in the file (a store or a key that is OUT, or one a newer app added)
     * is ignored, so a session, a keyset or a device id can never come from a file. A known key with the wrong type or an absurd value
     * refuses the file (check 6).
     */
    fun parse(text: String): RestoredSettings {
        try {
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
            STRING -> (item.get("value") as? String ?: throw invalid()).also { if (it.length > MAX_STRING) throw invalid() }
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

/**
 * Applies the settings to the real stores, only the IN keys: each key in the allowlist is replaced by the file's value, or removed when
 * the file has none (the backup is the truth for these). Keys that are OUT (server URL, session, device id, hints) are not touched. The
 * answers store goes through [AnswerPrefs.clear] first so the erase epoch moves on and an Undo token from before the restore never applies.
 */
class AndroidRestoreSettings(
    private val context: Context,
    private val answers: AnswerPrefs,
) : RestoreSettingsApplier {
    override suspend fun apply(settings: RestoredSettings) {
        val stores: Map<String, DataStore<Preferences>> =
            mapOf(
                BackupPrefsInventory.STORE_SETTINGS to context.dataStore,
                BackupPrefsInventory.STORE_ANSWERS to context.answerStore,
                BackupPrefsInventory.STORE_SUBSCRIPTIONS to context.subscriptionStore,
            )
        for ((name, store) in stores) {
            if (name == BackupPrefsInventory.STORE_ANSWERS) answers.clear()
            val allowed = BackupPrefsInventory.DATASTORE_KEYS.getValue(name)
            val values = settings.stores[name].orEmpty()
            store.edit { p ->
                for (key in allowed) {
                    val type = TYPES.getValue(name).getValue(key)
                    when (type) {
                        BOOL -> p.remove(booleanPreferencesKey(key))
                        INT -> p.remove(intPreferencesKey(key))
                        STRING -> p.remove(stringPreferencesKey(key))
                        else -> p.remove(stringSetPreferencesKey(key))
                    }
                }
                for ((key, v) in values) {
                    if (key !in allowed) continue
                    @Suppress("UNCHECKED_CAST")
                    when (v) {
                        is Boolean -> p[booleanPreferencesKey(key)] = v
                        is Int -> p[intPreferencesKey(key)] = v
                        is String -> p[stringPreferencesKey(key)] = v
                        is Set<*> -> p[stringSetPreferencesKey(key)] = v as Set<String>
                    }
                }
            }
        }
        for ((file, allowed) in BackupPrefsInventory.SHARED_KEYS) {
            val values = settings.stores["shared:$file"].orEmpty()
            context.getSharedPreferences(file, Context.MODE_PRIVATE).edit(commit = true) {
                for (key in allowed) remove(key)
                for ((key, v) in values) {
                    if (key !in allowed) continue
                    when (v) {
                        is Boolean -> putBoolean(key, v)
                        is Int -> putInt(key, v)
                        is String -> putString(key, v)
                        is Set<*> -> putStringSet(key, v.filterIsInstance<String>().toSet())
                    }
                }
            }
        }
    }

    private companion object {
        val TYPES = RestoreSettingKeys.TYPES
        const val BOOL = RestoreSettingKeys.BOOL
        const val INT = RestoreSettingKeys.INT
        const val STRING = RestoreSettingKeys.STRING
    }
}
