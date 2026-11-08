package eg.masroof.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eg.masroof.MainActivity
import eg.masroof.R
import eg.masroof.core.CairoTime
import eg.masroof.core.DigitStyle
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.ingest.NotificationPermission
import eg.masroof.ui.AppLanguage
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One thing to remind about today. [key] is stable per payment (or renewal ...) and is how "already reminded" is remembered.
 * [route] is where a tap goes (an allow-listed in-app route such as `bills/<planId>`). Never an account number or message text.
 */
data class DueReminder(
    val key: String,
    val label: String,
    val amount: Money,
    val dueDate: LocalDate,
    val route: String,
)

/**
 * A feature that has things falling due. Register one with a Hilt multibinding (`@Binds @IntoSet`) and the daily worker picks
 * it up without being edited. [dueOn] returns every item whose reminder window includes [today] (not yet filtered by the log).
 */
interface ReminderSource {
    suspend fun dueOn(today: LocalDate): List<DueReminder>
}

/** Remembers which reminders were already posted; survives restarts and reboots. */
interface ReminderLog {
    fun wasReminded(key: String): Boolean

    fun markReminded(key: String)

    /** Forgets everything ("Erase all data"). */
    fun clear()
}

/** For code paths that have no reminder log. */
object NoReminderLog : ReminderLog {
    override fun wasReminded(key: String) = false

    override fun markReminded(key: String) = Unit

    override fun clear() = Unit
}

class PrefsReminderLog(
    private val prefs: android.content.SharedPreferences,
) : ReminderLog {
    override fun wasReminded(key: String) = prefs.contains(key)

    override fun clear() {
        prefs.edit { clear() }
    }

    override fun markReminded(key: String) {
        // apply(): the in-memory value is set at once and written in the background
        prefs.edit { putLong(key, System.currentTimeMillis()) }
    }
}

/** Posts a reminder; [canPost] is false when the user has not allowed notifications. */
interface ReminderNotifier {
    fun canPost(): Boolean

    fun post(
        reminder: DueReminder,
        daysUntil: Int,
    )
}

/** The daily pass: every source, one notification per not-yet-reminded item, nothing when notifications are not allowed. */
@Singleton
class ReminderRunner
    @Inject
    constructor(
        private val sources: Set<@JvmSuppressWildcards ReminderSource>,
        private val log: ReminderLog,
        private val notifier: ReminderNotifier,
        private val clock: Clock,
    ) {
        /** Returns how many reminders were posted. */
        suspend fun run(): Int {
            // Without permission nothing is posted and nothing is marked, so a later grant is not flooded with stale ones.
            if (!notifier.canPost()) return 0
            val today = CairoTime.today(clock.instant())
            var posted = 0
            for (source in sources) {
                for (item in source.dueOn(today)) {
                    if (log.wasReminded(item.key)) continue
                    notifier.post(item, ChronoUnit.DAYS.between(today, item.dueDate).toInt())
                    log.markReminded(item.key)
                    posted++
                }
            }
            return posted
        }
    }

@HiltWorker
class BillsReminderWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val runner: ReminderRunner,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (DatabaseStartup.isBlocked()) return Result.retry()
            return try {
                runner.run()
                Result.success()
            } catch (_: SQLiteException) {
                Result.retry()
            }
        }
    }

/** Once a day around 09:00 Cairo, no exact alarm, no network, no foreground service. */
@Singleton
class BillsReminderScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val clock: Clock,
    ) {
        fun schedule() {
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(
                    DAILY_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<BillsReminderWorker>(1, TimeUnit.DAYS)
                        .setInitialDelay(delayUntilNextNine(clock.instant()), TimeUnit.MILLISECONDS)
                        .build(),
                )
        }

        companion object {
            const val DAILY_NAME = "bills-reminders-daily"
            private val NINE = LocalTime.of(9, 0)

            /** Milliseconds from [now] to the next 09:00 in Cairo (today's when it is still ahead, else tomorrow's). */
            fun delayUntilNextNine(now: Instant): Long {
                val local = now.atZone(CairoTime.zone)
                var next = local.toLocalDate().atTime(NINE).atZone(CairoTime.zone)
                if (!next.toInstant().isAfter(now)) {
                    next =
                        local
                            .toLocalDate()
                            .plusDays(1)
                            .atTime(NINE)
                            .atZone(CairoTime.zone)
                }
                return next.toInstant().toEpochMilli() - now.toEpochMilli()
            }
        }
    }

const val BILLS_CHANNEL_ID = "bill_reminders"
const val EXTRA_OPEN_ROUTE = "eg.masroof.OPEN_ROUTE"

/** "valU - iPhone 17 Pro: 3,250 EGP due tomorrow", in the app language. Provider, title and amount only. */
@Singleton
class AndroidReminderNotifier
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val permission: NotificationPermission,
    ) : ReminderNotifier {
        override fun canPost() = permission.granted()

        override fun post(
            reminder: DueReminder,
            daysUntil: Int,
        ) {
            val context = AppLanguage.wrap(appContext)
            val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    BILLS_CHANNEL_ID,
                    context.getString(R.string.bills_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.bills_channel_description) },
            )
            val notification =
                NotificationCompat
                    .Builder(appContext, BILLS_CHANNEL_ID)
                    .setSmallIcon(appContext.applicationInfo.icon)
                    .setContentTitle(text(context, reminder, daysUntil))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(
                        NotificationCompat
                            .Builder(appContext, BILLS_CHANNEL_ID)
                            .setSmallIcon(appContext.applicationInfo.icon)
                            .setContentTitle(context.getString(R.string.bills_reminder_public))
                            .build(),
                    ).setAutoCancel(true)
                    .setContentIntent(openIntent(reminder))
                    .build()
            // the key is the tag: two reminders can never share a notification, whatever their hashes
            manager.notify(reminder.key, 0, notification)
        }

        private fun text(
            context: Context,
            reminder: DueReminder,
            daysUntil: Int,
        ): String {
            val arabic =
                context.resources.configuration.locales[0]
                    .language == "ar"
            val digits = if (arabic) DigitStyle.ARABIC_INDIC else DigitStyle.WESTERN
            val amount = MoneyFormat.format(reminder.amount, arabic, digits)
            return when {
                daysUntil <= 0 -> context.getString(R.string.bills_reminder_today, reminder.label, amount)
                daysUntil == 1 -> context.getString(R.string.bills_reminder_tomorrow, reminder.label, amount)
                else ->
                    context.getString(
                        R.string.bills_reminder_days,
                        reminder.label,
                        amount,
                        eg.masroof.core.DateFormat
                            .number(daysUntil, digits),
                    )
            }
        }

        private fun openIntent(reminder: DueReminder): PendingIntent {
            // explicit and immutable: it only ever opens this app, on the route the app itself put there
            val intent =
                Intent(appContext, MainActivity::class.java)
                    .putExtra(EXTRA_OPEN_ROUTE, reminder.route)
                    // a distinct data URI per reminder keeps each PendingIntent (and its route extra) its own
                    .setData(
                        Uri
                            .Builder()
                            .scheme("masroof")
                            .authority("reminder")
                            .appendPath(reminder.key)
                            .build(),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                appContext,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
