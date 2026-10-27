package eg.masroof.data.auth

import eg.masroof.data.SettingsRepository
import eg.masroof.data.normalizeServerUrl
import eg.masroof.di.IoDispatcher
import eg.masroof.ingest.CaptureGate
import eg.masroof.ingest.NoopCaptureGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** How long a caller waits for a refresh before giving up (the refresh itself keeps running). */
internal const val REFRESH_WAIT_MILLIS = 30_000L

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
        private val capture: CaptureGate = NoopCaptureGate,
        private val clock: Clock = Clock.systemUTC(),
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
                        capture.open()
                        _state.value = SessionState.SignedIn(loaded.session.displayName)
                    }
                    LoadedSession.Empty -> {
                        capture.close()
                        _state.value = SessionState.SignedOut()
                    }
                    LoadedSession.Invalidated -> {
                        capture.close()
                        _state.value = SessionState.SignedOut(SignedOutNotice.KEYSTORE_RESET)
                    }
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

        /** The signed-in member's id (not secret), or null when signed out. */
        suspend fun memberId(): String? {
            restore()
            return current?.memberId
        }

        suspend fun accessToken(): String? {
            restore()
            return current?.accessToken
        }

        /**
         * The token to send now. When the access token is past, or within 60 s of, its known expiry it is refreshed first
         * (single-flight through [refreshAfter]), so a cold start with an expired token makes ONE refresh instead of a
         * burst of 401s. An unknown expiry (session stored by an older build) is left to the first 401. A failed
         * proactive refresh is not retried for 30 s and the current token is sent anyway.
         */
        suspend fun freshAccessToken(): String? {
            restore()
            val session = current ?: return null
            val expiresAt = session.accessExpiresAtMillis ?: return session.accessToken
            val now = clock.millis()
            if (now < expiresAt - EXPIRY_SKEW_MILLIS || now < retryProactiveAt) return session.accessToken
            val refreshed = withTimeoutOrNull(REFRESH_WAIT_MILLIS) { refreshAfter(session.accessToken) }
            if (refreshed == null) retryProactiveAt = clock.millis() + PROACTIVE_BACKOFF_MILLIS
            return refreshed ?: current?.accessToken
        }

        @Volatile private var retryProactiveAt = 0L

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
                AuthResult.InvalidUrl -> LoginResult.InvalidUrl
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
                capture.close()
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
            capture.close()
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
            // A refresh that started for the previous session must not overwrite this one: bump the generation first.
            commitLock.withLock {
                generation++
                val persisted = persist(session)
                withContext(io) { runCatchingStore { store.lastMemberId = session.memberId } }
                settings.update { it.copy(serverUrl = base) }
                current = session
                capture.open()
                _state.value = SessionState.SignedIn(session.displayName, persisted)
            }
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
            // The refresh runs in the manager's own scope, not the caller's: a caller that times out or leaves the
            // screen abandons only its WAIT. A cancelled refresh would lose the server's reply after the refresh token
            // was rotated, and the next refresh would replay the spent token (reuse detection ends the whole family).
            // The request itself is bounded by the auth client's call timeout.
            // ONE refresh per stale token is shared by every waiter (a failed refresh ends all their waits instead of
            // starting N serial attempts, each of which after a lost reply would replay a spent refresh token).
            val flight =
                synchronized(flightLock) {
                    inFlight?.takeIf { it.stale == staleToken && it.result.isActive }
                        ?: Flight(staleToken, refreshScope.async(start = CoroutineStart.LAZY) { refreshExclusive(staleToken) })
                            .also { inFlight = it }
                }
            flight.result.start()
            return flight.result.await()
        }

        private class Flight(
            val stale: String?,
            val result: Deferred<String?>,
        )

        private val flightLock = Any()

        @Volatile private var inFlight: Flight? = null

        private val refreshScope = CoroutineScope(SupervisorJob() + io)

        /** Bumped under [commitLock] whenever the session is replaced or ended; a refresh result commits only if unchanged. */
        private var generation = 0
        private val commitLock = Mutex()

        private suspend fun refreshExclusive(staleToken: String?): String? =
            refreshMutex.withLock {
                val startGeneration = commitLock.withLock { generation }
                val session = current ?: return@withLock null
                if (staleToken != null && session.accessToken != staleToken) return@withLock session.accessToken
                val base = settings.current().serverUrl
                when (val result = remote.refresh(base, session.refreshToken)) {
                    is AuthResult.Success -> commitRefreshed(base, startGeneration, result.session)
                    AuthResult.Rejected -> {
                        commitLock.withLock { if (generation == startGeneration) expire() }
                        null
                    }
                    else -> null
                }
            }

        /**
         * Commits a rotated session only if nobody signed out or in meanwhile. Otherwise the result is discarded and its
         * refresh token revoked (best effort), so a refresh in flight can never resurrect a logged-out session or
         * overwrite another member's sign-in.
         */
        private suspend fun commitRefreshed(
            base: String,
            startGeneration: Int,
            fresh: Session,
        ): String? {
            val committed =
                commitLock.withLock {
                    if (generation != startGeneration) return@withLock false
                    // Memory first: the rotated token must be the one used next even if persisting fails.
                    current = fresh
                    val persisted = persist(fresh)
                    _state.value = SessionState.SignedIn(fresh.displayName, persisted)
                    true
                }
            if (committed) return fresh.accessToken
            withTimeoutOrNull(LOGOUT_TIMEOUT_MILLIS) { remote.logout(base, fresh.accessToken, fresh.refreshToken) }
            return null
        }

        /** The server no longer accepts this session. Queued entries upload after the next sign-in. Caller holds [commitLock]. */
        private suspend fun expire() {
            generation++
            capture.close()
            withContext(io) { runCatchingStore { store.clear() } }
            current = null
            _state.value = SessionState.SignedOut(SignedOutNotice.SESSION_EXPIRED)
        }

        /** Revokes the refresh token (best effort), then wipes tokens and all cached data for this account. */
        suspend fun logout() {
            restore()
            // First, before any network call: from now on no message is read or queued for this account.
            capture.close()
            // End the session in memory BEFORE the revoke call: a refresh in flight can no longer commit (see generation).
            val session =
                commitLock.withLock {
                    generation++
                    current.also { current = null }
                }
            try {
                if (session != null) {
                    // Best effort and bounded: a stuck network must never keep the user from signing out locally.
                    withTimeoutOrNull(LOGOUT_TIMEOUT_MILLIS) {
                        remote.logout(settings.current().serverUrl, session.accessToken, session.refreshToken)
                    }
                }
            } finally {
                // Even if the caller (a screen's scope) is cancelled during the revoke wait, the wipe completes.
                withContext(NonCancellable) {
                    withContext(io) {
                        runCatchingStore { store.clear() }
                        runCatchingStore { store.lastMemberId = null }
                    }
                    wiper.wipe()
                    _state.value = SessionState.SignedOut()
                }
            }
        }

        private companion object {
            const val DEVICE_NAME = "Masroof Android"
            const val EXPIRY_SKEW_MILLIS = 60_000L
            const val PROACTIVE_BACKOFF_MILLIS = 30_000L
            const val LOGOUT_TIMEOUT_MILLIS = 25_000L
        }
    }
