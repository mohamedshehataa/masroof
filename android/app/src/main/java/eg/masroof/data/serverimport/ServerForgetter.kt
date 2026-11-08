package eg.masroof.data.serverimport

import eg.masroof.data.SettingsRepository
import eg.masroof.data.auth.SessionManager
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What happens after an import completed (REQ-SEC-065): the phone forgets that a server ever existed. */
interface ServerForgetter {
    suspend fun forget()
}

/** Deletes the Keystore-wrapped key the session blob was sealed with. */
interface SessionKeystore {
    fun destroy()
}

/**
 * The session key lives in the Tink keyset that the local message id key is sealed with too. Deleting the keyset would make that
 * key unreadable and change every local message id (a backfill would then record each message twice), so the key is read first and
 * sealed again under the keyset the next use creates ([LocalIdKey.surviving]).
 */
class TinkSessionKeystore(
    private val onDestroyed: () -> Unit = {},
) : SessionKeystore {
    /**
     * INTERIM (fix round 1): the keyset is NOT deleted yet. The local message id key still lives in it, and a kill between deleting
     * the keyset and sealing that key again would lose the key. It is deleted once the key moves to `LocalKeystore` (STORY-186), in
     * a crash-safe order; until then only the session blob is gone.
     */
    override fun destroy() {
        onDestroyed()
    }
}

/**
 * Logs out once (best effort: its failure is ignored), deletes the session blob, its Keystore key and the stored server URL. After
 * this no code path has a session or a base URL, so no request can be made. Local data is never touched (REQ-AND-131).
 */
@Singleton
class DefaultServerForgetter
    @Inject
    constructor(
        private val session: SessionManager,
        private val settings: SettingsRepository,
        private val keystore: SessionKeystore,
    ) : ServerForgetter {
        @Suppress("TooGenericExceptionCaught") // each step is best effort and must not stop the next one
        override suspend fun forget() {
            // SessionManager.logout calls the server once, bounded, and clears the blob even if that call fails
            try {
                session.logout()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the revoke is best effort
            }
            try {
                keystore.destroy()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the blob is already gone: an undeletable key protects nothing
            }
            // a DataStore write can fail (full disk): retried, and if it still fails the caller keeps `forgetPending` and tries again
            var failure: IOException? = null
            repeat(URL_ATTEMPTS) {
                try {
                    settings.update { it.copy(serverUrl = NO_SERVER) }
                    return
                } catch (e: IOException) {
                    failure = e
                }
            }
            throw failure ?: IOException("server URL not deleted")
        }

        companion object {
            /** An empty stored URL means "no server": the base URL cannot be built, so no request can be made. */
            const val NO_SERVER = ""
            private const val URL_ATTEMPTS = 3
        }
    }
