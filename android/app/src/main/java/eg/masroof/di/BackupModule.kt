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
import eg.masroof.data.backup.LocalIdKeyStore
import eg.masroof.data.backup.RestoreBlockers
import eg.masroof.data.backup.RestoreEnvironment
import eg.masroof.data.backup.RestoreSources
import eg.masroof.data.backup.RoomRestoreDatabase
import eg.masroof.data.backup.WorkBackupLauncher
import eg.masroof.data.backup.WorkRestoreBlockers
import eg.masroof.data.db.AppDatabase
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

    @Binds
    abstract fun gate(impl: eg.masroof.data.backup.FileRestoreGate): eg.masroof.data.backup.RestoreGate

    companion object {
        @Provides @Singleton
        fun restorer(
            db: AppDatabase,
            @ApplicationContext context: Context,
            answers: AnswerPrefs,
            @ReceiptsDir receiptsDir: File,
            clock: Clock,
            eraseLock: eg.masroof.data.auth.EraseLock,
        ): BackupRestorer =
            BackupRestorer(
                RestoreEnvironment(
                    db = RoomRestoreDatabase(db),
                    settings = AndroidRestoreSettings(context, answers),
                    // This phone's Keystore seals the restored key; the file's bytes are never stored as they are.
                    // TODO(STORY-182): bind to the no-mint LocalIdKey (install via LocalKeystore). The TinkKeystore-based key resets itself on a transient
                    // failure, so it is NOT used here; until 182 merges the key step fails (INCOMPLETE) rather than risk a silent replacement.
                    keys = LocalIdKeyStore { error("TODO(STORY-182): local-id key store not bound yet") },
                    epoch = { eraseLock.epoch },
                    receiptsDir = receiptsDir,
                    nowMillis = { clock.millis() },
                ),
            )
    }
}
