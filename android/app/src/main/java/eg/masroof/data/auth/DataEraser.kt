package eg.masroof.data.auth

import eg.masroof.data.db.DatabaseStartup
import eg.masroof.di.AppScope
import eg.masroof.ingest.CaptureGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Settings calls for "Erase all data". The work runs in an application-scoped job that leaving the screen cannot cancel,
 * signs out of the server first (best effort, so captures stop uploading under that account), and is finished at the next start
 * if the process dies half way ([recoverInterrupted]).
 */
@Singleton
class DataEraser
    @Inject
    constructor(
        private val wiper: LocalDataWiper,
        private val session: SessionManager,
        private val gate: CaptureGate,
        @AppScope private val scope: CoroutineScope,
    ) {
        suspend fun counts(): EraseCounts = wiper.counts()

        /** Throws [PartialEraseException] when the data is erased but a clean-up step failed; any other exception = unchanged. */
        @Suppress("TooGenericExceptionCaught") // handed to the caller through the deferred, never swallowed
        suspend fun erase() {
            val done = CompletableDeferred<Unit>()
            scope.launch {
                try {
                    runCatching { withTimeoutOrNull(SIGN_OUT_WAIT_MILLIS) { session.logout() } }
                    wiper.wipe()
                    done.complete(Unit)
                } catch (e: Throwable) {
                    done.completeExceptionally(e)
                }
            }
            done.await()
        }

        /** The capture flag is only closed while an erase runs: closed at app start means the last erase was cut short. */
        fun recoverInterrupted(): Job? {
            if (DatabaseStartup.isBlocked() || gate.isOpen()) return null
            return scope.launch { runCatching { wiper.wipe() } }
        }

        private companion object {
            const val SIGN_OUT_WAIT_MILLIS = 5_000L
        }
    }
