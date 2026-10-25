package eg.masroof

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import eg.masroof.data.SettingsRepository
import eg.masroof.data.auth.AuthRemote
import eg.masroof.data.auth.AuthResult
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.auth.LocalDataWiper
import eg.masroof.data.auth.Session
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.auth.SessionStore
import kotlinx.coroutines.Dispatchers

/** A fresh in-memory AES-GCM key: stands in for the Android Keystore-wrapped keyset in unit tests. */
fun inMemoryAead(): Aead {
    AeadConfig.register()
    val handle =
        KeysetHandle
            .newBuilder()
            .addEntry(KeysetHandle.generateEntryFromParameters(PredefinedAeadParameters.AES256_GCM).withFixedId(1).makePrimary())
            .build()
    return handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
}

fun session(
    access: String = "access-1",
    refresh: String = "refresh-1",
    member: String = "member-1",
) = Session(access, refresh, member, "Mohamed")

class FakeSessionStore(
    var stored: LoadedSession = LoadedSession.Empty,
) : SessionStore {
    var clears = 0
    var saveThrows = false
    var loadThrows = false
    var saves = mutableListOf<Session>()
    override var lastMemberId: String? = null

    override fun load(): LoadedSession {
        if (loadThrows) error("keystore gone")
        return stored
    }

    override fun save(session: Session) {
        if (saveThrows) throw java.security.GeneralSecurityException("keystore gone")
        saves += session
        stored = LoadedSession.Found(session)
    }

    override fun clear() {
        clears++
        stored = LoadedSession.Empty
    }
}

class FakeAuthRemote : AuthRemote {
    var loginResult: AuthResult = AuthResult.Network
    var refreshResult: suspend () -> AuthResult = { AuthResult.Network }
    var logoutOk = true
    var logins = 0
    var refreshes = 0
    val refreshTokensSeen = mutableListOf<String>()
    val logoutTokens = mutableListOf<String>()

    override suspend fun login(
        baseUrl: String,
        email: String,
        password: String,
        deviceName: String,
    ): AuthResult {
        logins++
        return loginResult
    }

    override suspend fun refresh(
        baseUrl: String,
        refreshToken: String,
    ): AuthResult {
        refreshes++
        refreshTokensSeen += refreshToken
        return refreshResult()
    }

    override suspend fun logout(
        baseUrl: String,
        accessToken: String,
        refreshToken: String,
    ): Boolean {
        logoutTokens += refreshToken
        return logoutOk
    }
}

class FakeWiper(
    var unsynced: Int = 0,
) : LocalDataWiper {
    var wipes = 0

    override suspend fun unsyncedCount() = unsynced

    override suspend fun wipe() {
        wipes++
    }
}

object TestAuth {
    fun manager(
        store: SessionStore = FakeSessionStore(),
        remote: AuthRemote = FakeAuthRemote(),
        settings: SettingsRepository = FakeSettings(),
        wiper: LocalDataWiper = FakeWiper(),
    ) = SessionManager(store, remote, settings, wiper, Dispatchers.Unconfined)
}

class FakeNetwork(
    online: Boolean = true,
) : eg.masroof.data.NetworkMonitor {
    val flow = kotlinx.coroutines.flow.MutableStateFlow(online)
    override val online: kotlinx.coroutines.flow.Flow<Boolean> = flow
}
