package eg.masroof.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eg.masroof.data.backup.BackupLauncher
import eg.masroof.data.backup.WorkBackupLauncher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds @Singleton
    abstract fun launcher(impl: WorkBackupLauncher): BackupLauncher
}
