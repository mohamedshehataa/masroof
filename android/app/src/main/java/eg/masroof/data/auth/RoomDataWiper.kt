package eg.masroof.data.auth

import androidx.room.withTransaction
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import eg.masroof.data.backup.RestoreStage
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.DefaultSeed
import eg.masroof.data.insights.InsightsCache
import eg.masroof.di.CameraDir
import eg.masroof.di.IoDispatcher
import eg.masroof.di.ReceiptsDir
import eg.masroof.di.SafetyCopyDir
import eg.masroof.ingest.CaptureData
import eg.masroof.ingest.CaptureGate
import eg.masroof.ingest.CapturedNotifier
import eg.masroof.ingest.EraseStage
import eg.masroof.ingest.NoCaptureData
import eg.masroof.ingest.NoCapturedNotifier
import eg.masroof.ingest.NoopCaptureGate
import eg.masroof.ingest.SMS_INTAKE_TAG
import eg.masroof.ingest.SMS_UPLOAD_RETRY_WORK
import eg.masroof.ingest.SMS_UPLOAD_WORK
import eg.masroof.ingest.backfill.SMS_BACKFILL_WORK
import eg.masroof.work.OUTBOX_RETRY_WORK_NAME
import eg.masroof.work.OUTBOX_WORK_NAME
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("LongParameterList") // one injected seam per thing the erase must empty
@Singleton
class RoomDataWiper
    @Inject
    constructor(
        private val db: AppDatabase,
        private val insights: InsightsCache,
        private val workManager: WorkManager,
        @IoDispatcher private val io: CoroutineDispatcher,
        private val capture: CaptureData = NoCaptureData,
        private val notifier: CapturedNotifier = NoCapturedNotifier,
        private val gate: CaptureGate = NoopCaptureGate,
        @ReceiptsDir private val receiptsDir: File = File("no-receipts-dir"),
        @SafetyCopyDir private val safetyCopyDir: File = File("no-safety-copy-dir"),
        @CameraDir private val cameraDir: File = File("no-camera-dir"),
        private val reminders: eg.masroof.work.ReminderLog = eg.masroof.work.NoReminderLog,
        private val subscriptionPrefs: eg.masroof.data.bills.SubscriptionPrefs = eg.masroof.data.bills.NoSubscriptionPrefs,
        private val answerPrefs: eg.masroof.data.answers.AnswerPrefs = eg.masroof.data.answers.NoAnswerPrefs,
        private val eraseLock: EraseLock = EraseLock(),
        private val backfillReset: BackfillReset = BackfillReset {},
    ) : LocalDataWiper {
        override suspend fun counts(): EraseCounts = withContext(io) { EraseCounts(db.expenses().count(), db.receipts().count()) }

        /**
         * "Erase all data" (REQ-AND-132). The persisted stage goes ERASING before the database transaction and CLEANUP right
         * after the commit; capture stays dropped while it is not NONE. A crash is recognised at the next start by the stage:
         * ERASING reruns this (the user confirmed it, and the commit may or may not have happened), CLEANUP only finishes the
         * clean-up ([recover]). If the transaction throws, nothing changed and the stage returns to NONE. If a later step throws,
         * the data is already gone: the stage stays CLEANUP and [PartialEraseException] says so.
         */
        @Suppress("TooGenericExceptionCaught") // rethrown: only decides whether the stage returns to NONE
        override suspend fun wipe() {
            check(gate.setStage(EraseStage.ERASING)) { "could not record the erase" }
            // every long job that began before this (a "Read past SMS" run) is stale from now on and writes nothing more
            eraseLock.bump()
            var committed = false
            try {
                workManager.cancelUniqueWork(SMS_BACKFILL_WORK).await()
                // An unfinished restore must not resume after an Erase: its journal goes FIRST.
                withContext(io) { check(deleteRestoreJournal()) { "restore journal could not be deleted" } }
                workManager.cancelUniqueWork(OUTBOX_WORK_NAME).await()
                workManager.cancelUniqueWork(OUTBOX_RETRY_WORK_NAME).await()
                // Queued messages' input data holds text: cancel the jobs and WAIT for the cancellation, so no intake worker can
                // insert after the tables are cleared.
                workManager.cancelUniqueWork(SMS_UPLOAD_WORK).await()
                workManager.cancelUniqueWork(SMS_UPLOAD_RETRY_WORK).await()
                workManager.cancelAllWorkByTag(SMS_INTAKE_TAG).await()
                withContext(io) { awaitIdle() }
                workManager.pruneWork()
                withContext(io) {
                    db.withTransaction {
                        db.clearAllTables()
                        // The defaults (categories, "Me") are not user data: the app must be as after a fresh install.
                        DefaultSeed.seed(db.openHelper.writableDatabase)
                        // The restore commit point must not outlive the data it described.
                        db.openHelper.writableDatabase.execSQL("PRAGMA application_id = 0")
                    }
                }
                committed = true
                // Without a recorded CLEANUP a restart would rerun the table wipe over entries added meanwhile: give up loudly.
                if (!gate.setStage(EraseStage.CLEANUP) && !gate.setStage(EraseStage.CLEANUP)) throw PartialEraseException()
                finishCleanup()
            } catch (e: Throwable) {
                if (!committed && !gate.setStage(EraseStage.NONE)) {
                    e.addSuppressed(IllegalStateException("could not reopen capture"))
                }
                throw e
            }
        }

        override suspend fun recover(stage: EraseStage) {
            when (stage) {
                EraseStage.NONE -> Unit
                EraseStage.ERASING -> wipe()
                EraseStage.CLEANUP -> finishCleanup()
            }
        }

        /** Everything outside the database transaction, then the stage returns to NONE. Each step runs even if one failed. */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun finishCleanup() {
            var failed = false
            val steps: List<suspend () -> Unit> =
                listOf(
                    { withContext(io) { deleteReceipts() } },
                    { withContext(io) { deleteSafetyCopies() } },
                    // a restore's staging folder (checked backup data), its marker and the old receipts of an interrupted swap
                    {
                        withContext(io) {
                            check(
                                eg.masroof.data.backup.RestoreStage
                                    .sweep(receiptsDir),
                            ) { "restore staging could not be deleted" }
                        }
                    },
                    // staged backup rows a killed backup left behind (encrypted, but still data)
                    {
                        withContext(io) {
                            check(
                                eg.masroof.data.backup.BackupStage
                                    .sweep(
                                        eg.masroof.data.backup.BackupStage
                                            .dir(safetyCopyDir),
                                    ) == 0,
                            )
                        }
                    },
                    { withContext(io) { capture.clear() } },
                    // the run state and the 30-day memory of "Read past SMS": the screen must not show an erased run
                    { withContext(io) { backfillReset.reset() } },
                    { withContext(io) { insights.clear() } },
                    // nothing stays on the lock screen: every "Recorded ... Undo" notification goes
                    { notifier.cancelAll() },
                    // the "already reminded" memory of the bill reminders
                    { withContext(io) { reminders.clear() } },
                    // dismissed suggestions, price notices and renewal anchor days
                    { subscriptionPrefs.clear() },
                    // answered cards, refund and transfer links, ignored cards, card aliases, the ATM choice (STORY-208)
                    { answerPrefs.clear() },
                )
            for (step in steps) {
                try {
                    step()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed = true
                }
            }
            // Shrink the file so deleted rows do not linger in free pages or the WAL (best effort, not part of "failed").
            withContext(io) { runCatching { compact() } }
            if (failed || !gate.setStage(EraseStage.NONE)) throw PartialEraseException()
        }

        private fun deleteRestoreJournal(): Boolean {
            val journalGone =
                eg.masroof.data.backup.RestoreStage
                    .deleteJournal(receiptsDir)
            return journalGone
        }

        private fun compact() {
            val raw = db.openHelper.writableDatabase
            raw.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            raw.execSQL("VACUUM")
            // VACUUM itself writes through the WAL: truncate again so the freed pages do not stay in the -wal file.
            raw.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }

        /** The pre-migration safety copy is a full copy of the old database (REQ-AND-132): the copy, its `.part`, `-wal`, `-shm`. */
        private fun deleteSafetyCopies() {
            val failed = safetyCopyDir.listFiles { f -> f.name.startsWith("pre-migration-") }?.filterNot { it.delete() }.orEmpty()
            check(failed.isEmpty()) { "${failed.size} safety copy files could not be deleted" }
        }

        private fun deleteReceipts() {
            // a delete that returns false is a failure: the erase is Incomplete and stays in CLEANUP
            val failed = receiptsDir.listFiles()?.filterNot { it.deleteRecursively() }.orEmpty()
            check(failed.isEmpty()) { "${failed.size} receipt files could not be deleted" }
            // a photo the camera hand-over left in the cache is a receipt copy too
            cameraDir.listFiles()?.forEach { it.deleteRecursively() }
        }

        /**
         * Best effort: waits at most five seconds for capture jobs that are still RUNNING. It does not guarantee they ended;
         * a job that outlives the wait is stopped by the gate, which the pipeline re-checks inside its write transaction.
         */
        private fun awaitIdle() {
            val deadline = System.nanoTime() + IDLE_TIMEOUT_NANOS
            while (System.nanoTime() < deadline && running()) Thread.sleep(IDLE_POLL_MILLIS)
        }

        private fun running(): Boolean =
            listOf(
                workManager.getWorkInfosByTag(SMS_INTAKE_TAG),
                workManager.getWorkInfosForUniqueWork(SMS_UPLOAD_WORK),
                workManager.getWorkInfosForUniqueWork(SMS_UPLOAD_RETRY_WORK),
            ).any { f -> f.get().any { it.state == WorkInfo.State.RUNNING } }

        private companion object {
            const val IDLE_TIMEOUT_NANOS = 5_000_000_000L
            const val IDLE_POLL_MILLIS = 50L
        }
    }

/** What "Erase all data" calls to forget a "Read past SMS" run (state and memory); a function so the data layer needs no ingest type. */
fun interface BackfillReset {
    fun reset()
}
