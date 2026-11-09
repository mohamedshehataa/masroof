package eg.masroof.ingest.backfill

import eg.masroof.core.CairoTime
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.capturedCutoff
import eg.masroof.ingest.CaptureChannel
import eg.masroof.ingest.CaptureGate
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.LocalIdHasher
import eg.masroof.ingest.LocalIdKey
import eg.masroof.ingest.MessageHygiene
import eg.masroof.ingest.SAME_SMS_SLACK_MILLIS
import eg.masroof.ingest.SenderSettings
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.capped
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How a run of the engine ended. A kill or a system stop ends it with no outcome at all: the state stays RUNNING and it resumes.
 * [DEFERRED]: a restore is unfinished; nothing was read past the saved position, the run stays RUNNING and is tried again later.
 */
enum class BackfillOutcome { DONE, STOPPED, PERMISSION_LOST, DEFERRED }

/** Thrown inside a run when the pipeline defers a message (restore pending): the cursor is not moved, the run ends. */
private class RestoreDeferred : RuntimeException()

/** One summary at the end of a run (counts only), never one notice per message. */
interface BackfillNotifier {
    suspend fun finished(state: BackfillState)
}

object NoBackfillNotifier : BackfillNotifier {
    override suspend fun finished(state: BackfillState) = Unit
}

/** Messages read per page; Cancel and the saved resume point are honoured per message, so a stop is always within one batch. */
internal const val BACKFILL_BATCH = 200

/** How long a handled message is remembered, so a deleted entry is not recorded again (the same as the review list's retention). */
internal const val BACKFILL_MEMORY_DAYS = 30L

/**
 * "Read past SMS" (REQ-SMS-101..103): reads the allowlisted senders of the chosen range from the inbox, oldest first, in pages, and
 * gives every message to the SAME [SmsPipeline] as live capture (channel BACKFILL). It adds nothing of its own to what a message
 * becomes. The position and counts are saved after every message, so a cancelled, stopped or killed run continues where it stopped.
 * Only the rows of allowlisted senders are ever read with their text, and the text lives only in the page being handled; it is never
 * logged. Every write and state save is refused once an "Erase all data" happened after the run started.
 */
