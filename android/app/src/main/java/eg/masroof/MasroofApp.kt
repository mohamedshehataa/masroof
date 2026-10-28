package eg.masroof

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import eg.masroof.data.auth.DataEraser
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.data.edit.PurgeScheduler
import eg.masroof.data.receipts.ReceiptStore
import eg.masroof.di.AppScope
import eg.masroof.ingest.cancelLeftoverUploadWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MasroofApp :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var eraser: DataEraser

    @Inject lateinit var purgeScheduler: PurgeScheduler

    @Inject lateinit var workManager: WorkManager

    @Inject lateinit var receipts: ReceiptStore

    @Inject @AppScope
    lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Before Hilt builds anything: safety copy, migration and seeding (a blocked result shows a message in MainActivity).
        DatabaseStartup.run(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Nothing is uploaded any more (ADR-0009): jobs an older version left behind are cancelled.
        cancelLeftoverUploadWork(workManager)
        // An erase that was cut short (process died) is finished before anything else is captured.
        eraser.recoverInterrupted()
        // Entries deleted more than 30 days ago are removed now and then once a day (REQ-EXP-046).
        purgeScheduler.schedule()
        // Receipt files with no row (a crash between copy and insert, a failed delete) are deleted (REQ-RCPT-019).
        if (!DatabaseStartup.isBlocked()) appScope.launch { runCatching { receipts.sweep() } }
    }
}
