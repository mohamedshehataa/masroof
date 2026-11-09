package eg.masroof.data.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import eg.masroof.data.answers.answerStore
import eg.masroof.data.bills.subscriptionStore
import eg.masroof.data.dataStore
import kotlinx.coroutines.flow.first

/**
 * Which phone state outside Room travels in `data/settings.json` (STORY-183). It is an allowlist per store: a key that is not named here
 * is never written, so a new secret can never slip in by being added to a store. Review text, OTPs, session tokens and server data are
 * not here on purpose.
 *
 * IN (rebuild the phone):
 * - DataStore `settings`: arabic_digits, digits_set (digit setting), month_start_day, include_cash_withdrawals (ATM rule).
 * - DataStore `answers` (STORY-208): answered_entries, entry_links (refund/transfer links with their nonce), ignored_cards,
 *   card_aliases, atm_choice, kept_apart_pairs and merged_away (STORY-173: they stop a restored phone re-flagging or double counting
 *   merged duplicates). Not the in-process erase epoch (it is not stored).
 * - DataStore `subscriptions` (STORY-203): anchor_days (incl. `since:<id>` resume cutoffs), dismissed_suggestions, price_notices.
 * - SharedPreferences `masroof_senders`: sms_capture_enabled, added, disabled_defaults, suggested (sender allowlist and capture switch).
 * - SharedPreferences `masroof_notifications`: notifications_enabled, packages_added, packages_removed (notification capture).
 * - SharedPreferences `masroof_captured_alerts`: captured_alerts (the "Recorded ... Undo" switch).
 * - SharedPreferences `masroof_ui`: language (the in-app language).
 *
 * OUT (and why):
 * - DataStore `settings` server_url: a server address belongs to one install and the server is retired (ADR-0009).
 * - SharedPreferences `masroof_session`, `masroof_keyset`: session tokens and the Keystore-wrapped keyset (never exported).
 * - `masroof_device` (device_id): the identity of this install for the frozen server upload, not user data.
 * - `masroof_capture` (erase_stage): in-progress state of an erase, never restored.
 * - `masroof_reminders`: which reminders were already shown; a restored phone may remind once more, which is harmless.
 * - `masroof_ui` voice_online_notice_seen: a one-time hint flag, shown again after a restore.
 * - `masroof_capture_key` (the local-id HMAC key): written as `keys/local-id.key`, not as a preference.
 * - `masroof_backup` (last backup line): it describes this phone's last file, not the data.
 *
 * Nothing is classified by hand only: BackupPrefsGuardTest scans the sources for every preference key and file and fails until each
 * one is on [DATASTORE_KEYS] / [SHARED_KEYS] (in) or [OUT_KEYS] / [OUT_FILES] (out, with the reason).
 * STORY-186's `masroof_backfill` (its 30-day memory is sealed with the device-bound LocalKeystore, so a restore on another phone could
 * not read it) and `masroof_local_keyset` are OUT.
 */
object BackupPrefsInventory {
    const val STORE_SETTINGS = "datastore:settings"
    const val STORE_ANSWERS = "datastore:answers"
    const val STORE_SUBSCRIPTIONS = "datastore:subscriptions"

    val DATASTORE_KEYS: Map<String, Set<String>> =
        mapOf(
            STORE_SETTINGS to setOf("arabic_digits", "digits_set", "month_start_day", "include_cash_withdrawals"),
            STORE_ANSWERS to
                setOf("answered_entries", "entry_links", "ignored_cards", "card_aliases", "atm_choice", "kept_apart_pairs", "merged_away"),
            STORE_SUBSCRIPTIONS to setOf("anchor_days", "dismissed_suggestions", "price_notices"),
        )

    /** SharedPreferences file name to its allowed keys; the store name in the backup is `shared:<file>`. */
    val SHARED_KEYS: Map<String, Set<String>> =
        mapOf(
            "masroof_senders" to setOf("sms_capture_enabled", "added", "disabled_defaults", "suggested"),
            "masroof_notifications" to setOf("notifications_enabled", "packages_added", "packages_removed"),
            "masroof_captured_alerts" to setOf("captured_alerts"),
            "masroof_ui" to setOf("language"),
        )

