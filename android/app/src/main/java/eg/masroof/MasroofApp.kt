package eg.masroof

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.auth.SessionState
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.ingest.SmsWorkScheduler
import eg.masroof.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MasroofApp :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var scheduler: SyncScheduler

    @Inject lateinit var smsScheduler: SmsWorkScheduler

    @Inject lateinit var session: SessionManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Before Hilt builds anything: safety copy, migration and seeding (a blocked result shows a message in MainActivity).
        DatabaseStartup.run(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Anything left in the outbox from a previous run is retried as soon as the network allows.
        scheduler.schedule()
        smsScheduler.scheduleUpload()
        // Records captured while the session was expired wait in the outbox: every (re)sign-in sends them.
        appScope.launch {
            session.state
                .filterIsInstance<SessionState.SignedIn>()
                .collect { smsScheduler.scheduleUpload() }
        }
    }
}
