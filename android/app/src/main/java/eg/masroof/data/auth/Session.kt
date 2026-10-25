package eg.masroof.data.auth

import kotlinx.serialization.Serializable

/** The signed-in user's tokens. [toString] is redacted so a stray log line can never leak a token. */
@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val memberId: String,
    val displayName: String,
) {
    override fun toString(): String = "Session(member=$memberId)"
}

/** Why the user is looking at the login screen without having asked to sign out. */
enum class SignedOutNotice { SESSION_EXPIRED, KEYSTORE_RESET }

sealed interface SessionState {
    /** The encrypted store has not been read yet (app start). */
    data object Restoring : SessionState

    data class SignedOut(
        val notice: SignedOutNotice? = null,
    ) : SessionState

    /** [persisted] is false when the encrypted store could not be written: the session lives in memory only. */
    data class SignedIn(
        val displayName: String,
        val persisted: Boolean = true,
    ) : SessionState
}

sealed interface LoadedSession {
    data class Found(
        val session: Session,
    ) : LoadedSession

    data object Empty : LoadedSession

    /** The ciphertext could not be decrypted (keystore key reset or data corrupt); it has been discarded. */
    data object Invalidated : LoadedSession
}

/** Blocking, small-file persistence; callers run it on an IO dispatcher. */
interface SessionStore {
    fun load(): LoadedSession

    fun save(session: Session)

    fun clear()

    /** Non-secret id of the last account, so a different account never inherits cached data or queued entries. */
    var lastMemberId: String?
}

sealed interface AuthResult {
    data class Success(
        val session: Session,
    ) : AuthResult

    /** 400/401: wrong credentials, or the refresh token is invalid, expired or was reused. */
    data object Rejected : AuthResult

    data class RateLimited(
        val retryAfterSeconds: Int?,
    ) : AuthResult

    data object Network : AuthResult

    data object Server : AuthResult

    /** The server URL is not a valid http(s) URL (Retrofit rejected it before any request was made). */
    data object InvalidUrl : AuthResult
}

sealed interface LoginResult {
    data object Success : LoginResult

    /**
     * The credentials are valid but belong to a different member than the one whose [unsynced] entries are queued
     * on this device. Nothing is stored until [SessionManager.confirmAccountSwitch].
     */
    data class AccountSwitch(
        val unsynced: Int,
    ) : LoginResult

    data object InvalidUrl : LoginResult

    data object InvalidCredentials : LoginResult

    data class RateLimited(
        val retryAfterSeconds: Int?,
    ) : LoginResult

    data object Network : LoginResult

    data object Server : LoginResult
}

interface AuthRemote {
    suspend fun login(
        baseUrl: String,
        email: String,
        password: String,
        deviceName: String,
    ): AuthResult

    suspend fun refresh(
        baseUrl: String,
        refreshToken: String,
    ): AuthResult

    /** Best effort: revokes [refreshToken] server side. Returns false when it could not be confirmed. */
    suspend fun logout(
        baseUrl: String,
        accessToken: String,
        refreshToken: String,
    ): Boolean
}

/** Removes everything cached for the account: Room tables (expenses, outbox, lookups) and pending sync work. */
interface LocalDataWiper {
    /** Queued entries (pending or failed) that exist only on this device. */
    suspend fun unsyncedCount(): Int

    /** The part of [unsyncedCount] that can still sync (not permanently failed). */
    suspend fun retryableCount(): Int

    suspend fun wipe()
}
