package eg.masroof.ingest.backfill

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eg.masroof.R
import eg.masroof.core.CairoTime
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.ingest.NotificationPermission
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

const val SMS_BACKFILL_WORK = "sms-backfill"
private const val BACKFILL_CHANNEL_ID = "backfill_summary"
private const val BACKFILL_NOTICE_ID = 0x5B1F
private const val MAX_BACKFILL_ATTEMPTS = 10

/** What the user picked on "Read past SMS": the last 30 / 90 / 180 days, or everything since a date. */
sealed interface BackfillRange {
    val key: String

    data class Days(
        val days: Int,
    ) : BackfillRange {
        override val key get() = "D$days"
    }

    data class Since(
        val date: LocalDate,
    ) : BackfillRange {
        override val key get() = "C$date"
    }

    companion object {
        val DEFAULT: BackfillRange = Days(90)
        val CHOICES: List<Int> = listOf(30, 90, 180)

        /** The range a saved run was started with, so "Continue" resumes that run; null for an empty or unknown key. */
        fun parse(key: String): BackfillRange? =
            when {
                key.startsWith("D") -> key.drop(1).toIntOrNull()?.let { Days(it) }
                key.startsWith("C") -> runCatching { Since(LocalDate.parse(key.drop(1))) }.getOrNull()
                else -> null
            }
    }
}

/** Start of the first day of [range], in Africa/Cairo, as epoch millis. */
internal fun BackfillRange.fromMillis(nowMillis: Long): Long {
    val first =
        when (this) {
            is BackfillRange.Days -> CairoTime.dateOf(nowMillis).minusDays(days.toLong())
            is BackfillRange.Since -> date
        }
    return first.atStartOfDay(CairoTime.zone).toInstant().toEpochMilli()
}

/** Starts, resumes and cancels the one backfill run (unique work). The screen talks only to this, and reads [BackfillStore.state]. */
@Singleton
class BackfillController
    @Inject
    constructor(
        private val workManager: WorkManager,
        private val store: BackfillStore,
        private val clock: Clock,
    ) {
        val state get() = store.state

        /** The same choice as a run that was stopped or killed continues it; anything else starts a fresh run. */
        fun start(range: BackfillRange) {
            val now = clock.millis()
            val current = store.state.value
            val next =
                if (current.rangeKey == range.key && current.resumable) {
                    current.copy(phase = BackfillPhase.RUNNING)
                } else {
                    BackfillState(BackfillPhase.RUNNING, range.key, range.fromMillis(now), now + 1)
                }
            store.save(next)
            val request =
                OneTimeWorkRequestBuilder<SmsBackfillWorker>()
                    .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                    .build()
            workManager.enqueueUniqueWork(SMS_BACKFILL_WORK, ExistingWorkPolicy.KEEP, request)
        }

        /** Stops within one batch (the worker checks after every message) and keeps what was stored. */
        fun cancel() {
            workManager.cancelUniqueWork(SMS_BACKFILL_WORK)
            val s = store.state.value
            if (s.phase == BackfillPhase.RUNNING) store.save(s.copy(phase = BackfillPhase.STOPPED))
        }

        /** Hides a finished run's summary line (the screen goes back to the range picker). A stopped run keeps its resume point. */
        fun dismiss() {
            if (store.state.value.phase == BackfillPhase.DONE) store.save(BackfillState())
        }

        fun refresh() = store.refresh()
    }

@HiltWorker
class SmsBackfillWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val backfill: SmsBackfill,
        private val store: BackfillStore,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            // Blocked start: the run stays RUNNING with its saved position and is tried again later.
            if (DatabaseStartup.isBlocked()) return Result.retry()
            return try {
                backfill.run()
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("SwallowedException") _: SQLiteException,
            ) {
                // a database that cannot write (full disk, locked) wrote nothing: continue from the saved position
                if (runAttemptCount < MAX_BACKFILL_ATTEMPTS) {
                    Result.retry()
                } else {
                    store.save(store.state.value.copy(phase = BackfillPhase.STOPPED))
                    Result.failure()
                }
            }
        }
    }

/** The one "Read past SMS finished" notification: counts only, no sender, no text. Skipped without the notification permission. */
@Singleton
class AndroidBackfillNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val permission: NotificationPermission,
    ) : BackfillNotifier {
        override fun finished(state: BackfillState) {
            if (!permission.granted()) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel =
                NotificationChannel(
                    BACKFILL_CHANNEL_ID,
                    context.getString(R.string.backfill_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            manager.createNotificationChannel(channel)
            val open =
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                    PendingIntent.getActivity(
                        context,
                        0,
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }
            val text =
                context.resources.getQuantityString(R.plurals.backfill_notice_text, state.entries, state.entries, state.review)
            val notification =
                NotificationCompat
                    .Builder(context, BACKFILL_CHANNEL_ID)
                    .setSmallIcon(context.applicationInfo.icon)
                    .setContentTitle(context.getString(R.string.backfill_notice_title))
                    .setContentText(text)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
            manager.notify(BACKFILL_NOTICE_ID, notification)
        }
    }
