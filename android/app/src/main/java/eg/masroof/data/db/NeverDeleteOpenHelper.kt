package eg.masroof.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * Room becomes the only copy of the data (ADR-0009), so the framework's default reaction to a corruption report - deleting
 * `masroof.db` - is not acceptable. [SupportSQLiteOpenHelper.Callback.onCorruption] deletes the file by default; this wrapper
 * forwards every other callback to Room's own and makes `onCorruption` do nothing. The failing call still throws to its caller
 * (the startup shows "The update could not finish. Your data is safe") and the file stays for the safety copy or a restore.
 */
class NeverDeleteCallback(
    private val delegate: SupportSQLiteOpenHelper.Callback,
) : SupportSQLiteOpenHelper.Callback(delegate.version) {
    override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)

    override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)

    override fun onUpgrade(
        db: SupportSQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) = delegate.onUpgrade(db, oldVersion, newVersion)

    override fun onDowngrade(
        db: SupportSQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) = delegate.onDowngrade(db, oldVersion, newVersion)

    override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)

    override fun onCorruption(db: SupportSQLiteDatabase) = Unit
}

/** The framework open helper with [NeverDeleteCallback] in front of Room's callback. */
object NeverDeleteOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val guarded =
            SupportSQLiteOpenHelper.Configuration
                .builder(configuration.context)
                .name(configuration.name)
                .callback(NeverDeleteCallback(configuration.callback))
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                .build()
        return NeverDeleteOpenHelper(FrameworkSQLiteOpenHelperFactory().create(guarded))
    }
}

/** Marker type so a test can tell that the database was built through [NeverDeleteOpenHelperFactory]. */
class NeverDeleteOpenHelper(
    delegate: SupportSQLiteOpenHelper,
) : SupportSQLiteOpenHelper by delegate
