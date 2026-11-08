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
                    when (RestoreSettingKeys.TYPES.getValue(name).getValue(key)) {
                        RestoreSettingKeys.BOOL -> p.remove(booleanPreferencesKey(key))
                        RestoreSettingKeys.INT -> p.remove(intPreferencesKey(key))
                        RestoreSettingKeys.STRING -> p.remove(stringPreferencesKey(key))
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
}
