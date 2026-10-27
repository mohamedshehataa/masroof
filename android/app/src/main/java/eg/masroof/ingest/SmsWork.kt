package eg.masroof.ingest

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eg.masroof.core.CairoTime
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.UuidIdFactory
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CapturedTxEntity
import eg.masroof.data.db.DatabaseStartup
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.data.db.REVIEW_RETENTION_DAYS
import eg.masroof.data.db.ReviewItemEntity
import eg.masroof.data.db.capturedCutoff
import eg.masroof.data.remote.ExpenseDeleter
import eg.masroof.data.remote.NoExpenseDeleter
import eg.masroof.data.remote.ParsedRemote
import eg.masroof.data.remote.SmsUploadResult
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ingest.parse.MessageParser
import eg.masroof.ingest.parse.ParseOutcome
import eg.masroof.ingest.parse.ParsedRecord
import eg.masroof.ingest.parse.TemplateShape
import eg.masroof.ingest.parse.TxKind
import eg.masroof.work.OutboxBackoff
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

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

/** What the pipeline did with one message. [RECORDED] is also the answer for a message that was recorded before. */
enum class Disposition { RECORDED, REVIEW, DROPPED }

/** What one write transaction decided; the notices are shown after it committed, never inside it. */
private data class Stored(
    val disposition: Disposition,
    val notice: CapturedTxEntity? = null,
    val withdraw: String? = null,
    val changed: Boolean = false,
)

/**
 * Background half of the receiver and the notification listener: parses on the device and decides (REQ-SMS-030,
 * REQ-SMS-060). A record becomes a local entry in Room at once (ADR-0009 D3: `expense` + `expense_source` + `captured_tx` in one
 * transaction, nothing is queued or uploaded); anything unclear or secret goes to the local review list; the rest is forgotten.
 * The message text is never written anywhere except the review list.
 */
