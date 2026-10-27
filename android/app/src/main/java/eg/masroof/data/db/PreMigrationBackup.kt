package eg.masroof.data.db

import android.annotation.SuppressLint
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The result of trying to take the safety copy (ADR-0009 D3, REQ-AND-128). */
sealed interface BackupOutcome {
    /** Fresh install, or the database is already at the app's version: nothing to copy. */
    data object NotNeeded : BackupOutcome

    data class Done(
        val copy: File,
    ) : BackupOutcome

    /** The copy could not be completed (I/O error or too little space). The migration must not run. */
    data class NeedsSpace(
        val neededMb: Long,
    ) : BackupOutcome

    /** The file cannot be opened as a database. Nothing is touched. */
    data object Unreadable : BackupOutcome
}

/**
 * Before a migration runs on an existing database: checkpoint the WAL, copy `masroof.db` into [backupDir] (the app's
 * `noBackupFilesDir`) as `pre-migration-v<N>.db`, and keep only that newest copy. A half-written copy is deleted.
 * Row contents are never read or logged.
 */
class PreMigrationBackup(
    private val dbFile: File,
    private val backupDir: File,
    private val targetVersion: Int,
    private val freeSpace: (File) -> Long = { freeBytes(it) },
    private val copy: (File, File) -> Unit = { from, to -> Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) },
) {
    @Suppress("TooGenericExceptionCaught") // any failure to read the file means "do not migrate", never "delete"
    fun run(): BackupOutcome {
        if (!dbFile.exists()) return BackupOutcome.NotNeeded
        val version =
            try {
                checkpointAndReadVersion()
            } catch (ignored: RuntimeException) {
                return BackupOutcome.Unreadable
            }
        if (version <= 0 || version >= targetVersion) return BackupOutcome.NotNeeded
        val neededMb = (dbFile.length() + BYTES_PER_MB - 1) / BYTES_PER_MB
        backupDir.mkdirs()
        val part = File(backupDir, "pre-migration-v$version.db.part")
        part.delete()
        if (freeSpace(backupDir) < dbFile.length()) return BackupOutcome.NeedsSpace(neededMb)
        val target = File(backupDir, "pre-migration-v$version.db")
        return try {
            copy(dbFile, part)
            if (part.length() != dbFile.length()) throw IOException("short copy")
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            backupDir
                .listFiles { f -> f.name.startsWith("pre-migration-") && f.name.endsWith(".db") && f != target }
                ?.forEach { it.delete() }
            BackupOutcome.Done(target)
        } catch (ignored: IOException) {
            part.delete()
            BackupOutcome.NeedsSpace(neededMb)
        }
    }

    private fun checkpointAndReadVersion(): Int {
        // The default error handler DELETES a corrupt file; a no-op handler keeps it on disk.
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE, DatabaseErrorHandler { })
        try {
            db.rawQuery("PRAGMA quick_check", null).use {
                check(it.moveToFirst() && it.getString(0) == "ok") { "database is not intact" }
            }
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            return db.version
        } finally {
            db.close()
        }
    }

    private companion object {
        // The copy needs plain free bytes; clearable cache space (StorageManager) is not something we can count on.
        @SuppressLint("UsableSpace")
        fun freeBytes(dir: File): Long = dir.usableSpace

        const val BYTES_PER_MB = 1024L * 1024L
    }
}
