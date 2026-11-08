package eg.masroof.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eg.masroof.data.answers.AnswerPrefs
import eg.masroof.data.backup.AndroidRestoreSettings
import eg.masroof.data.backup.AndroidRestoreSources
import eg.masroof.data.backup.BackupLauncher
import eg.masroof.data.backup.BackupRestorer
import eg.masroof.data.backup.LocalIdKeyInstaller
import eg.masroof.data.backup.RestoreBlockers
import eg.masroof.data.backup.RestoreEnvironment
import eg.masroof.data.backup.RestoreSources
import eg.masroof.data.backup.RoomRestoreDatabase
import eg.masroof.data.backup.WorkBackupLauncher
import eg.masroof.data.backup.WorkRestoreBlockers
import eg.masroof.data.db.AppDatabase
import eg.masroof.ingest.KeystoreLocalIdKey
import java.io.File
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds @Singleton
    abstract fun launcher(impl: WorkBackupLauncher): BackupLauncher

    @Binds
    abstract fun sources(impl: AndroidRestoreSources): RestoreSources

    @Binds
    abstract fun blockers(impl: WorkRestoreBlockers): RestoreBlockers

    companion object {
        @Provides @Singleton
        fun restorer(
            db: AppDatabase,
            @ApplicationContext context: Context,
            answers: AnswerPrefs,
            @ReceiptsDir receiptsDir: File,
            clock: Clock,
        ): BackupRestorer =
            BackupRestorer(
                RestoreEnvironment(
                    db = RoomRestoreDatabase(db),
                    settings = AndroidRestoreSettings(context, answers),
                    // This phone's Keystore seals the restored key; the file's bytes are never stored as they are.
                    keys = LocalIdKeyInstaller { KeystoreLocalIdKey(context).install(it) },
                    receiptsDir = receiptsDir,
                    nowMillis = { clock.millis() },
                ),
            )
    }
}