@Suppress("LongParameterList") // one collaborator per concern; all injected
@Singleton
class SmsPipeline
    @Inject
    constructor(
        private val db: AppDatabase,
        private val settings: SenderSettings,
        private val notifications: NotificationSettings,
        private val clock: Clock,
        private val gate: CaptureGate,
        private val idKey: LocalIdKey,
        private val notifier: CapturedNotifier = NoCapturedNotifier,
        private val ids: IdFactory = UuidIdFactory(),
        private val signal: LocalDataSignal = LocalDataSignal(),
        private val eraseLock: EraseLock = EraseLock(),
    ) {
        private val pairing = ChannelPairing(db, clock, notifier)

        suspend fun accept(message: IncomingMessage): Disposition {
            // Capture switched off (or an erase running): nothing is read, recorded or kept.
            if (!gate.isOpen() || !isAllowed(message)) return Disposition.DROPPED
            val id = LocalIdHasher.id(idKey.get(), message)
            if (!gate.isOpen()) {
                // A wipe raced us and the key may have been recreated after it: forget it again.
                idKey.wipe()
                return Disposition.DROPPED
            }
            // A message in a format the user confirmed on this phone is recorded like a proven family
            val applied = TemplateLearning.apply(db, message, MessageParser.parse(message))
            val outcome = applied.outcome
            // Waits for a running erase or recovery, like a manual save. The gate and the setting are checked once more inside the
            // transaction, right before anything is written, so an intake that was in flight when capture was switched off (or an
            // erase began) writes nothing.
            val stored =
                eraseLock.mutex.withLock {
                    db.withTransaction {
                        if (gate.isOpen() &&
                            isAllowed(message)
                        ) {
                            store(message, id, outcome, applied.templateId)
                        } else {
                            Stored(Disposition.DROPPED)
                        }
                    }
                }
            stored.withdraw?.let(notifier::cancel)
            stored.notice?.let(notifier::captured)
            if (stored.changed) signal.changed()
            return stored.disposition
        }

        private suspend fun store(
            message: IncomingMessage,
            id: String,
            outcome: ParseOutcome,
            templateId: String?,
        ): Stored =
            when (outcome) {
                is ParseOutcome.Record ->
                    storeRecord(outcome.record, id, templateId, message.receivedAtMillis.takeIf { it > 0 } ?: clock.millis())
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
                        ).withSuggestion(
                            outcome.suggestion,
                            outcome.reason.takeIf { it == "unconfirmed" }?.let { TemplateShape.of(message.text) },
                        ),
                    )
                    Stored(Disposition.REVIEW)
                }
                is ParseOutcome.Ignore -> Stored(Disposition.DROPPED)
            }

        /** An automatic record: a local entry with its source, remembered for Undo and announced (once per message). */
        private suspend fun storeRecord(
            r: ParsedRecord,
            id: String,
            templateId: String?,
            receivedAt: Long,
        ): Stored {
            val now = clock.millis()
            db.captured().purgeBefore(capturedCutoff(now))
            db.reviews().purgeBefore(now - REVIEW_RETENTION_DAYS * MILLIS_PER_DAY)
            // The same message again (a re-run job, a double delivery) is the same record: no second entry, row or notice. The source
            // row outlives an Undo, and a paired twin has its own source row, so this also holds for an undone entry (30 days).
            if (db.sources().countByLocalSourceId(id) > 0 || db.captured().knows(id) > 0) return Stored(Disposition.RECORDED)
            // the same transfer announced by the other channel (bank SMS + app notification): one entry, two sources
            val twin = pairing.claimTwin(r, id, receivedAt)
            if (twin != null) return addToTwin(twin, r, id, templateId, receivedAt, now)
            val expenseId = ids.next()
            db.expenses().insert(newExpense(expenseId, r, receivedAt, now))
            db.sources().insert(source(expenseId, r, id, receivedAt))
            // the safety net: a record the phone made by itself can be undone for a while
            val item = captured(id, expenseId, r, templateId, receivedAt, now, twinId = null)
            db.captured().insert(item)
            return Stored(Disposition.RECORDED, notice = item, changed = true)
        }

        /**
         * The other channel already made the entry: this message only adds its source. When it knows more (the account tail) and
         * nobody edited the entry, the entry takes that and the first notice is replaced by one notice; otherwise the old notice
         * stays. An entry that was undone or deleted gets the source and nothing else.
         */
        private suspend fun addToTwin(
            twin: LocalTwin,
            r: ParsedRecord,
            id: String,
            templateId: String?,
            receivedAt: Long,
            now: Long,
        ): Stored {
            val expenseId = twin.expense.id
            db.sources().insert(source(expenseId, r, id, receivedAt))
            val richer = twin.captured.instrumentLast4 == null && r.instrumentLast4 != null
            val replace =
                richer && !twin.captured.undoRequested && db.expenses().enrichFromTwin(expenseId, r.instrumentLast4, label(r)) == 1
            if (!replace) return Stored(Disposition.RECORDED, changed = true)
            db.captured().delete(twin.captured.localId)
            val item = captured(id, expenseId, r, templateId, receivedAt, now, twinId = twin.captured.localId)
            db.captured().insert(item)
            return Stored(Disposition.RECORDED, notice = item, withdraw = twin.captured.localId, changed = true)
        }

        private fun newExpense(
            expenseId: String,
            r: ParsedRecord,
            receivedAt: Long,
            now: Long,
        ) = ExpenseEntity(
            id = expenseId,
            amountMinor = r.amountMinor,
            currency = r.currency,
            occurredAtMillis = r.occurredAtMillis,
            merchant = label(r),
            categoryId = null,
            accountId = null,
            notes = null,
            pending = false,
            source = channel(r),
            type = typeOf(r.kind),
            status = if (needsReview(r)) STATUS_PENDING_REVIEW else STATUS_CONFIRMED,
            createdAtMillis = now,
            updatedAtMillis = now,
            timeKnown = timeKnown(r.occurredAtMillis, receivedAt),
            paymentMethod = r.paymentMethod,
            instrumentLast4 = r.instrumentLast4,
        )

        private fun source(
            expenseId: String,
            r: ParsedRecord,
            localId: String,
            receivedAt: Long,
        ) = ExpenseSourceEntity(
            id = ids.next(),
            expenseId = expenseId,
            channel = channel(r),
            localSourceId = localId,
            receivedAtMillis = receivedAt,
            senderId = r.senderId,
            instrumentLast4 = r.instrumentLast4,
        )

        @Suppress("LongParameterList") // typed fields of one row
        private fun captured(
            id: String,
            expenseId: String,
            r: ParsedRecord,
            templateId: String?,
            receivedAt: Long,
            now: Long,
            twinId: String?,
        ) = CapturedTxEntity(
            localId = id,
            expenseId = expenseId,
            amountMinor = r.amountMinor,
            currency = r.currency,
            label = noticeLabel(r),
            source = r.source.name,
            templateId = templateId,
            capturedAtMillis = now,
            kind = r.kind.name,
            receivedAtMillis = receivedAt,
            paired = twinId != null,
            pairedLocalId = twinId,
            instrumentLast4 = r.instrumentLast4,
        )

        /** The name an entry shows: the merchant or counterparty label, else the institution (typed, digit-free values only). */
        private fun label(r: ParsedRecord): String? = r.merchant ?: r.institution

        private fun channel(r: ParsedRecord) = if (r.source == CaptureSource.SMS) CHANNEL_SMS else CHANNEL_PUSH

        /** The server's old mapping: money in is INCOME, money out to a person is a TRANSFER, the rest by what it is. */
        private fun typeOf(kind: TxKind): String =
            when (kind) {
                TxKind.PURCHASE, TxKind.FEE -> "EXPENSE"
                TxKind.TRANSFER_OUT -> "TRANSFER"
                TxKind.TRANSFER_IN, TxKind.INCOME -> "INCOME"
                TxKind.REFUND -> "REFUND"
                TxKind.CASH_WITHDRAWAL -> "CASH_WITHDRAWAL"
            }

        /** Incoming money (a spoofed sender id could claim it) and unusually large amounts wait for the user. */
        private fun needsReview(r: ParsedRecord) =
            r.kind in setOf(TxKind.INCOME, TxKind.TRANSFER_IN, TxKind.REFUND) || r.amountMinor > REVIEW_CEILING_MINOR

        /** A date-only message is stored at Cairo noon of that day (as a manual entry for another day): its time is not known. */
        private fun timeKnown(
            occurredAt: Long,
            receivedAt: Long,
        ): Boolean =
            occurredAt == receivedAt ||
                Instant.ofEpochMilli(occurredAt).atZone(CairoTime.zone).toLocalTime() != LocalTime.NOON

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

        /** The notification names a merchant, but never a person: a transfer shows the institution only. */
        private fun noticeLabel(r: ParsedRecord): String? =
            when (r.kind) {
                TxKind.PURCHASE, TxKind.CASH_WITHDRAWAL, TxKind.FEE -> r.merchant
                else -> r.institution
            }

        private fun isAllowed(message: IncomingMessage): Boolean =
            when (message.source) {
                CaptureSource.SMS -> settings.smsCaptureEnabled() && settings.policy().isAllowed(message.sender)
                CaptureSource.NOTIFICATION -> notifications.enabled() && notifications.isMonitored(message.sender)
            }

        private companion object {
            const val NOT_INSERTED = -1L
            const val MAX_SENDER = 60
            const val MAX_REVIEW_TEXT = 1000
            const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
            const val REVIEW_CEILING_MINOR = 5_000_000L
            const val STATUS_PENDING_REVIEW = "PENDING_REVIEW"
            const val STATUS_CONFIRMED = "CONFIRMED"
            const val CHANNEL_SMS = "SMS"
            const val CHANNEL_PUSH = "PUSH"
        }
    }

