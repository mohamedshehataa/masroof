package eg.masroof.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eg.masroof.data.DataStoreSettings
import eg.masroof.data.DefaultExpenseRepository
import eg.masroof.data.ExpenseRepository
import eg.masroof.data.SettingsRepository
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.remote.ApiRemoteSource
import eg.masroof.data.remote.RemoteSource
import eg.masroof.work.SyncScheduler
import eg.masroof.work.WorkManagerSyncScheduler
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun repository(impl: DefaultExpenseRepository): ExpenseRepository

    @Binds abstract fun remote(impl: ApiRemoteSource): RemoteSource

    @Binds abstract fun settings(impl: DataStoreSettings): SettingsRepository

    @Binds abstract fun scheduler(impl: WorkManagerSyncScheduler): SyncScheduler
}

@Module
@InstallIn(SingletonComponent::class)
object ProvidersModule {
    @Provides @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): AppDatabase =
        Room
            .databaseBuilder(context, AppDatabase::class.java, "masroof.db")
            // Cache + a pre-release outbox only: v1 -> v2 (failed flag) is not worth a migration yet.
            .fallbackToDestructiveMigrationFrom(true, 1)
            .build()

    @Provides @Singleton
    fun workManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    @Provides
    fun clock(): Clock = Clock.systemUTC()
}
