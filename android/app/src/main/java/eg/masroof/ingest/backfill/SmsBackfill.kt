package eg.masroof.ingest.backfill

import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.capturedCutoff
import eg.masroof.ingest.CaptureChannel
import eg.masroof.ingest.CaptureGate
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.LocalIdHasher
import eg.masroof.ingest.LocalIdKey
import eg.masroof.ingest.MessageHygiene
import eg.masroof.ingest.MoneyLike
import eg.masroof.ingest.SenderSettings
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.capped
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** How a run of the engine ended. A kill ends it with no outcome at all: the state stays RUNNING and WorkManager restarts it. */
enum class BackfillOutcome { DONE, STOPPED, PERMISSION_LOST }

/** One summary at the end of a run (counts only), never one notice per message. */
interface BackfillNotifier {
    fun finished(state: BackfillState)
}

object NoBackfillNotifier : BackfillNotifier {
    override fun finished(state: BackfillState) = Unit
}

/** Messages read per page; Cancel and the saved resume point are honoured per message, so a stop is always within one batch. */
internal const val BACKFILL_BATCH = 200

/** How long a handled message is remembered, so a deleted entry is not recorded again (the same as the review list's retention). */
internal const val BACKFILL_MEMORY_DAYS = 30L

/**
 * "Read past SMS" (REQ-SMS-101..103): reads the allowlisted senders of the chosen range from the inbox, oldest first, in pages, and
 * gives every message to the SAME [SmsPipeline] as live capture (channel BACKFILL). It adds nothing of its own to what a message
 * becomes. The position and counts are saved after every message, so a cancelled or killed run continues where it stopped.
 * Message text lives only in the page being handled and is never logged.
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
    ) {
        suspend fun run(): BackfillOutcome {
            val start = store.state.value
            // not started, or cancelled before the worker got going
            if (start.phase != BackfillPhase.RUNNING) return BackfillOutcome.STOPPED
            return try {
                readAll(start).also { if (it == BackfillOutcome.DONE) notifier.finished(store.state.value) }
            } catch (
                @Suppress("SwallowedException") _: SecurityException,
            ) {
                // READ_SMS was taken away (or never given): what was stored stays, the screen shows the permission message
                finish(store.state.value, BackfillPhase.PERMISSION_LOST, BackfillOutcome.PERMISSION_LOST)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { store.save(store.state.value.copy(phase = BackfillPhase.STOPPED)) }
                throw e
            }
        }

        private suspend fun readAll(start: BackfillState): BackfillOutcome {
            store.forgetBefore(clock.millis() - BACKFILL_MEMORY_DAYS * MILLIS_PER_DAY)
            // expired Undo rows must not make an old message look "known"
            db.captured().purgeBefore(capturedCutoff(clock.millis()))
            var s = if (start.total < 0) start.copy(total = countAllowed(start)).also(store::save) else start
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = inbox.page(s.fromMillis, s.toMillis, s.position, BACKFILL_BATCH)
                if (page.isEmpty()) return finish(s, BackfillPhase.DONE, BackfillOutcome.DONE)
                s = handlePage(page, s) ?: return finish(store.state.value, BackfillPhase.STOPPED, BackfillOutcome.STOPPED)
            }
        }

        /** The progress line's "of Y": the inbox rows of allowed senders in the range (a streaming pass over senders only). */
        private fun countAllowed(s: BackfillState): Int {
            val policy = settings.policy()
            var n = 0
            inbox.forEachAddress(s.fromMillis, s.toMillis) { if (policy.isAllowed(it)) n++ }
            return n
        }

        /** Handles one page, saving after every message; null when an erase started (stop, keep nothing more). */
        private suspend fun handlePage(
            page: List<InboxSms>,
            from: BackfillState,
        ): BackfillState? {
            var s = from
            for (sms in page) {
                currentCoroutineContext().ensureActive()
                if (!gate.isOpen()) return null
                s = handle(sms, s)
                store.save(s)
            }
            return s
        }

        private fun finish(
            s: BackfillState,
            phase: BackfillPhase,
            outcome: BackfillOutcome,
        ): BackfillOutcome {
            store.save(s.copy(phase = phase))
            return outcome
        }

        private suspend fun handle(
            sms: InboxSms,
            s: BackfillState,
        ): BackfillState {
            val moved = s.copy(lastDateMillis = sms.dateMillis, lastId = sms.id)
            if (!settings.policy().isAllowed(sms.address)) {
                // a money-like message from a sender that is not allowed may only suggest the sender (its name, never the text)
                if (sms.address.any { it.isLetter() } && MoneyLike.looksLikeMoney(sms.body) && !MessageHygiene.isPlainSecret(sms.body)) {
                    settings.suggest(sms.address)
                }
                return moved
            }
            val read = moved.copy(read = s.read + 1)
            // the same two cheap drops as the live receiver: a plain OTP never reaches the pipeline
            if (MessageHygiene.isPlainSecret(sms.body)) return read
            val message = IncomingMessage(CaptureSource.SMS, sms.address, sms.body, sms.stampMillis).capped()
            val id = LocalIdHasher.id(idKey.get(), message)
            // recorded by live capture, by an earlier run, or handled and since deleted/dismissed: nothing new
            if (store.handled(id) || known(id)) return read
            return when (pipeline.accept(message, CaptureChannel.BACKFILL)) {
                Disposition.RECORDED -> read.copy(entries = read.entries + 1).also { store.markHandled(id, clock.millis()) }
                Disposition.REVIEW -> read.copy(review = read.review + 1).also { store.markHandled(id, clock.millis()) }
                Disposition.DROPPED -> read
            }
        }

        private suspend fun known(id: String): Boolean =
            db.sources().countByLocalSourceId(id) > 0 || db.captured().knows(id) > 0 || db.reviews().find(id) != null

        private companion object {
            const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        }
    }
