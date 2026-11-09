package eg.masroof.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eg.masroof.data.backup.BackupLauncher
import eg.masroof.data.backup.BackupSinkOpener
import eg.masroof.data.backup.WorkBackupLauncher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds @Singleton
    abstract fun launcher(impl: WorkBackupLauncher): BackupLauncher

    companion object {
        /** The provider stream of the destination the user picked; "wt" truncates it, so it is opened only after the key is read. */
        @Provides
        fun sinkOpener(
            @ApplicationContext context: Context,
        ): BackupSinkOpener = BackupSinkOpener { uri -> context.contentResolver.openOutputStream(uri, "wt") }
    }
}
