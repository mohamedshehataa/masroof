package eg.masroof.data.backup

import java.io.File

/**
 * Where the writer stages table rows (encrypted with a key that only lives in memory): `noBackupFilesDir/backup-stage/`, which Android
 * never trims like the cache. A kill during a backup can leave files behind, so the folder is emptied when a backup starts (the unique
 * work means nothing else is using it) and by "Erase all data".
 */
object BackupStage {
    const val FOLDER = "backup-stage"

    fun dir(noBackupFilesDir: File): File = File(noBackupFilesDir, FOLDER)

    /** Deletes everything in the folder; returns how many files could not be deleted. */
    fun sweep(dir: File): Int = dir.listFiles().orEmpty().count { !it.deleteRecursively() }
}
