package eg.masroof.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import eg.masroof.data.backup.LocalIdKeyStore
import eg.masroof.data.backup.RestoreHooks
import eg.masroof.data.backup.RestoreOp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException

/** The order of the durable operations of one clean restore (design section 6.3), read from the operation log. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreDurabilityOrderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Log : RestoreHooks {
        val events = mutableListOf<String>()
        var armed = false

        override fun before(op: RestoreOp) {
            if (armed) events += "b:" + op.name
        }

        override fun after(op: RestoreOp) {
            if (armed) events += "a:" + op.name
        }

        override fun beforeCommit() {
            if (armed) events += "tx:beforeCommit"
        }
    }

    private fun cleanRun(): List<String> {
        val src = Phone(context, "dur-src")
        val file =
            try {
                fillSource(src)
                sourceBackup(src)
            } finally {
                src.close()
            }
        val p = Phone(context, "dur", seeded = true)
        try {
            val log = Log().also { it.armed = true }
            val r = p.restorer(hooks = log)
            val h = r.checkHeader(ByteArrayInputStream(file))
            val staged = runBlocking { r.stage({ ByteArrayInputStream(file) }, file.size.toLong(), h, RESTORE_PASS) }
            // staged files and folders were fsynced before Confirm: the folder fsyncs of staging are in the log before apply starts
            assertTrue("staging fsyncs before Confirm", log.events.count { it == "b:FSYNC_DIR" } >= 3)
            log.events.clear()
            log.armed = true
            runBlocking { r.apply(staged) }
            return log.events.toList()
        } finally {
            p.close()
        }
    }

    @Test
    fun `REQ-OPS-179 journal writes are fsynced before the next step, the pending slot precedes the commit and the delete is last`() {
        val e = cleanRun()
        // every JOURNAL_WRITE span contains a directory fsync (tmp fsync + atomic rename + directory fsync happen inside it)
        var i = 0
        var writes = 0
        while (i < e.size) {
            if (e[i] == "b:JOURNAL_WRITE") {
                writes++
                val end = e.subList(i, e.size).indexOf("a:JOURNAL_WRITE") + i
                assertTrue("journal write $writes has no directory fsync", e.subList(i, end).contains("b:FSYNC_DIR"))
                i = end
            }
            i++
        }
        assertTrue("pending, promotion and four step records", writes >= 6)
        // 2. the pending write is before the commit; 4. the promotion is after it
        val firstWrite = e.indexOf("b:JOURNAL_WRITE")
        val commit = e.indexOf("b:TX_COMMIT")
        val committed = e.indexOf("a:TX_COMMIT")
        assertTrue(firstWrite in 0 until commit)
        val promotion = e.subList(committed, e.size).indexOf("b:JOURNAL_WRITE") + committed
        assertTrue("the promotion is after the commit", promotion > committed)
        assertTrue("beforeCommit is inside the transaction", e.indexOf("tx:beforeCommit") in commit until committed)
        // 5. each step record comes after its effect
        val renameToR = e.indexOf("a:RENAME_N_TO_R")
        val settings = e.indexOf("a:SETTINGS_APPLY")
        val key = e.indexOf("a:KEY_INSTALL")

        fun nextWrite(after: Int) = e.subList(after, e.size).indexOf("b:JOURNAL_WRITE") + after
        assertTrue(nextWrite(renameToR) > renameToR)
        assertTrue(nextWrite(settings) > settings)
        assertTrue(nextWrite(key) > key)
        assertTrue(
            "SWAPPING is recorded before the first rename",
            e.subList(promotion + 1, e.size).indexOf("b:JOURNAL_WRITE") + promotion + 1 < e.indexOf("b:RENAME_N_TO_R"),
        )
        // 6. the journal delete is last
        val del = e.lastIndexOf("b:JOURNAL_DELETE")
        assertTrue("the journal delete happened", del > 0)
        assertTrue(
            "only its own fsync follows the delete",
            e.subList(del + 1, e.size).all {
                it == "b:FSYNC_DIR" ||
                    it == "a:FSYNC_DIR" ||
                    it == "a:JOURNAL_DELETE"
            },
        )
        assertTrue(
            "the staging and old receipts go before the journal",
            e.lastIndexOf("b:STAGE_DELETE") < del && e.lastIndexOf("b:DELETE_O") < del,
        )
    }
}

/** A reference LocalIdKeyStore over an AEAD, to run the contract (design section 5) until STORY-182 binds the real one. */
class ReferenceKeyStore(
    private val aead: () -> Aead,
    private val storage: MutableMap<String, ByteArray>,
) : LocalIdKeyStore {
    override fun install(key: ByteArray) {
        require(key.size == 32 && key.any { it.toInt() != 0 }) { "not a local-id key" }
        val a = aead()
        val sealed = a.encrypt(key, ByteArray(0))
        val check = a.decrypt(sealed, ByteArray(0))
        try {
            check(check.contentEquals(key)) { "does not read back" }
        } finally {
            check.fill(0)
        }
        storage["k"] = sealed // one atomic replace, after the new copy proved itself
    }

    fun get(): ByteArray? = storage["k"]?.let { aead().decrypt(it, ByteArray(0)) }
}

/** The four clauses of the LocalIdKeyStore contract; the production binding must pass the same ones (STORY-182). */
class LocalIdKeyStoreContractTest {
    private class XorAead(
        var failNext: Boolean = false,
    ) : Aead {
        override fun encrypt(
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray {
            if (failNext) {
                failNext = false
                throw GeneralSecurityException("ProviderException")
            }
            return ByteArray(plaintext.size) { (plaintext[it].toInt() xor 0x5A).toByte() }
        }

        override fun decrypt(
            ciphertext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray {
            if (failNext) {
                failNext = false
                throw GeneralSecurityException("ProviderException")
            }
            return ByteArray(ciphertext.size) { (ciphertext[it].toInt() xor 0x5A).toByte() }
        }
    }

    private val aead = XorAead()
    private val storage = mutableMapOf<String, ByteArray>()
    private val store = ReferenceKeyStore({ aead }, storage)
    private val old = ByteArray(32) { 1 }
    private val fresh = ByteArray(32) { (it + 5).toByte() }

    @Test
    fun `REQ-OPS-178 clause 1 only a 32 byte key that is not all zero is accepted`() {
        store.install(old)
        for (bad in listOf(ByteArray(31) { 1 }, ByteArray(33) { 1 }, ByteArray(32))) {
            try {
                store.install(bad)
                fail("accepted")
            } catch (_: IllegalArgumentException) {
            }
            assertArrayEquals(old, store.get())
        }
    }

    @Test
    fun `REQ-OPS-178 clause 2 and 3 install is idempotent and reads back equal`() {
        store.install(fresh)
        store.install(fresh)
        assertArrayEquals(fresh, store.get())
    }

    @Test
    fun `REQ-OPS-178 clause 4 a transient provider failure never replaces or deletes the stored key`() {
        store.install(old)
        aead.failNext = true
        try {
            store.install(fresh)
            fail("expected the provider failure")
        } catch (_: GeneralSecurityException) {
        }
        assertArrayEquals("the old key is still there", old, store.get())
        aead.failNext = true
        try {
            store.get()
            fail("expected the provider failure on read")
        } catch (_: GeneralSecurityException) {
        }
        assertTrue("a failing read deletes nothing", storage.containsKey("k"))
        assertNotEquals(fresh.toList(), store.get()!!.toList())
        assertFalse(store.get()!!.contentEquals(fresh))
        store.install(fresh)
        assertArrayEquals(fresh, store.get())
    }
}
