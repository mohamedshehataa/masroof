package eg.masroof.data.backup

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import eg.masroof.data.db.AppDatabase
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable

class ColumnSchema(
    val name: String,
    val integer: Boolean,
    val notNull: Boolean,
)

class ForeignKeySchema(
    val column: String,
    val parentTable: String,
    val parentColumn: String,
)

class TableSchema(
    val name: String,
    val columns: List<ColumnSchema>,
    val foreignKeys: List<ForeignKeySchema>,
)

/** The shape of the live database, read from SQLite itself so a backup is judged against exactly what this app version stores. */
class RestoreSchema(
    val tables: Map<String, TableSchema>,
)

/** Everything the restore needs from Room; the real one is [RoomRestoreDatabase]. */
interface RestoreDatabase {
    fun schema(): RestoreSchema

    fun liveEntryCount(): Long

    /** SQLite's `application_id` header field: Room never uses it, and it commits with the transaction that sets it. */
    fun applicationId(): Int

    /**
     * Replaces every table in ONE transaction: all backup and pipeline tables are emptied, then each of [tables] (table name to its
     * JSON-lines file) is inserted, [nonce] is written to `application_id`, and [beforeCommit] runs last. Any failure rolls everything back.
     */
    fun replaceAll(
        nonce: Int,
        tables: List<Pair<String, File>>,
        beforeCommit: () -> Unit,
    )
}

/** Boolean columns are INTEGER 0/1 in SQLite; Room's schema does not say which, so they are named. */
internal val BOOLEAN_COLUMNS =
    setOf("pending", "failed", "timeKnown", "excludedFromTotals", "archived", "system", "rollover", "active", "hiddenFromTotals")

class RoomRestoreDatabase(
    private val db: AppDatabase,
) : RestoreDatabase {
    override fun schema(): RestoreSchema {
        val tables = LinkedHashMap<String, TableSchema>()
        for (table in BackupTables.INCLUDED) {
            val name = known(table.name)
            // The table name is one of the fixed names above, never text from a file.
            val columns =
                db.query("PRAGMA table_info(" + name + ")", null).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(ColumnSchema(c.getString(1), c.getString(2).uppercase().contains("INT"), c.getInt(3) == 1))
                        }
                    }
                }
            val fks =
                db.query("PRAGMA foreign_key_list(" + name + ")", null).use { c ->
                    buildList { while (c.moveToNext()) add(ForeignKeySchema(c.getString(3), c.getString(2), c.getString(4))) }
                }
            tables[name] = TableSchema(name, columns, fks)
        }
        return RestoreSchema(tables)
    }

    override fun liveEntryCount(): Long = RoomBackupDatabase(db).liveEntryCount()

    override fun applicationId(): Int = db.query("PRAGMA application_id", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun replaceAll(
        nonce: Int,
        tables: List<Pair<String, File>>,
        beforeCommit: () -> Unit,
    ) {
        val schema = schema()
        db.runInTransaction(
            Callable {
                val raw = db.openHelper.writableDatabase
                // Foreign keys are checked at the commit, so the order of the inserts does not matter.
                raw.execSQL("PRAGMA defer_foreign_keys = ON")
                for (name in BackupTables.INCLUDED.map { it.name } + BackupTables.EXCLUDED) raw.delete(name, null, null)
                for ((name, file) in tables) {
                    val table = schema.tables.getValue(known(name))
                    RestoreRows.forEachLine(file, Int.MAX_VALUE) { line ->
                        raw.insert(name, SQLiteDatabase.CONFLICT_ABORT, values(table, line))
                    }
                }
                // Not a template: [nonce] is an Int, and this pragma is the only way to write a value that commits with the transaction.
                raw.execSQL("PRAGMA application_id = " + nonce)
                beforeCommit()
            },
        )
    }

    private fun values(
        table: TableSchema,
        line: String,
    ): ContentValues {
        val obj = JSONObject(line)
        val cv = ContentValues()
        for (c in table.columns) {
            val v = obj.get(c.name)
            when (v) {
                JSONObject.NULL -> cv.putNull(c.name)
                is String -> cv.put(c.name, v)
                is Int -> cv.put(c.name, v.toLong())
                is Long -> cv.put(c.name, v)
                else -> error("unvalidated value")
            }
        }
        return cv
    }

    private fun known(table: String): String {
        require(BackupTables.INCLUDED.any { it.name == table }) { "not a backup table" }
        return table
    }
}
