package eg.masroof.di

import android.content.Context
import androidx.core.content.edit
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
import eg.masroof.data.db.MIGRATION_2_3
import eg.masroof.data.db.MIGRATION_3_4
import eg.masroof.data.db.MIGRATION_4_5
import eg.masroof.data.db.MIGRATION_5_6
import eg.masroof.data.db.MIGRATION_6_7
import eg.masroof.data.insights.ApiInsightsRemote
import eg.masroof.data.insights.DefaultInsightsRepository
import eg.masroof.data.insights.FileInsightsCache
import eg.masroof.data.insights.InsightsCache
import eg.masroof.data.insights.InsightsRemote
import eg.masroof.data.insights.InsightsRepository
import eg.masroof.data.remote.ApiRemoteSource
import eg.masroof.data.remote.ExpenseDeleter
import eg.masroof.data.remote.ParsedRemote
import eg.masroof.data.remote.RemoteSource
import eg.masroof.ingest.AndroidCapturedNotifier
import eg.masroof.ingest.AndroidNotificationPermission
import eg.masroof.ingest.CaptureData
import eg.masroof.ingest.CaptureGate
import eg.masroof.ingest.CapturedAlertSettings
import eg.masroof.ingest.CapturedNotifier
import eg.masroof.ingest.DeviceIdProvider
import eg.masroof.ingest.KeystoreLocalIdKey
import eg.masroof.ingest.LocalIdKey
import eg.masroof.ingest.NotificationPermission
import eg.masroof.ingest.NotificationSettings
import eg.masroof.ingest.PrefsCaptureData
import eg.masroof.ingest.PrefsCaptureGate
import eg.masroof.ingest.PrefsCapturedAlertSettings
import eg.masroof.ingest.PrefsNotificationSettings
import eg.masroof.ingest.PrefsSenderSettings
import eg.masroof.ingest.SenderSettings
import eg.masroof.ingest.SmsWorkScheduler
import eg.masroof.ingest.WorkManagerSmsScheduler
import eg.masroof.work.SyncScheduler
import eg.masroof.work.WorkManagerSyncScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import java.io.File
import java.time.Clock
import java.util.UUID
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

    @Binds abstract fun parsedRemote(impl: ApiRemoteSource): ParsedRemote

    @Binds abstract fun capturedNotifier(impl: AndroidCapturedNotifier): CapturedNotifier

    @Binds abstract fun expenseDeleter(impl: ApiRemoteSource): ExpenseDeleter

    @Binds abstract fun smsScheduler(impl: WorkManagerSmsScheduler): SmsWorkScheduler

    @Binds abstract fun insights(impl: DefaultInsightsRepository): InsightsRepository

    @Binds abstract fun insightsRemote(impl: ApiInsightsRemote): InsightsRemote
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
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .fallbackToDestructiveMigrationFrom(true, 1)
            .build()

    @Provides @Singleton
    fun workManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    @Provides @Singleton
    fun captureGate(
        @ApplicationContext context: Context,
    ): CaptureGate = PrefsCaptureGate(context.getSharedPreferences("masroof_capture", Context.MODE_PRIVATE))

    @Provides @Singleton
    fun localIdKey(
        @ApplicationContext context: Context,
    ): LocalIdKey = KeystoreLocalIdKey(context)

    @Provides @Singleton
    fun captureData(
        @ApplicationContext context: Context,
        key: LocalIdKey,
    ): CaptureData = PrefsCaptureData(context, key)

    @Provides @Singleton
    fun notificationPermission(
        @ApplicationContext context: Context,
    ): NotificationPermission = AndroidNotificationPermission(context)

    @Provides @Singleton
    fun capturedAlertSettings(
        @ApplicationContext context: Context,
    ): CapturedAlertSettings = PrefsCapturedAlertSettings(context.getSharedPreferences("masroof_captured_alerts", Context.MODE_PRIVATE))

    @Provides @Singleton
    fun notificationSettings(
        @ApplicationContext context: Context,
    ): NotificationSettings = PrefsNotificationSettings(context.getSharedPreferences("masroof_notifications", Context.MODE_PRIVATE))

    @Provides @Singleton
    fun senderSettings(
        @ApplicationContext context: Context,
    ): SenderSettings = PrefsSenderSettings(context.getSharedPreferences("masroof_senders", Context.MODE_PRIVATE))

    @Provides @Singleton
    fun deviceId(
        @ApplicationContext context: Context,
    ): DeviceIdProvider {
        val prefs = context.getSharedPreferences("masroof_device", Context.MODE_PRIVATE)
        return object : DeviceIdProvider {
            override fun get(): String =
                prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { id -> prefs.edit { putString("device_id", id) } }
        }
    }

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

    /** Last good analytics answer per period, as files in private storage (offline Insights; wiped on logout). */
    @Provides @Singleton
    fun insightsCache(
        @ApplicationContext context: Context,
    ): InsightsCache = FileInsightsCache(File(context.filesDir, "insights"))

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
