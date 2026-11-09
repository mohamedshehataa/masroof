package eg.masroof.data.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The "Last backup: today 14:05, 1,240 entries, 37 receipts, 58 MB" line. Counts only; written only after a complete file. */
data class LastBackup(
    val atMillis: Long,
    val entries: Long,
    val receipts: Int,
    val bytes: Long,
)

/**
 * The last successful backup of this phone. It describes the last file, not the data, so it is not part of the backup and an
 * "Erase all data" is free to keep or clear it (it holds no personal data).
 */
@Singleton
class BackupStatusStore
    constructor(
        private val prefs: SharedPreferences,
    ) {
        @Inject
        constructor(
            @ApplicationContext context: Context,
        ) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

        private val state = MutableStateFlow(read())
        val last: StateFlow<LastBackup?> get() = state

        private val acknowledgedState = MutableStateFlow(prefs.getString(ACK, null))

        /** The id of the finished run whose result the user has seen (success, failure or cancel); that run is shown as idle. */
        val acknowledged: StateFlow<String?> get() = acknowledgedState

        fun acknowledge(workId: String) {
            prefs.edit(commit = true) { putString(ACK, workId) }
            acknowledgedState.value = workId
        }

        fun record(backup: LastBackup) {
            prefs.edit(commit = true) {
                putLong(AT, backup.atMillis)
                putLong(ENTRIES, backup.entries)
                putInt(RECEIPTS, backup.receipts)
                putLong(BYTES, backup.bytes)
            }
            state.value = backup
        }

        private fun read(): LastBackup? =
            if (prefs.contains(AT)) {
                LastBackup(prefs.getLong(AT, 0L), prefs.getLong(ENTRIES, 0L), prefs.getInt(RECEIPTS, 0), prefs.getLong(BYTES, 0L))
            } else {
                null
            }

        private companion object {
            const val FILE = "masroof_backup"
            const val AT = "last_at"
            const val ENTRIES = "last_entries"
            const val RECEIPTS = "last_receipts"
            const val BYTES = "last_bytes"
            const val ACK = "ack_id"
        }
    }

/**
 * Hands the derived key of one backup from the screen to the foreground task in memory only. The passphrase itself is gone before this
 * is filled; WorkManager's database never sees a secret. If the process dies before the task starts the key is gone and the task fails
 * with [BackupFailure.KEY_LOST]: the user simply starts again.
 */
@Singleton
class PendingBackupKey
    @Inject
    constructor() {
        private val pending = HashMap<UUID, BackupInput>()

        /** Keyed by the work request's id, so a task takes exactly its own key and never another request's. */
        @Synchronized
        fun put(
            id: UUID,
            input: BackupInput,
        ) {
            pending.put(id, input)?.key?.fill(0)
        }

        @Synchronized
        fun take(id: UUID): BackupInput? = pending.remove(id)

        /** The request was not enqueued: its key must not stay in memory. */
        @Synchronized
        fun discard(id: UUID) {
            pending.remove(id)?.key?.fill(0)
        }
    }

/** Why a backup failed, as a code the UI turns into text. A provider's own message is never shown or logged: it may hold a file name. */
enum class BackupFailure {
    STORAGE_FULL,
    CANNOT_OPEN,
    RECEIPT_CHANGED,
    KEY_LOST,

    /** The local-id key could not be read right now (a busy Keystore): nothing was written; try again. */
    KEY_UNAVAILABLE,
    OTHER,
    ;

    companion object {
        fun classify(e: Throwable): BackupFailure =
            when {
                e is BackupSourceChangedException -> RECEIPT_CHANGED
                e is eg.masroof.ingest.LocalIdKeyUnavailableException -> KEY_UNAVAILABLE
                e is FileNotFoundException -> CANNOT_OPEN
                e is IOException && e.message.orEmpty().let { "ENOSPC" in it || "space" in it.lowercase() } -> STORAGE_FULL
                else -> OTHER
            }
    }
}