/** Result of one upload pass over the outbox. */
data class SmsSyncReport(
    val sent: Int,
    val rejected: Int,
    val retry: Boolean,
    val retryAfterSeconds: Long? = null,
)

/** Whether a server session exists. Without one the capture uploader sends nothing (ADR-0009: no anonymous or stale uploads). */
interface UploadSession {
    suspend fun hasSession(): Boolean
}

object AlwaysSignedIn : UploadSession {
    override suspend fun hasSession() = true
}

@Singleton
class SessionUploadCheck
    @Inject
    constructor(
        private val session: eg.masroof.data.auth.SessionManager,
    ) : UploadSession {
        override suspend fun hasSession() = session.memberId() != null
    }

/** Uploads queued records one by one, in order. A row is deleted as soon as the server acknowledged it (REQ-SMS-061). */
@Suppress("LongParameterList") // one collaborator per concern; all injected
@Singleton
class SmsUploader
    @Inject
    constructor(
        private val db: AppDatabase,
        private val remote: ParsedRemote,
        private val device: DeviceIdProvider,
        private val clock: Clock,
        private val gate: CaptureGate,
        private val deleter: ExpenseDeleter = NoExpenseDeleter,
        private val locks: CapturedLocks = CapturedLocks(),
        private val notifier: CapturedNotifier = NoCapturedNotifier,
        private val refresher: UploadedRecordsRefresher = NoUploadedRecordsRefresher,
        private val upload: UploadSession = AlwaysSignedIn,
    ) {
        private val pairing = ChannelPairing(db, clock, notifier)

        suspend fun uploadPending(): SmsSyncReport {
            val dao = db.parsedOutbox()
            // Retention is enforced here too, so an old review item goes even if no new message ever arrives.
            db.reviews().purgeBefore(clock.millis() - REVIEW_RETENTION_DAYS * MILLIS_PER_DAY)
            db.captured().purgeBefore(capturedCutoff(clock.millis()))
            dao.purgeFailedBefore(clock.millis() - RETENTION_MILLIS)
            // Signed out (or between two accounts): rows are never sent; logout cleared them, this is the belt.
            if (!gate.isOpen()) return SmsSyncReport(0, 0, false)
            // No session: nothing is sent and no Undo is replayed against the server. Rows stay queued (STORY-171 makes them local).
            if (!upload.hasSession()) return SmsSyncReport(0, 0, false)
            var sent = 0
            var rejected = 0
            var retry = false
            var retryAfter: Long? = null
            resolveUndoRequests()
            // records queued by an older version of the app, never compared with each other, are paired before anything is sent
            pairing.pairQueued()
            var stopped = false
            var wait = 0L
            for (item in dao.pending()) {
                if (retry || stopped) break
                val step = uploadOne(item)
                sent += step.sent
                rejected += step.rejected
                retry = step.retry
                retryAfter = step.retryAfter
                stopped = step.needsSignIn
                wait = maxOf(wait, step.waitSeconds)
            }
            // one refresh per pass, however many records went out
            if (sent > 0) {
                // a failing refresh must never fail the pass: the records are sent and the retry below is still needed
                try {
                    refresher.refreshAfterUpload()
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") _: Exception,
                ) {
                    // the next normal sync fetches the rows
                }
            }
            if (stopped) return SmsSyncReport(sent, rejected, false)
            if (wait > 0) {
                // a row is held by another upload (maybe a dead one): come back when its claim runs out
                retry = true
                retryAfter = maxOf(retryAfter ?: 0L, wait)
            }
            return SmsSyncReport(sent, rejected, retry, retryAfter)
        }

        /** What one row's upload changed. An unclaimed row (another upload holds it, or it is gone) changes nothing. */
        private data class Step(
            val sent: Int = 0,
            val rejected: Int = 0,
            val retry: Boolean = false,
            val retryAfter: Long? = null,
            val needsSignIn: Boolean = false,
            val waitSeconds: Long = 0L,
        )

        private suspend fun uploadOne(item: ParsedOutboxEntity): Step {
            val dao = db.parsedOutbox()
            // One statement takes the row: a second uploader skips it, and a replacement by the other channel cannot delete it.
            val token = clock.millis()
            if (dao.claim(item.localId, token, token - UPLOAD_CLAIM_TTL_MILLIS) == 0) return heldStep(item.localId, token)
            locks.begin(item.localId)
            try {
                return when (val result = remote.uploadParsed(device.get(), item)) {
                    is SmsUploadResult.Sent -> {
                        dao.delete(item.localId)
                        recordSent(item.localId, result.expenseId)
                        Step(sent = 1)
                    }
                    is SmsUploadResult.Retry -> {
                        dao.bumpAttempts(item.localId)
                        Step(retry = true, retryAfter = result.afterSeconds)
                    }
                    SmsUploadResult.RetryLimited ->
                        if (item.attempts + 1 >= MAX_LIMITED_ATTEMPTS) {
                            moveToReview(item)
                            Step(rejected = 1)
                        } else {
                            dao.bumpAttempts(item.localId)
                            Step(retry = true)
                        }
                    // Needs a new sign-in; the rows stay queued and the next sign-in/app start uploads them.
                    SmsUploadResult.Unauthorized -> Step(needsSignIn = true)
                    SmsUploadResult.Rejected -> {
                        moveToReview(item)
                        Step(rejected = 1)
                    }
                }
            } finally {
                locks.end(item.localId)
                // the claim is held until the outcome is stored, so a replacement never sees a half-finished upload
                dao.release(item.localId, token)
            }
        }

        /** The row was not taken: gone or finished (nothing to do), or held by a live claim (come back when it runs out). */
        private suspend fun heldStep(
            localId: String,
            now: Long,
        ): Step {
            val held = db.parsedOutbox().find(localId)?.takeIf { !it.failed && it.claimedAtMillis > 0 } ?: return Step()
            val remainingMillis = held.claimedAtMillis + UPLOAD_CLAIM_TTL_MILLIS - now
            return if (remainingMillis > 0) Step(waitSeconds = remainingMillis / MILLIS_PER_SECOND + 1) else Step()
        }

        /**
         * The server accepted [localId]: remember the expense it made (what Undo deletes). If the user pressed Undo while the request
         * was in flight, the expense is deleted right now; without an id the request stays remembered for a later pass.
         */
        private suspend fun recordSent(
            localId: String,
            expenseId: String?,
        ) {
            locks.guard {
                val captured = db.captured().find(localId) ?: return@guard
                expenseId?.let { db.captured().setExpenseId(localId, it) }
                val id = expenseId ?: captured.expenseId
                if (captured.undoRequested && id != null) deleteRequested(localId, id)
            }
        }

        /** Undo requests that could not be finished before (no id yet, or offline): retried at the start of every pass. */
        private suspend fun resolveUndoRequests() {
            locks.guard {
                for (c in db.captured().undoRequests()) {
                    val id = c.expenseId
                    if (id != null && !locks.isInFlight(c.localId)) deleteRequested(c.localId, id)
                }
            }
        }

        private suspend fun deleteRequested(
            localId: String,
            expenseId: String,
        ) {
            if (!deleter.deleteExpense(expenseId)) return
            db.withTransaction {
                db.expenses().delete(expenseId)
                db.captured().delete(localId)
            }
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
            // it never became an expense: nothing to undo
            db.captured().delete(item.localId)
        }

        private companion object {
            const val MAX_LIMITED_ATTEMPTS = 5
            const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
            const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
            const val MILLIS_PER_SECOND = 1000L
            const val CENTS = 100
        }
    }

