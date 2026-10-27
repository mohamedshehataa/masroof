package eg.masroof.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File

const val DB_NAME = "masroof.db"
const val DB_VERSION = 9

/** What the app found when it opened the database at start (REQ-AND-127/128). */
sealed interface DbStartup {
    data class Ready(
        val db: AppDatabase,
    ) : DbStartup

    /** Blocking message "The update could not finish. Your data is safe". Nothing was deleted. */
    data object CouldNotFinish : DbStartup

    /** Blocking message "Free up storage"; the next start retries. */
    data class FreeUpStorage(
        val neededMb: Long,
    ) : DbStartup
}

/** Foreign keys are off by default in SQLite; seeding runs after every open and is idempotent. */
object AppDatabaseCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys=ON")
        DefaultSeed.seed(db)
    }
}

/** Opens the database once per process: safety copy first, then migrate, then seed. Never deletes anything. */
object DatabaseStartup {
    @Volatile private var current: DbStartup? = null

    /** Runs the startup once (call from `Application.attachBaseContext`); later calls return the same result. */
    @Synchronized
    fun run(context: Context): DbStartup = current ?: open(context.applicationContext ?: context).also { current = it }

    /** The result of [run], or a blocked placeholder database if it did not run (never a silent migration). */
    fun state(): DbStartup = checkNotNull(current) { "DatabaseStartup.run was not called" }

    @Suppress("TooGenericExceptionCaught") // a failing migration must become the blocking message, whatever it threw
    fun open(
        context: Context,
        name: String = DB_NAME,
        migrations: Array<Migration> = ALL_MIGRATIONS,
        backupDir: File = context.noBackupFilesDir,
        backup: PreMigrationBackup = PreMigrationBackup(context.getDatabasePath(name), backupDir, DB_VERSION),
    ): DbStartup {
        when (val outcome = backup.run()) {
            is BackupOutcome.NeedsSpace -> return DbStartup.FreeUpStorage(outcome.neededMb)
            BackupOutcome.Unreadable -> return DbStartup.CouldNotFinish
            BackupOutcome.NotNeeded, is BackupOutcome.Done -> Unit
        }
        val db = build(context, name, migrations)
        return try {
            db.openHelper.writableDatabase // forces migration + seed now, so a failure is caught here
            DbStartup.Ready(db)
        } catch (ignored: RuntimeException) {
            runCatching { db.close() }
            DbStartup.CouldNotFinish
        }
    }

    @Suppress("SpreadOperator") // built once per process
    fun build(
        context: Context,
        name: String = DB_NAME,
        migrations: Array<Migration> = ALL_MIGRATIONS,
    ): AppDatabase =
        Room
            .databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*migrations)
            // Only the pre-release v1 (no shipped APK has it) may be recreated; v2..v8 are always migrated.
            .fallbackToDestructiveMigrationFrom(true, 1)
            .addCallback(AppDatabaseCallback)
            .build()

    /** A database that refuses every access: used while the startup is blocked, so no worker can touch the real file. */
    fun blocked(context: Context): AppDatabase =
        Room
            .databaseBuilder(context, AppDatabase::class.java, "blocked-never-opened.db")
            .openHelperFactory { BlockedHelper }
            .build()

    private object BlockedHelper : SupportSQLiteOpenHelper {
        override val databaseName: String = "blocked"
        override val writableDatabase: SupportSQLiteDatabase get() = error("database blocked at startup")
        override val readableDatabase: SupportSQLiteDatabase get() = error("database blocked at startup")

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) = Unit

        override fun close() = Unit
    }
}