    /** SharedPreferences files that are deliberately not in the backup, with the reason. */
    val OUT_FILES: Map<String, String> =
        mapOf(
            "masroof_session" to "session tokens",
            "masroof_keyset" to "Keystore-wrapped keyset, never exported",
            "masroof_device" to "identity of this install for the frozen server upload",
            "masroof_capture" to "erase-in-progress state",
            "masroof_capture_key" to "the local-id key travels as keys/local-id.key, not as a preference",
            "masroof_reminders" to "which reminders were shown; a repeat reminder is harmless",
            "masroof_backup" to "describes this phone's last backup file, not the data",
            "masroof_server_import" to "state of the one-time server import (STORY-182): ids and flags, meaningless on another phone",
            "masroof_backfill" to
                "device-bound sealed memory; after a restore the 30-day memory starts empty, " +
                "recorded entries still dedupe by their HMAC ids",
            "masroof_local_keyset" to "device-bound key material of the phone's own LocalKeystore, never exported",
        )

    /** Per store (`datastore:<name>` / `shared:<file>`): keys that are deliberately not in the backup, with the reason. */
    val OUT_KEYS: Map<String, Map<String, String>> =
        mapOf(
            "datastore:settings" to
                mapOf(
                    "server_url" to "a server address belongs to one install",
                    "access_token" to "legacy plaintext token, removed at upgrade",
                ),
            "shared:masroof_session" to mapOf("session" to "session tokens", "last_member" to "session state"),
            "shared:masroof_device" to mapOf("device_id" to "install identity for the frozen server upload"),
            "shared:masroof_server_import" to
                mapOf(
                    "pending" to "an import is on offer on this phone",
                    "forget" to "forgetting the server was started on this phone",
                ),
            "shared:masroof_capture" to mapOf("erase_stage" to "erase-in-progress state"),
            "shared:masroof_capture_key" to mapOf("k" to "sealed local-id key; travels as keys/local-id.key"),
            "shared:masroof_ui" to mapOf("voice_online_notice_seen" to "one-time hint, shown again after a restore"),
            "shared:masroof_local_keyset" to mapOf("master_made" to "device-bound: whether this phone's Keystore made the master key"),
            "shared:masroof_backfill" to
                mapOf(
                    "phase" to "device-bound sealed memory and run state of Read past SMS; a restore starts it empty",
                    "range_key" to "device-bound sealed memory and run state of Read past SMS; a restore starts it empty",
                    "from_day" to "device-bound sealed memory and run state of Read past SMS; a restore starts it empty",
                    "to_day" to "device-bound sealed memory and run state of Read past SMS; a restore starts it empty",
                    "total" to "device-bound sealed memory and run state of Read past SMS; a restore starts it empty",
                    "read" to "device-bound sealed memory and run state of Read past SMS; a restore starts it empty",
                    "entries" to "device-bound sealed memory and run state of Read past SMS; a restore starts it empty",
                    "review" to "device-bound sealed memory and run state of Read past SMS; a restore starts it empty",
                    "last_day" to "device-bound sealed memory and run state of Read past SMS; a restore starts it empty",
                    "day_read" to "device-bound sealed memory and run state of Read past SMS; a restore starts it empty",
                    "last_id" to "device-bound sealed memory and run state of Read past SMS; a restore starts it empty",
                ),
            "shared:masroof_backup" to
                mapOf(
                    "last_at" to "last backup line",
                    "last_entries" to "last backup line",
                    "last_receipts" to "last backup line",
                    "last_bytes" to "last backup line",
                    "ack_id" to "which finished backup run the user has seen",
                ),
        )
}

/** Reads the allowlisted keys from the real stores. */
class AndroidBackupPrefs(
    private val context: Context,
) : BackupPrefs {
    override suspend fun snapshot(): Map<String, Map<String, Any>> {
        val result = LinkedHashMap<String, Map<String, Any>>()
        val stores: Map<String, DataStore<Preferences>> =
            mapOf(
                BackupPrefsInventory.STORE_SETTINGS to context.dataStore,
                BackupPrefsInventory.STORE_ANSWERS to context.answerStore,
                BackupPrefsInventory.STORE_SUBSCRIPTIONS to context.subscriptionStore,
            )
        for ((name, store) in stores) {
            val allowed = BackupPrefsInventory.DATASTORE_KEYS.getValue(name)
            val values = LinkedHashMap<String, Any>()
            for ((key, value) in store.data.first().asMap()) {
                if (key.name in allowed) values[key.name] = value
            }
            result[name] = values
        }
        for ((file, allowed) in BackupPrefsInventory.SHARED_KEYS) {
            val values = LinkedHashMap<String, Any>()
            for ((key, value) in context.getSharedPreferences(file, Context.MODE_PRIVATE).all) {
                if (key in allowed && value != null) values[key] = value
            }
            result["shared:$file"] = values
        }
        return result
    }
}