@Suppress("LongParameterList") // one collaborator per concern; all injected
@Singleton
class SmsBackfill
    @Inject
    constructor(
        private val db: AppDatabase,
        private val pipeline: SmsPipeline,
        private val inbox: SmsInbox,
        private val store: BackfillStore,
        private val settings: SenderSettings,
        private val idKey: LocalIdKey,
        private val gate: CaptureGate,
        private val clock: Clock,
        private val notifier: BackfillNotifier = NoBackfillNotifier,
        private val eraseLock: EraseLock = EraseLock(),
        private val restoreGate: eg.masroof.data.backup.RestoreGate = eg.masroof.data.backup.OpenRestoreGate,
    ) {
        suspend fun run(): BackfillOutcome {
            val start = store.state.value
            // not started, or cancelled before the worker got going
            if (start.phase != BackfillPhase.RUNNING) return BackfillOutcome.STOPPED
            // A restore is running or unfinished: the run does not start and keeps its saved position (the worker retries later).
            if (restoreGate.blocked()) return BackfillOutcome.DEFERRED
            val epoch = eraseLock.epoch
            return try {
                readAll(start, epoch)
            } catch (_: RestoreDeferred) {
                BackfillOutcome.DEFERRED
            } catch (
                @Suppress("SwallowedException") _: SecurityException,
            ) {
                // READ_SMS was taken away (or never given): what was stored stays, the screen shows the permission message
                val kept = store.state.value
                if (commit(epoch) {
                        store.save(kept.copy(phase = BackfillPhase.PERMISSION_LOST))
                    }
                ) {
                    BackfillOutcome.PERMISSION_LOST
                } else {
                    BackfillOutcome.STOPPED
                }
            }
            // A cancellation is not caught: the user's Cancel already marked the run STOPPED (BackfillController), and a stop by the
            // system (time limit, constraint) leaves it RUNNING so WorkManager's retry continues it.
        }

        private suspend fun readAll(
            start: BackfillState,
            epoch: Long,
        ): BackfillOutcome {
            store.forgetBefore(clock.millis() - BACKFILL_MEMORY_DAYS * MILLIS_PER_DAY)
            // expired Undo rows must not make an old message look "known"
            db.captured().purgeBefore(capturedCutoff(clock.millis()))
            // the senders in the range (addresses only, no text) that the allowlist accepts: the only ones a message is read from
            val allowed = mutableMapOf<String, Int>()
            val policy = settings.policy()
            inbox.forEachAddress(start.fromMillis, start.toMillis) { if (policy.isAllowed(it)) allowed.merge(it, 1, Int::plus) }
            var s = start
            if (start.total < 0) {
                s = start.copy(total = allowed.values.sum())
                if (!commit(epoch) { store.save(s) }) return BackfillOutcome.STOPPED
            }
            val addresses = allowed.keys
            while (addresses.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val page = inbox.page(s.fromMillis, s.toMillis, addresses, s.position, BACKFILL_BATCH)
                if (page.isEmpty()) break
                s = handlePage(page, s, epoch) ?: return BackfillOutcome.STOPPED
            }
            val done = s.copy(phase = BackfillPhase.DONE)
            // the summary is posted in the same locked step as the save: never after an erase
            val saved =
                commit(epoch) {
                    store.save(done)
                    notifier.finished(done)
                }
            return if (saved) BackfillOutcome.DONE else BackfillOutcome.STOPPED
        }

        /** Handles one page, saving after every message; null when the run must end (Cancel, an erase). */
        private suspend fun handlePage(
            page: List<InboxSms>,
            from: BackfillState,
            epoch: Long,
        ): BackfillState? {
            var s = from
            for (sms in page) {
                currentCoroutineContext().ensureActive()
                if (!gate.isOpen()) {
                    commit(epoch) { store.save(s.copy(phase = BackfillPhase.STOPPED)) }
                    return null
                }
                val next = handle(sms, s, epoch)
                // refused after an erase, and after the user's Cancel (the controller marked the run STOPPED)
                if (!commit(epoch) { store.save(next) }) return null
                s = next
            }
            return s
        }

        /** Runs [block] under the erase lock only while no erase happened since the run began and the user did not cancel. */
        private suspend fun commit(
            epoch: Long,
            block: suspend () -> Unit,
        ): Boolean =
            eraseLock.mutex.withLock {
                if (eraseLock.epoch != epoch || store.state.value.phase != BackfillPhase.RUNNING) {
                    false
                } else {
                    block()
                    true
                }
            }

        private suspend fun handle(
            sms: InboxSms,
            s: BackfillState,
            epoch: Long,
        ): BackfillState {
            val moved = s.copy(lastDateMillis = sms.dateMillis, lastId = sms.id)
            // the rows asked for are of allowed senders only; a row that is not (a policy changed meanwhile) is skipped unread
            if (!settings.policy().isAllowed(sms.address)) return moved
            // the file keeps only the day of the last message, so count how many of that day were read
            val sameDay = s.lastDateMillis >= 0 && CairoTime.dateOf(s.lastDateMillis) == CairoTime.dateOf(sms.dateMillis)
            val read = moved.copy(read = s.read + 1, dayRead = if (sameDay) s.dayRead + 1 else 1)
            // the same cheap drop as the live receiver: a plain OTP never reaches the pipeline
            if (MessageHygiene.isPlainSecret(sms.body)) return read
            val message = IncomingMessage(CaptureSource.SMS, sms.address, sms.body, sms.stampMillis).capped()
            val id = LocalIdHasher.id(idKey.get(), message)
            // recorded by live capture, by an earlier run, or handled and since deleted/dismissed: nothing new
            if (store.handled(id, clock.millis()) || known(id)) return read
            return when (pipeline.accept(message, CaptureChannel.BACKFILL, epoch, slackOf(sms))) {
                Disposition.RECORDED -> read.copy(entries = read.entries + 1)
                Disposition.REVIEW -> read.copy(review = read.review + 1)
                Disposition.DROPPED, Disposition.KNOWN -> read
                // a restore started meanwhile: this message is read again later, the cursor is not moved
                Disposition.DEFERRED -> throw RestoreDeferred()
            }
        }

        /** Only without a sent time is the provider's stamp possibly seconds off the live PDU's (with one, the ids match exactly). */
        private fun slackOf(sms: InboxSms): Long = if (sms.dateSentMillis <= 0) SAME_SMS_SLACK_MILLIS else 0L

        private suspend fun known(id: String): Boolean =
            db.sources().countByLocalSourceId(id) > 0 || db.captured().knows(id) > 0 || db.reviews().find(id) != null

        private companion object {
            const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        }
    }
