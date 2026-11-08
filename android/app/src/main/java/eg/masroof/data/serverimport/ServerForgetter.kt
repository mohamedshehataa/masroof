package eg.masroof.data.serverimport

import android.content.Context
import eg.masroof.data.SettingsRepository
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.auth.TinkKeystore
import eg.masroof.ingest.LocalIdKey
import kotlinx.coroutines.CancellationException
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
    private val context: Context,
    private val localIdKey: LocalIdKey,
) : SessionKeystore {
    override fun destroy() = localIdKey.surviving { TinkKeystore.destroy(context) }
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
            settings.update { it.copy(serverUrl = NO_SERVER) }
        }

        companion object {
            /** An empty stored URL means "no server": the base URL cannot be built, so no request can be made. */
            const val NO_SERVER = ""
        }
    }
