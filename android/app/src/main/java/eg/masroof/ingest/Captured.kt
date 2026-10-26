package eg.masroof.ingest

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eg.masroof.R
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CapturedTxEntity
import eg.masroof.data.db.capturedCutoff
import eg.masroof.data.remote.ExpenseDeleter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** The safety net of automatic capture (round 18): whether the "Recorded ... Undo" notification is shown. On by default. */
interface CapturedAlertSettings {
    fun enabled(): Boolean

    fun setEnabled(on: Boolean)
}

class PrefsCapturedAlertSettings(
    private val prefs: SharedPreferences,
) : CapturedAlertSettings {
    override fun enabled() = prefs.getBoolean(ENABLED, true)

    override fun setEnabled(on: Boolean) = prefs.edit { putBoolean(ENABLED, on) }

    private companion object {
        const val ENABLED = "captured_alerts"
    }
}

/** Whether the system lets this app post notifications (a runtime permission on Android 13+). */
interface NotificationPermission {
    fun granted(): Boolean
}

class AndroidNotificationPermission(
    private val context: Context,
) : NotificationPermission {
    override fun granted() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/** What the pipeline calls right after it recorded an expense on its own (a proven family or a learned format). */
interface CapturedNotifier {
    fun captured(item: CapturedTxEntity)

    /** The notification of one record (its Undo was used somewhere else). */
    fun cancel(localId: String)

    /** Every notification of captured transactions (logout, account switch): nothing of the old account stays on the lock screen. */
    fun cancelAll()
}

object NoCapturedNotifier : CapturedNotifier {
    override fun captured(item: CapturedTxEntity) = Unit

    override fun cancel(localId: String) = Unit

    override fun cancelAll() = Unit
}

const val CAPTURED_CHANNEL_ID = "captured_transactions"
const val ACTION_UNDO_CAPTURED = "eg.masroof.action.UNDO_CAPTURED"
const val ACTION_STOP_LEARNING = "eg.masroof.action.STOP_LEARNING"
const val ACTION_KEEP_LEARNING = "eg.masroof.action.KEEP_LEARNING"
const val EXTRA_LOCAL_ID = "localId"
const val EXTRA_TEMPLATE_ID = "templateId"

/**
 * The low-priority "Recorded 500 EGP at AMAZON - Undo" notification. It carries ONLY the amount, the currency and the digit-free
 * label: never any message text. No sound, no vibration; it is skipped when the user turned it off in Settings or the system
 * notification permission was not granted.
 */
@Singleton
class AndroidCapturedNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: CapturedAlertSettings,
        private val permission: NotificationPermission = AndroidNotificationPermission(context),
    ) : CapturedNotifier {
        override fun captured(item: CapturedTxEntity) {
            if (!canPost()) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            ensureChannel(manager)
            val arabic =
                context.resources.configuration.locales[0]
                    .language == "ar"
            val amount = MoneyFormat.format(Money(item.amountMinor, item.currency), arabic, eg.masroof.core.DigitStyle.WESTERN)
            val title =
                item.label?.let { context.getString(R.string.captured_title_at, amount, it) }
                    ?: context.getString(R.string.captured_title, amount)
            val undo = receiverIntent(ACTION_UNDO_CAPTURED, item.localId, null, requestCode = item.localId.hashCode())
            val notification =
                NotificationCompat
                    .Builder(context, CAPTURED_CHANNEL_ID)
                    .setSmallIcon(context.applicationInfo.icon)
                    .setContentTitle(title)
                    .setContentText(context.getString(R.string.captured_text))
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicVersion())
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(false)
                    .addAction(0, context.getString(R.string.captured_undo), undo)
                    .build()
            manager.notify(item.localId.hashCode(), notification)
        }

        /** After an Undo of a record that a learned format made: "Stop learning this format?". */
        fun promptStopLearning(templateId: String) {
            if (!canPost()) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            ensureChannel(manager)
            val yes = receiverIntent(ACTION_STOP_LEARNING, null, templateId, requestCode = templateId.hashCode())
            val no = receiverIntent(ACTION_KEEP_LEARNING, null, templateId, requestCode = templateId.hashCode() + 1)
            val notification =
                NotificationCompat
                    .Builder(context, CAPTURED_CHANNEL_ID)
                    .setSmallIcon(context.applicationInfo.icon)
                    .setContentTitle(context.getString(R.string.captured_stop_learning_title))
                    .setContentText(context.getString(R.string.captured_stop_learning_text))
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setOnlyAlertOnce(true)
                    .addAction(0, context.getString(R.string.captured_yes), yes)
                    .addAction(0, context.getString(R.string.captured_no), no)
                    .build()
            manager.notify(promptId(templateId), notification)
        }

        override fun cancel(localId: String) {
            context.getSystemService(NotificationManager::class.java)?.cancel(localId.hashCode())
        }

        override fun cancelAll() {
            context.getSystemService(NotificationManager::class.java)?.cancelAll()
        }

        fun cancelPrompt(templateId: String) {
            context.getSystemService(NotificationManager::class.java)?.cancel(promptId(templateId))
        }

        private fun promptId(templateId: String) = templateId.hashCode() xor PROMPT_SALT

        private fun canPost(): Boolean = settings.enabled() && permission.granted()

        private fun publicVersion(): Notification =
            Notification
                .Builder(context, CAPTURED_CHANNEL_ID)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(context.getString(R.string.captured_public_title))
                .build()

        private fun ensureChannel(manager: NotificationManager) {
            val channel =
                NotificationChannel(
                    CAPTURED_CHANNEL_ID,
                    context.getString(R.string.captured_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.captured_channel_description)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            manager.createNotificationChannel(channel)
        }

        private fun receiverIntent(
            action: String,
            localId: String?,
            templateId: String?,
            requestCode: Int,
        ): PendingIntent {
            // explicit, not exported: only this app can send it
            val intent =
                Intent(action).setClass(context, CapturedActionReceiver::class.java).apply {
                    localId?.let { putExtra(EXTRA_LOCAL_ID, it) }
                    templateId?.let { putExtra(EXTRA_TEMPLATE_ID, it) }
                }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private companion object {
            const val PROMPT_SALT = 0x5EED
        }
    }

/**
 * Serializes Undo against the upload of the same record. [begin]/[end] bracket an upload request that may be in flight; [guard]
 * is a short critical section for the database work that follows (never held across a network call except the delete itself).
 */
@Singleton
class CapturedLocks
    @Inject
    constructor() {
        private val inFlight =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<String>()
        private val mutex = Mutex()

        fun begin(localId: String) {
            inFlight.add(localId)
        }

        fun end(localId: String) {
            inFlight.remove(localId)
        }

        fun isInFlight(localId: String) = localId in inFlight

        suspend fun <T> guard(block: suspend () -> T): T = mutex.withLock { block() }
    }

/**
 * How one Undo ended: [removed] is true when the record is gone or will be (see [deferred]); [templateId] is the learned format
 * that made it, if any. [deferred]: the upload was in flight (or the server id is not known yet), so the Undo is remembered and the
 * expense is deleted the moment its id arrives.
 */
data class UndoResult(
    val removed: Boolean,
    val templateId: String?,
    val deferred: Boolean = false,
)

/**
 * Undo of an automatically captured expense, shared by the notification action and the chip in the lists. If the record is still in
 * the upload outbox it is simply removed from it; otherwise the expense is deleted through the API (a soft delete the server can
 * restore). While an upload of the same record may be in flight the Undo is only REMEMBERED (never half-applied): the uploader
 * deletes the expense when the server answers. A failure (offline) leaves everything as it was so the user can try again.
 */
@Singleton
class CapturedActions
    @Inject
    constructor(
        private val db: AppDatabase,
        private val deleter: ExpenseDeleter,
        private val clock: Clock,
        private val locks: CapturedLocks = CapturedLocks(),
    ) {
        fun observeRecent(): Flow<List<CapturedTxEntity>> = db.captured().observeRecent(capturedCutoff(clock.millis()))

        suspend fun undo(localId: String): UndoResult =
            locks.guard {
                val item = db.captured().find(localId) ?: return@guard UndoResult(false, null)
                val queued = db.parsedOutbox().all().any { it.localId == localId }
                when {
                    locks.isInFlight(localId) -> remember(item)
                    queued -> {
                        db.withTransaction {
                            db.parsedOutbox().delete(localId)
                            db.captured().delete(localId)
                        }
                        UndoResult(true, item.templateId)
                    }
                    // uploaded, but the server has not told us which expense it made: remembered, resolved by the next upload pass
                    item.expenseId == null -> remember(item)
                    !deleter.deleteExpense(item.expenseId) -> UndoResult(false, item.templateId)
                    else -> {
                        db.withTransaction {
                            db.expenses().delete(item.expenseId)
                            db.captured().delete(localId)
                        }
                        UndoResult(true, item.templateId)
                    }
                }
            }

        private suspend fun remember(item: CapturedTxEntity): UndoResult {
            db.captured().requestUndo(item.localId)
            return UndoResult(removed = true, templateId = item.templateId, deferred = true)
        }

        /** "Stop learning this format?" answered yes. */
        suspend fun stopLearning(templateId: String) = db.learnedTemplates().deleteById(templateId)
    }

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CapturedEntryPoint {
    fun actions(): CapturedActions

    fun notifier(): AndroidCapturedNotifier
}

/** The three notification buttons: Undo, and the answer to "Stop learning this format?". Not exported. */
class CapturedActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, CapturedEntryPoint::class.java)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handle(graph, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(
        graph: CapturedEntryPoint,
        intent: Intent,
    ) {
        val localId = intent.getStringExtra(EXTRA_LOCAL_ID)
        val templateId = intent.getStringExtra(EXTRA_TEMPLATE_ID)
        when (intent.action) {
            ACTION_UNDO_CAPTURED -> if (localId != null) undo(graph, localId)
            ACTION_STOP_LEARNING -> if (templateId != null) stopLearning(graph, templateId)
            ACTION_KEEP_LEARNING -> if (templateId != null) graph.notifier().cancelPrompt(templateId)
        }
    }

    private suspend fun undo(
        graph: CapturedEntryPoint,
        localId: String,
    ) {
        val result = graph.actions().undo(localId)
        if (!result.removed) return
        graph.notifier().cancel(localId)
        result.templateId?.let { graph.notifier().promptStopLearning(it) }
    }

    private suspend fun stopLearning(
        graph: CapturedEntryPoint,
        templateId: String,
    ) {
        graph.actions().stopLearning(templateId)
        graph.notifier().cancelPrompt(templateId)
    }
}
