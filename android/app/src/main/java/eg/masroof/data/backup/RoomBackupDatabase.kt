package eg.masroof.data.backup

import android.database.Cursor
import eg.masroof.data.db.AppDatabase
import java.io.File
import java.io.InputStream
import java.util.concurrent.Callable

/** Reads the backup tables straight from Room's cursor, one row at a time, so no table is ever held in memory. */
class RoomBackupDatabase(
    private val db: AppDatabase,
) : BackupDatabase {
    override fun <T> snapshot(block: () -> T): T = db.runInTransaction(Callable { block() })

    override fun count(table: String): Long = scalar("SELECT COUNT(*) FROM ${known(table)}")

    override fun liveEntryCount(): Long = scalar("SELECT COUNT(*) FROM expense WHERE deletedAtMillis IS NULL")

    private fun scalar(sql: String): Long = db.query(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    override fun forEachRow(
        table: String,
        action: (columns: List<String>, values: List<Any?>) -> Unit,
    ) {
        db.query("SELECT * FROM ${known(table)} ORDER BY rowid", null).use { c ->
            val columns = c.columnNames.toList()
            val values = arrayOfNulls<Any?>(columns.size)
            while (c.moveToNext()) {
                for (i in columns.indices) values[i] = value(c, i)
                action(columns, values.asList())
            }
        }
    }

    override fun receipts(): List<BackupReceiptRef> =
        db.query("SELECT id, fileName, sizeBytes FROM receipt ORDER BY createdAtMillis, id", null).use { c ->
            buildList { while (c.moveToNext()) add(BackupReceiptRef(c.getString(0), c.getString(1), c.getLong(2))) }
        }

    private fun known(table: String): String {
        require(BackupTables.INCLUDED.any { it.name == table }) { "not a backup table" }
        return table
    }

    // Integers and text only: money is Long minor units, instants are epoch millis, enums are text. A float or blob column would be a
    // schema change the backup does not know, so it fails loudly instead of being written wrong.
    private fun value(
        c: Cursor,
        i: Int,
    ): Any? =
        when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
            Cursor.FIELD_TYPE_STRING -> c.getString(i)
            else -> error("unsupported column type")
        }
}

/** Receipt files under the app-private receipts directory; a name that would leave the directory counts as missing. */
class DirectoryReceiptFiles(
    private val dir: File,
) : ReceiptFiles {
    override fun open(ref: BackupReceiptRef): InputStream? {
        val file = File(dir, ref.fileName)
        val inside = file.canonicalFile.parentFile == dir.canonicalFile
        return if (inside && file.isFile) file.inputStream() else null
    }
}
