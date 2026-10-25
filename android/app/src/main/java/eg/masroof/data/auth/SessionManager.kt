package eg.masroof.data.auth

import eg.masroof.data.SettingsRepository
import eg.masroof.data.normalizeServerUrl
import eg.masroof.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the signed-in session: restore, login, single-flight refresh, expiry and logout. */
@Singleton
class SessionManager
    @Inject
    constructor(
        private val store: SessionStore,
        private val remote: AuthRemote,
        private val settings: SettingsRepository,
        private val wiper: LocalDataWiper,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        private val _state = MutableStateFlow<SessionState>(SessionState.Restoring)
        val state: StateFlow<SessionState> = _state

        @Volatile private var current: Session? = null

        @Volatile private var restored = false
        private val restoreMutex = Mutex()
        private val refreshMutex = Mutex()

        /** Reads the encrypted store once. Safe to call from anywhere, any number of times. */
        suspend fun restore() {
            if (restored) return
            restoreMutex.withLock {
                if (restored) return
                when (val loaded = withContext(io) { safeLoad() }) {
                    is LoadedSession.Found -> {
                        current = loaded.session
                        _state.value = SessionState.SignedIn(loaded.session.displayName)
                    }
                    LoadedSession.Empty -> _state.value = SessionState.SignedOut()
                    LoadedSession.Invalidated -> _state.value = SessionState.SignedOut(SignedOutNotice.KEYSTORE_RESET)
                }
                restored = true
            }
        }

        /** A store that cannot even be opened (keystore or keyset loss) is the same as a lost session, never a crash. */
        @Suppress("TooGenericExceptionCaught")
        private fun safeLoad(): LoadedSession =
            try {
                store.load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LoadedSession.Invalidated
            }

        suspend fun accessToken(): String? {
            restore()
            return current?.accessToken
        }

        suspend fun login(
            serverUrl: String,
            email: String,
            password: String,
        ): LoginResult {
            val base = normalizeServerUrl(serverUrl) ?: return LoginResult.InvalidUrl
            restore()
            return when (val result = remote.login(base, email.trim(), password, DEVICE_NAME)) {
                is AuthResult.Success -> onAuthenticated(base, result.session)
                AuthResult.Rejected -> LoginResult.InvalidCredentials
                is AuthResult.RateLimited -> LoginResult.RateLimited(result.retryAfterSeconds)
                AuthResult.Network -> LoginResult.Network
                AuthResult.Server -> LoginResult.Server
            }
        }

        private suspend fun onAuthenticated(
            base: String,
            session: Session,
        ): LoginResult {
            val previous = withContext(io) { store.lastMemberId }
            if (previous != null && previous != session.memberId) {
                // Another account must never see (or upload) the previous account's cache and queued entries,
                // but silently deleting unsynced entries is not acceptable: ask first.
                val unsynced = wiper.unsyncedCount()
                if (unsynced > 0) {
                    pendingSwitch = base to session
                    return LoginResult.AccountSwitch(unsynced)
                }
                wiper.wipe()
            }
            finishSignIn(base, session)
            return LoginResult.Success
        }

        private var pendingSwitch: Pair<String, Session>? = null

        /** The user accepted losing the previous account's unsynced entries: wipe, then complete the sign-in. */
        suspend fun confirmAccountSwitch() {
            val (base, session) = pendingSwitch ?: return
            pendingSwitch = null
            wiper.wipe()
            finishSignIn(base, session)
        }

        /** The user backed out: revoke the session the server just issued and keep everything as it was. */
        suspend fun cancelAccountSwitch() {
            val (base, session) = pendingSwitch ?: return
            pendingSwitch = null
            remote.logout(base, session.accessToken, session.refreshToken)
        }

        suspend fun unsyncedCount(): Int = wiper.unsyncedCount()

        suspend fun retryableCount(): Int = wiper.retryableCount()

        private suspend fun finishSignIn(
            base: String,
            session: Session,
        ) {
            val persisted = persist(session)
            withContext(io) { runCatchingStore { store.lastMemberId = session.memberId } }
            settings.update { it.copy(serverUrl = base) }
            current = session
            _state.value = SessionState.SignedIn(session.displayName, persisted)
        }

        /** Writing the encrypted store must never be fatal: on failure the session stays in memory only. */
        private suspend fun persist(session: Session): Boolean = withContext(io) { runCatchingStore { store.save(session) } }

        @Suppress("TooGenericExceptionCaught")
        private inline fun runCatchingStore(block: () -> Unit): Boolean =
            try {
                block()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }

        /**
         * Single-flight refresh after a 401 for [staleToken]. Concurrent callers queue on the mutex; whoever arrives
         * after the first refresh finds a newer token and reuses it, so the rotating refresh token is spent exactly
         * once. Returns the token to retry with, or null. Only a server rejection of the refresh token ends the session;
         * a network or 5xx failure keeps it so a bad connection does not sign the user out.
         */
        suspend fun refreshAfter(staleToken: String?): String? {
            restore()
            return refreshMutex.withLock {
                val session = current ?: return@withLock null
                if (staleToken != null && session.accessToken != staleToken) return@withLock session.accessToken
                val base = settings.current().serverUrl
                when (val result = remote.refresh(base, session.refreshToken)) {
                    is AuthResult.Success -> {
                        val persisted = persist(result.session)
                        current = result.session
                        _state.value = SessionState.SignedIn(result.session.displayName, persisted)
                        result.session.accessToken
                    }
                    AuthResult.Rejected -> {
                        expire()
                        null
                    }
                    else -> null
                }
            }
        }

        /** The server no longer accepts this session. Queued entries are kept; they upload after the next sign-in. */
        private suspend fun expire() {
            withContext(io) { runCatchingStore { store.clear() } }
            current = null
            _state.value = SessionState.SignedOut(SignedOutNotice.SESSION_EXPIRED)
        }

        /** Revokes the refresh token (best effort), then wipes tokens and all cached data for this account. */
        suspend fun logout() {
            restore()
            val session = current
            if (session != null) {
                remote.logout(settings.current().serverUrl, session.accessToken, session.refreshToken)
            }
            withContext(io) {
                runCatchingStore { store.clear() }
                runCatchingStore { store.lastMemberId = null }
            }
            wiper.wipe()
            current = null
            _state.value = SessionState.SignedOut()
        }

        private companion object {
            const val DEVICE_NAME = "Masroof Android"
        }
    }
