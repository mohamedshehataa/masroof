package eg.masroof

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import eg.masroof.data.auth.DataEraser
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.ingest.SmsWorkScheduler
import javax.inject.Inject

@HiltAndroidApp
class MasroofApp :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var smsScheduler: SmsWorkScheduler

    @Inject lateinit var eraser: DataEraser

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Before Hilt builds anything: safety copy, migration and seeding (a blocked result shows a message in MainActivity).
        DatabaseStartup.run(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Captures still upload until STORY-171; no session event starts or stops them (REQ-AND-131).
        smsScheduler.scheduleUpload()
        // An erase that was cut short (process died) is finished before anything else is captured.
        eraser.recoverInterrupted()
    }
}
