package eg.masroof

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.auth.AeadTokenCipher
import eg.masroof.data.auth.EncryptedSessionStore
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.auth.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionStoreCompatTest {
    private fun prefs(name: String) = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun `REQ-AND-007 a session sealed by the previous build, without an expiry, still loads and refreshes on the first 401`() {
        val prefs = prefs("compat-old")
        val cipher = AeadTokenCipher(inMemoryAead())
        val oldJson = "{\"accessToken\":\"a-old\",\"refreshToken\":\"r-old\",\"memberId\":\"m1\",\"displayName\":\"Mohamed\"}"
        prefs.edit().putString("session", Base64.encodeToString(cipher.encrypt(oldJson.toByteArray()), Base64.NO_WRAP)).commit()

        val loaded = EncryptedSessionStore(prefs, cipher).load() as LoadedSession.Found
        assertEquals("r-old", loaded.session.refreshToken)
        assertNull(loaded.session.accessExpiresAtMillis)
    }

    @Test
    fun `REQ-AND-007 a session with an expiry round trips through the sealed store`() {
        val store = EncryptedSessionStore(prefs("compat-new"), AeadTokenCipher(inMemoryAead()))
        store.save(Session("a", "r", "m1", "Mohamed", accessExpiresAtMillis = 1_234_567L))
        val loaded = store.load() as LoadedSession.Found
        assertEquals(1_234_567L, loaded.session.accessExpiresAtMillis)
    }
}
