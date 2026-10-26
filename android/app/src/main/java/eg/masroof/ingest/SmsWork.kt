package eg.masroof.ingest

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.data.db.REVIEW_RETENTION_DAYS
import eg.masroof.data.db.ReviewItemEntity
import eg.masroof.data.remote.ParsedRemote
import eg.masroof.data.remote.SmsUploadResult
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ingest.parse.MessageParser
import eg.masroof.ingest.parse.ParseOutcome
import eg.masroof.ingest.parse.ParsedRecord
import eg.masroof.ingest.parse.TemplateShape
import eg.masroof.work.OutboxBackoff
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

const val SMS_INTAKE_TAG = "sms-intake"
const val SMS_UPLOAD_WORK = "sms-upload"
const val SMS_UPLOAD_RETRY_WORK = "sms-upload-retry"
private const val KEY_SOURCE = "source"
private const val KEY_SENDER = "sender"
private const val KEY_TEXT = "text"
private const val KEY_TS = "ts"
private const val KEY_ATTEMPT = "attempt"

/** Where the app keeps a random id for this installation, sent with every record (idempotency key part). */
interface DeviceIdProvider {
    fun get(): String
}

@Singleton
class WorkManagerSmsScheduler
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : SmsWorkScheduler {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        override fun enqueueIntake(message: IncomingMessage) {
            val request =
                OneTimeWorkRequestBuilder<SmsIntakeWorker>()
                    .addTag(SMS_INTAKE_TAG)
                    .setInputData(
                        workDataOf(
                            KEY_SOURCE to message.source.name,
                            KEY_SENDER to message.sender,
                            KEY_TEXT to message.text,
                            KEY_TS to message.receivedAtMillis,
                        ),
                    ).build()
            // Not unique by name: every message is its own job; the outbox insert dedups on the local id.
            workManager.enqueue(request)
        }

        override fun scheduleUpload() {
            val request = OneTimeWorkRequestBuilder<SmsUploadWorker>().setConstraints(network).build()
            workManager.enqueueUniqueWork(SMS_UPLOAD_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        override fun scheduleUploadRetry(
            delaySeconds: Long,
            attempt: Int,
        ) {
            val request =
                OneTimeWorkRequestBuilder<SmsUploadWorker>()
                    .setConstraints(network)
                    .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                    .setInputData(Data.Builder().putInt(KEY_ATTEMPT, attempt).build())
                    .build()
            workManager.enqueueUniqueWork(SMS_UPLOAD_RETRY_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }

/** What the pipeline did with one message. */
enum class Disposition { QUEUED, REVIEW, DROPPED }

/**
 * Background half of the receiver and the notification listener: parses on the device and decides (REQ-SMS-030,
 * REQ-SMS-060). A record goes to the upload outbox; anything unclear or secret goes to the local review list; the rest is
 * forgotten. The message text is never written anywhere except the review list.
 */
@Singleton
class SmsPipeline
    @Inject
    constructor(
        private val db: AppDatabase,
        private val settings: SenderSettings,
        private val notifications: NotificationSettings,
        private val scheduler: SmsWorkScheduler,
        private val clock: Clock,
        private val gate: CaptureGate,
        private val idKey: LocalIdKey,
    ) {
        suspend fun accept(message: IncomingMessage): Disposition {
            // No session (logged out, between two accounts): nothing is read, queued or kept.
            if (!gate.isOpen() || !isAllowed(message)) return Disposition.DROPPED
            val id = LocalIdHasher.id(idKey.get(), message)
            if (!gate.isOpen()) {
                // A wipe raced us and the key may have been recreated after it: forget it again.
                idKey.wipe()
                return Disposition.DROPPED
            }
            // A message in a format the user confirmed on this phone is recorded like a proven family
            val outcome = TemplateLearning.apply(db, message, MessageParser.parse(message))
            // The gate is checked once more inside the transaction, right before anything is written, so an intake
            // that was in flight during logout cannot insert after the wipe.
            return db.withTransaction { if (gate.isOpen()) store(message, id, outcome) else Disposition.DROPPED }
        }

        private suspend fun store(
            message: IncomingMessage,
            id: String,
            outcome: ParseOutcome,
        ): Disposition =
            when (outcome) {
                is ParseOutcome.Record -> {
                    val r = outcome.record
                    db.parsedOutbox().insert(
                        ParsedOutboxEntity(
                            localId = id,
                            source = r.source.name,
                            kind = r.kind.name,
                            amountMinor = r.amountMinor,
                            currency = r.currency,
                            occurredAtMillis = r.occurredAtMillis,
                            instrumentLast4 = r.instrumentLast4,
                            senderId = r.senderId,
                            institution = r.institution,
                            paymentMethod = r.paymentMethod,
                            merchant = r.merchant,
                            createdAtMillis = clock.millis(),
                        ),
                    )
                    scheduler.scheduleUpload()
                    Disposition.QUEUED
                }
                is ParseOutcome.Review -> {
                    val now = clock.millis()
                    db.reviews().purgeBefore(now - REVIEW_RETENTION_DAYS * MILLIS_PER_DAY)
                    db.reviews().insert(
                        ReviewItemEntity(
                            id = id,
                            source = message.source.name,
                            sender = message.sender.take(MAX_SENDER),
                            text = outcome.text.take(MAX_REVIEW_TEXT),
                            reason = outcome.reason,
                            receivedAtMillis = message.receivedAtMillis,
                            createdAtMillis = now,
                        ).withSuggestion(outcome.suggestion, TemplateShape.of(message.text)),
                    )
                    Disposition.REVIEW
                }
                is ParseOutcome.Ignore -> Disposition.DROPPED
            }

        private fun ReviewItemEntity.withSuggestion(
            suggestion: ParsedRecord?,
            shape: TemplateShape.Shape?,
        ): ReviewItemEntity =
            copy(
                sugKind = suggestion?.kind?.name,
                sugAmountMinor = suggestion?.amountMinor,
                sugCurrency = suggestion?.currency,
                sugOccurredAtMillis = suggestion?.occurredAtMillis,
                sugLast4 = suggestion?.instrumentLast4,
                sugSenderId = suggestion?.senderId,
                sugInstitution = suggestion?.institution,
                sugPaymentMethod = suggestion?.paymentMethod,
                sugMerchant = suggestion?.merchant,
                shape = shape?.signature,
                slots = shape?.let { TemplateLearning.encodeSlots(it.numbers) },
            )

        private fun isAllowed(message: IncomingMessage): Boolean =
            when (message.source) {
                CaptureSource.SMS -> settings.policy().isAllowed(message.sender)
                CaptureSource.NOTIFICATION -> notifications.enabled() && notifications.isMonitored(message.sender)
            }

        private companion object {
            const val MAX_SENDER = 60
            const val MAX_REVIEW_TEXT = 1000
            const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        }
    }

/** Result of one upload pass over the outbox. */
data class SmsSyncReport(
    val sent: Int,
    val rejected: Int,
    val retry: Boolean,
    val retryAfterSeconds: Long? = null,
)

/** Uploads queued records one by one, in order. A row is deleted as soon as the server acknowledged it (REQ-SMS-061). */
@Singleton
class SmsUploader
    @Inject
    constructor(
        private val db: AppDatabase,
        private val remote: ParsedRemote,
        private val device: DeviceIdProvider,
        private val clock: Clock,
        private val gate: CaptureGate,
    ) {
        suspend fun uploadPending(): SmsSyncReport {
            val dao = db.parsedOutbox()
            // Retention is enforced here too, so an old review item goes even if no new message ever arrives.
            db.reviews().purgeBefore(clock.millis() - REVIEW_RETENTION_DAYS * MILLIS_PER_DAY)
            dao.purgeFailedBefore(clock.millis() - RETENTION_MILLIS)
            // Signed out (or between two accounts): rows are never sent; logout cleared them, this is the belt.
            if (!gate.isOpen()) return SmsSyncReport(0, 0, false)
            var sent = 0
            var rejected = 0
            var retry = false
            var retryAfter: Long? = null
            for (item in dao.pending()) {
                when (val result = remote.uploadParsed(device.get(), item)) {
                    SmsUploadResult.Sent -> {
                        dao.delete(item.localId)
                        sent++
                    }
                    is SmsUploadResult.Retry -> {
                        dao.bumpAttempts(item.localId)
                        retry = true
                        retryAfter = result.afterSeconds
                    }
                    SmsUploadResult.RetryLimited ->
                        if (item.attempts + 1 >= MAX_LIMITED_ATTEMPTS) {
                            moveToReview(item)
                            rejected++
                        } else {
                            dao.bumpAttempts(item.localId)
                            retry = true
                        }
                    // Needs a new sign-in; the rows stay queued and the next sign-in/app start uploads them.
                    SmsUploadResult.Unauthorized -> return SmsSyncReport(sent, rejected, false)
                    SmsUploadResult.Rejected -> {
                        moveToReview(item)
                        rejected++
                    }
                }
                if (retry) break
            }
            return SmsSyncReport(sent, rejected, retry, retryAfter)
        }

        /**
         * A record the server refuses for good is never silently lost: it moves to the local review list, described from
         * its typed fields only (the original text is not kept), where the user can create the expense by hand.
         */
        private suspend fun moveToReview(item: ParsedOutboxEntity) {
            val summary =
                buildString {
                    append(item.kind).append(' ').append("%d.%02d".format(item.amountMinor / CENTS, item.amountMinor % CENTS))
                    append(' ').append(item.currency)
                    item.merchant?.let { append(" - ").append(it) }
                    item.instrumentLast4?.let { append(" - **").append(it) }
                }
            db.reviews().insert(
                ReviewItemEntity(
                    id = item.localId,
                    source = item.source,
                    sender = item.senderId,
                    text = summary,
                    reason = "rejected",
                    receivedAtMillis = item.occurredAtMillis,
                    createdAtMillis = clock.millis(),
                ),
            )
            db.parsedOutbox().delete(item.localId)
        }

        private companion object {
            const val MAX_LIMITED_ATTEMPTS = 5
            const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
            const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
            const val CENTS = 100
        }
    }

@HiltWorker
class SmsIntakeWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val pipeline: SmsPipeline,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val sender = inputData.getString(KEY_SENDER) ?: return Result.failure()
            val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
            val source = inputData.getString(KEY_SOURCE)?.let { runCatching { CaptureSource.valueOf(it) }.getOrNull() } ?: CaptureSource.SMS
            pipeline.accept(IncomingMessage(source, sender, text, inputData.getLong(KEY_TS, 0L)))
            return Result.success()
        }
    }

@HiltWorker
class SmsUploadWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val uploader: SmsUploader,
        private val scheduler: SmsWorkScheduler,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val report = uploader.uploadPending()
            if (report.retry) {
                val attempt = inputData.getInt(KEY_ATTEMPT, 0)
                scheduler.scheduleUploadRetry(OutboxBackoff.delaySeconds(attempt, report.retryAfterSeconds), attempt + 1)
            }
            return Result.success()
        }
    }