/** After this many tries a message the database keeps refusing is given up on (its text leaves the WorkManager input with the job). */
internal const val MAX_INTAKE_ATTEMPTS = 10

/**
 * Turns the outcome of one intake into the job result: a database that cannot write (full disk, locked) wrote nothing, so the job
 * is tried again later with its input intact; any other failure is final. Nothing here touches the network.
 */
internal suspend fun intakeResult(
    attempt: Int,
    accept: suspend () -> Disposition,
): ListenableWorker.Result =
    try {
        accept()
        ListenableWorker.Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("SwallowedException") _: SQLiteException,
    ) {
        if (attempt < MAX_INTAKE_ATTEMPTS) ListenableWorker.Result.retry() else ListenableWorker.Result.failure()
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
            // Blocked start: keep the captured message in the job (not lost) and try again later.
            if (DatabaseStartup.isBlocked()) return Result.retry()
            val sender = inputData.getString(KEY_SENDER) ?: return Result.failure()
            val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
            val source = inputData.getString(KEY_SOURCE)?.let { runCatching { CaptureSource.valueOf(it) }.getOrNull() } ?: CaptureSource.SMS
            // The entry is in Room when this returns: nothing is queued, scheduled or sent (ADR-0009 D3/D7).
            return intakeResult(runAttemptCount) { pipeline.accept(IncomingMessage(source, sender, text, inputData.getLong(KEY_TS, 0L))) }
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
            if (DatabaseStartup.isBlocked()) return Result.retry()
            val report = uploader.uploadPending()
            if (report.retry) {
                val attempt = inputData.getInt(KEY_ATTEMPT, 0)
                scheduler.scheduleUploadRetry(OutboxBackoff.delaySeconds(attempt, report.retryAfterSeconds), attempt + 1)
            }
            return Result.success()
        }
    }
