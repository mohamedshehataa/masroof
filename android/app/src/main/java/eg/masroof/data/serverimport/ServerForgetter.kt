package eg.masroof.data.serverimport

import android.content.Context
import eg.masroof.data.SettingsRepository
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.auth.TinkKeystore
import eg.masroof.ingest.LocalIdKey
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
 * Deletes the session keyset and its Keystore master key. The local message id key used to be sealed under that keyset; it now lives
 * under `LocalKeystore`, and [LocalIdKey.detachFromSessionKeyset] first proves it reads back from there (a crash-safe order: a kill or an
 * exception at any step loses nothing), and only then runs the deletion.
 */
class TinkSessionKeystore(
    private val context: Context,
    private val localIdKey: LocalIdKey,
    private val onDestroyed: () -> Unit = {},
) : SessionKeystore {
    override fun destroy() {
        localIdKey.detachFromSessionKeyset { TinkKeystore.destroy(context) }
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
            // a keystore failure must not stop the URL from going, but it is NOT forgotten: it is thrown at the end, the caller keeps
            // `forgetPending`, and the next Import (or Settings open) runs this again until the keyset and its master key are gone
            var keystoreFailure: Exception? = null
            try {
                keystore.destroy()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                keystoreFailure = e
            }
            // a DataStore write can fail (full disk): retried, and if it still fails the caller keeps `forgetPending` and tries again
            var failure: IOException? = null
            var urlGone = false
            repeat(URL_ATTEMPTS) {
                if (urlGone) return@repeat
                try {
                    settings.update { it.copy(serverUrl = NO_SERVER) }
                    urlGone = true
                } catch (e: IOException) {
                    failure = e
                }
            }
            if (!urlGone) throw failure ?: IOException("server URL not deleted")
            if (keystoreFailure != null) throw IOException("session keyset not deleted", keystoreFailure)
        }

        companion object {
            /** An empty stored URL means "no server": the base URL cannot be built, so no request can be made. */
            const val NO_SERVER = ""
            private const val URL_ATTEMPTS = 3
        }
    }
