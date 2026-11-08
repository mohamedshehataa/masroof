package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.crypto.tink.Aead
import eg.masroof.data.auth.BackfillReset
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.auth.LocalKeystore
import eg.masroof.data.auth.RecoveringAead
import eg.masroof.data.auth.RoomDataWiper
import eg.masroof.data.db.AppDatabase
import eg.masroof.ingest.SignatureClaim
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.backfill.BackfillPhase
import eg.masroof.ingest.backfill.BackfillState
import eg.masroof.ingest.backfill.BackfillStore
import eg.masroof.ingest.backfill.SmsBackfill
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ingest.parse.NotificationApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val R4_PUSH = "InstaPay\nYou have received  1.00 EGP from mohamed.shehataaaaa@instapay"

/** An AEAD whose key is gone: every call fails, as after the OS reset the Keystore. */
private object BrokenAead : Aead {
    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = throw GeneralSecurityException("key gone")

    override fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = throw GeneralSecurityException("key gone")
}

/** STORY-186: the sealed memory uses the phone's own key (`LocalKeystore`), never the session keyset. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackfillRound4Test {
    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private val clock = Clock.fixed(ANSWER_NOW, ZoneOffset.UTC)
    private val gate = MemoryGate()
    private val lock = EraseLock()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun `REQ-SMS-102 a key that is gone never crashes the memory, which simply starts empty`() {
        val store = BackfillStore(prefs("r4-broken"), BrokenAead)
        store.mark("id", BACKFILL_NOW)
        store.markSignature("sig", BACKFILL_NOW, BACKFILL_NOW)
        assertFalse(store.handled("id", BACKFILL_NOW))
        assertFalse(store.handled("other", BACKFILL_NOW))
        // a time that could not be recorded leaves only a keyed marker (no time): it was seen, when unknown
        assertEquals(setOf("sigx:sig"), prefs("r4-broken").all.keys)
        assertTrue(store.seenSignature("sig", 0L, Long.MAX_VALUE, BACKFILL_NOW))
        assertEquals(SignatureClaim.ALREADY_CLAIMED, store.claimSignature("sig", 0L, Long.MAX_VALUE, BACKFILL_NOW, BACKFILL_NOW))
    }

    /** An AEAD that fails its next [failures] calls, then works (a transient Keystore error). */
    private class Flaky(
        private val inner: Aead,
        var failures: Int,
    ) : Aead {
        override fun encrypt(
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray = if (failures-- > 0) throw GeneralSecurityException("transient") else inner.encrypt(plaintext, associatedData)

        override fun decrypt(
            ciphertext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray = if (failures-- > 0) throw GeneralSecurityException("transient") else inner.decrypt(ciphertext, associatedData)
    }

    @Test
    fun `REQ-SMS-102 a transient seal failure never destroys the key and what was sealed before still opens`() {
        var destroyed = 0
        val flaky = Flaky(inMemoryAead(), failures = 0)
        val aead = RecoveringAead(build = { flaky }, destroy = { destroyed++ })
        val store = BackfillStore(prefs("r4-transient"), aead)
        store.mark("before", BACKFILL_NOW)
        flaky.failures = 1
        store.mark("during", BACKFILL_NOW)
        assertEquals("the key was not destroyed", 0, destroyed)
        assertTrue(store.handled("before", BACKFILL_NOW))
        assertTrue(store.handled("during", BACKFILL_NOW))
    }

    @Test
    fun `REQ-SMS-102 a permanent seal failure throws and still never destroys the key`() {
        var destroyed = 0
        val aead = RecoveringAead(build = { BrokenAead }, destroy = { destroyed++ })
        try {
            aead.encrypt(byteArrayOf(1), byteArrayOf())
            fail("a permanent failure must be thrown")
        } catch (_: GeneralSecurityException) {
            // expected: the store treats it as "not remembered"
        }
        assertEquals(0, destroyed)
    }

    @Test
    fun `REQ-SMS-102 a keyset whose master key is gone from the Keystore is recreated and old values read as absent`() {
        assertTrue(LocalKeystore.needsRecovery(hasKeyset = true, masterPresent = false, masterWasMade = true))
        assertFalse(
            "the Keystore never worked: keep the keyset",
            LocalKeystore.needsRecovery(true, masterPresent = false, masterWasMade = false),
        )
        assertFalse(
            "a first start has nothing to recover",
            LocalKeystore.needsRecovery(hasKeyset = false, masterPresent = false, masterWasMade = true),
        )
        assertFalse(LocalKeystore.needsRecovery(hasKeyset = true, masterPresent = true, masterWasMade = true))
        var destroyed = 0
        var lost = true
        val oldKey = inMemoryAead()
        val newKey = inMemoryAead()
        val oldStore = BackfillStore(prefs("r4-lost"), oldKey)
        oldStore.mark("old", BACKFILL_NOW)
        val aead =
            RecoveringAead(
                build = { newKey },
                destroy = { destroyed++ },
                recoverIfLost = {
                    if (lost) {
                        lost = false
                        destroyed++
                        true
                    } else {
                        false
                    }
                },
            )
        val store = BackfillStore(prefs("r4-lost"), aead)
        assertFalse("sealed under the lost key: absent, not an error", store.handled("old", BACKFILL_NOW))
        store.mark("new", BACKFILL_NOW)
        assertTrue(store.handled("new", BACKFILL_NOW))
        assertEquals("recreated once", 1, destroyed)
    }

    @Test
    fun `REQ-SMS-102 only an invalidated or unrecoverable master key counts as a lost key`() {
        assertTrue(LocalKeystore.isKeyLost(java.security.UnrecoverableKeyException("x")))
        assertTrue(LocalKeystore.isKeyLost(GeneralSecurityException("wrapped", java.security.UnrecoverableKeyException("x"))))
        assertTrue(LocalKeystore.isKeyLost(RuntimeException(android.security.keystore.KeyPermanentlyInvalidatedException())))
        assertFalse(LocalKeystore.isKeyLost(GeneralSecurityException("transient")))
        assertFalse(LocalKeystore.isKeyLost(java.io.IOException("busy")))
    }

    @Test
    fun `REQ-SMS-102 the local key has its own keyset file and master alias, apart from the session keyset`() {
        assertEquals("masroof_local_keyset", LocalKeystore.KEYSET_PREFS)
        assertEquals("masroof_local_master", LocalKeystore.MASTER_KEY_ALIAS)
        val session = File("src/main/java/eg/masroof/data/auth/TinkKeystore.kt").readText()
        assertFalse(session.contains(LocalKeystore.KEYSET_PREFS))
        assertFalse(session.contains(LocalKeystore.MASTER_KEY_ALIAS))
    }

    @Test
    fun `REQ-SMS-102 nothing of the backfill depends on the session keyset`() {
        val files =
            File("src/main/java/eg/masroof/ingest/backfill").walkTopDown().filter { it.extension == "kt" }.toList() +
                File("src/main/java/eg/masroof/di/BackfillModule.kt") + File("src/main/java/eg/masroof/ingest/RepeatGuard.kt")
        assertTrue(files.isNotEmpty())
        files.forEach { assertFalse("${it.name} uses the session keyset", it.readText().contains("TinkKeystore")) }
    }

    @Test
    fun `REQ-SMS-102 after the session keyset is destroyed a deleted notification still blocks its backfilled SMS`() =
        runTest {
            // the memory's key is the phone's own: a logout, a session clear or TinkKeystore.reset() only touch another keyset
            val local = inMemoryAead()
            // the session keyset (TinkKeystore) is a different key: an injected equivalent is created, used, and destroyed here
            var sessionDestroyed = 0
            val session = RecoveringAead(build = { inMemoryAead() }, destroy = { sessionDestroyed++ })
            session.encrypt(byteArrayOf(1), byteArrayOf())
            val store = BackfillStore(prefs("r4-session"), local)
            val t = BACKFILL_NOW - 20 * BACKFILL_DAY
            val settings = MemorySettings()

            fun pipelineAt(c: Clock) =
                SmsPipeline(
                    db,
                    settings,
                    MemoryNotifications(),
                    c,
                    gate,
                    FixedKey,
                    eraseLock = lock,
                    answers = FakeAnswerPrefs(),
                    memory = store,
                )
            pipelineAt(Clock.fixed(Instant.ofEpochMilli(t), ZoneOffset.UTC))
                .accept(IncomingMessage(CaptureSource.NOTIFICATION, NotificationApps.INSTAPAY_CANDIDATE, R4_PUSH, t))
            db.expenses().delete(
                db
                    .expenses()
                    .all()
                    .single()
                    .id,
            )
            db.captured().all().forEach { db.captured().delete(it.localId) }
            session.reset() // what a logout, a session clear or the server forget step does to the session keyset
            assertEquals(1, sessionDestroyed)
            store.save(BackfillState(BackfillPhase.RUNNING, "D90", BACKFILL_NOW - 90 * BACKFILL_DAY, BACKFILL_NOW + 1))
            SmsBackfill(
                db,
                pipelineAt(clock),
                FakeInbox(listOf(sms(1, INSTANT_IN, sender = "BDC", date = t + 3_000))),
                store,
                settings,
                FixedKey,
                gate,
                clock,
                eraseLock = lock,
            ).run()
            assertTrue("the SMS of the deleted notification made no entry", db.expenses().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-103 Erase all data empties the memory and destroys its key`() =
        runTest {
            WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
            var destroyed = 0
            val aead = RecoveringAead(build = { inMemoryAead() }, destroy = { destroyed++ })
            val store = BackfillStore(prefs("r4-erase"), aead)
            store.mark("id", BACKFILL_NOW)
            store.markSignature("sig", BACKFILL_NOW, BACKFILL_NOW)
            assertTrue(store.handled("id", BACKFILL_NOW))
            RoomDataWiper(
                db,
                NoInsightsCache,
                WorkManager.getInstance(context),
                Dispatchers.Unconfined,
                gate = gate,
                eraseLock = lock,
                backfillReset =
                    BackfillReset {
                        store.reset()
                        aead.reset()
                    },
            ).wipe()
            assertFalse(store.handled("id", BACKFILL_NOW))
            assertFalse(store.seenSignature("sig", 0L, Long.MAX_VALUE, BACKFILL_NOW))
            assertTrue(prefs("r4-erase").all.isEmpty())
            assertEquals("the key was destroyed", 1, destroyed)
            try {
                aead.encrypt(byteArrayOf(1), byteArrayOf())
            } catch (e: GeneralSecurityException) {
                fail("a new key is made at the next use: $e")
            }
        }
}
