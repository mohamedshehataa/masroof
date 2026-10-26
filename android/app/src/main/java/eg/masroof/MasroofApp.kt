package eg.masroof

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import eg.masroof.ingest.SmsWorkScheduler
import eg.masroof.work.SyncScheduler
import javax.inject.Inject

@HiltAndroidApp
class MasroofApp :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var scheduler: SyncScheduler

    @Inject lateinit var smsScheduler: SmsWorkScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Anything left in the outbox from a previous run is retried as soon as the network allows.
        scheduler.schedule()
        smsScheduler.scheduleUpload()
    }
}
