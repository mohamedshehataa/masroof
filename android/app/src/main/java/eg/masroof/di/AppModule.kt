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
import eg.masroof.data.ConnectivityNetworkMonitor
import eg.masroof.data.DataStoreSettings
import eg.masroof.data.DefaultExpenseRepository
import eg.masroof.data.ExpenseRepository
import eg.masroof.data.NetworkMonitor
import eg.masroof.data.SettingsRepository
import eg.masroof.data.auth.AeadTokenCipher
import eg.masroof.data.auth.ApiAuthRemote
import eg.masroof.data.auth.AuthRemote
import eg.masroof.data.auth.EncryptedSessionStore
import eg.masroof.data.auth.LazyTokenCipher
import eg.masroof.data.auth.LocalDataWiper
import eg.masroof.data.auth.RoomDataWiper
import eg.masroof.data.auth.SessionStore
import eg.masroof.data.auth.TinkKeystore
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.remote.ApiRemoteSource
import eg.masroof.data.remote.RemoteSource
import eg.masroof.work.SyncScheduler
import eg.masroof.work.WorkManagerSyncScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun repository(impl: DefaultExpenseRepository): ExpenseRepository

    @Binds abstract fun remote(impl: ApiRemoteSource): RemoteSource

    @Binds abstract fun settings(impl: DataStoreSettings): SettingsRepository

    @Binds abstract fun scheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    @Binds abstract fun authRemote(impl: ApiAuthRemote): AuthRemote

    @Binds abstract fun wiper(impl: RoomDataWiper): LocalDataWiper

    @Binds abstract fun network(impl: ConnectivityNetworkMonitor): NetworkMonitor
}

@Module
@InstallIn(SingletonComponent::class)
object ProvidersModule {
    private const val TIMEOUT_SECONDS = 15L

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

    @Provides @IoDispatcher
    fun io(): CoroutineDispatcher = Dispatchers.IO

    /** No auth interceptor/authenticator: used for login/refresh/logout and as the base of the authenticated client. */
    @Provides @Singleton
    fun bareClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    @Provides @Singleton
    fun sessionStore(
        @ApplicationContext context: Context,
    ): SessionStore =
        EncryptedSessionStore(
            context.getSharedPreferences("masroof_session", Context.MODE_PRIVATE),
            // Lazy: the Keystore is touched on the first (IO-thread) read, not at injection time on the main thread.
            LazyTokenCipher { AeadTokenCipher(TinkKeystore.aead(context)) },
        )
}
