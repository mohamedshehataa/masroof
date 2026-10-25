package eg.masroof.ingest

import android.content.Context
import androidx.hilt.work.HiltWorker
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
import eg.masroof.data.db.SmsOutboxEntity
import eg.masroof.data.remote.SmsRemote
import eg.masroof.data.remote.SmsUploadResult
import eg.masroof.work.OutboxBackoff
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

const val SMS_INTAKE_TAG = "sms-intake"
const val SMS_UPLOAD_WORK = "sms-upload"
const val SMS_UPLOAD_RETRY_WORK = "sms-upload-retry"
private const val KEY_SENDER = "sender"
private const val KEY_BODY = "body"
private const val KEY_TS = "ts"
private const val KEY_ATTEMPT = "attempt"

/** Where the app keeps a random id for this installation, sent with every SMS (idempotency key part). */
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

        override fun enqueueIntake(sms: IncomingSms) {
            val request =
                OneTimeWorkRequestBuilder<SmsIntakeWorker>()
                    .addTag(SMS_INTAKE_TAG)
                    .setInputData(workDataOf(KEY_SENDER to sms.sender, KEY_BODY to sms.body, KEY_TS to sms.timestampMillis))
                    .build()
            // Not unique by name: every message is its own job; the outbox insert dedups on the message id.
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

/** Background half of the receiver: filters again and queues the message for upload (REQ-SMS-030, REQ-SMS-060). */
@Singleton
class SmsPipeline
    @Inject
    constructor(
        private val db: AppDatabase,
        private val settings: SenderSettings,
        private val scheduler: SmsWorkScheduler,
        private val clock: Clock,
    ) {
        /** True when the message was queued; OTPs, promos and senders that are no longer allowlisted are dropped. */
        suspend fun accept(sms: IncomingSms): Boolean {
            if (!settings.policy().isAllowed(sms.sender)) return false
            if (OtpPromoFilter.classify(sms.body) != MessageClass.OTHER) return false
            val inserted =
                db.smsOutbox().insert(
                    SmsOutboxEntity(
                        smsId = smsId(sms),
                        sender = sms.sender.take(MAX_SENDER),
                        body = sms.body.take(MAX_BODY),
                        receivedAtMillis = sms.timestampMillis,
                        createdAtMillis = clock.millis(),
                    ),
                )
            scheduler.scheduleUpload()
            return inserted != -1L
        }

        private companion object {
            const val MAX_SENDER = 40
            const val MAX_BODY = 2000
        }
    }

/** Result of one upload pass over the SMS outbox. */
data class SmsSyncReport(
    val sent: Int,
    val rejected: Int,
    val retry: Boolean,
    val retryAfterSeconds: Long? = null,
)

/** Uploads queued SMS one by one, in order. The body is deleted as soon as the server acknowledged it (REQ-SMS-061). */
@Singleton
class SmsUploader
    @Inject
    constructor(
        private val db: AppDatabase,
        private val remote: SmsRemote,
        private val device: DeviceIdProvider,
        private val clock: Clock,
    ) {
        suspend fun uploadPending(): SmsSyncReport {
            val dao = db.smsOutbox()
            dao.purgeFailedBefore(clock.millis() - RETENTION_MILLIS)
            var sent = 0
            var rejected = 0
            var retry = false
            var retryAfter: Long? = null
            for (item in dao.pending()) {
                when (val result = remote.uploadSms(device.get(), item)) {
                    SmsUploadResult.Sent -> {
                        dao.delete(item.smsId)
                        sent++
                    }
                    is SmsUploadResult.Retry -> {
                        dao.bumpAttempts(item.smsId)
                        retry = true
                        retryAfter = result.afterSeconds
                    }
                    SmsUploadResult.RetryLimited ->
                        if (item.attempts + 1 >= MAX_LIMITED_ATTEMPTS) {
                            dao.markFailed(item.smsId)
                            rejected++
                        } else {
                            dao.bumpAttempts(item.smsId)
                            retry = true
                        }
                    // Needs a new sign-in; the rows stay queued and the next sign-in/app start uploads them.
                    SmsUploadResult.Unauthorized -> return SmsSyncReport(sent, rejected, false)
                    SmsUploadResult.Rejected -> {
                        dao.markFailed(item.smsId)
                        rejected++
                    }
                }
                if (retry) break
            }
            return SmsSyncReport(sent, rejected, retry, retryAfter)
        }

        private companion object {
            const val MAX_LIMITED_ATTEMPTS = 5
            const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
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
            val body = inputData.getString(KEY_BODY) ?: return Result.failure()
            pipeline.accept(IncomingSms(sender, body, inputData.getLong(KEY_TS, 0L)))
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
