package eg.masroof.backup

import eg.masroof.data.backup.BackupFailure
import eg.masroof.data.backup.BackupInput
import eg.masroof.data.backup.BackupReceiptRef
import eg.masroof.data.backup.BackupSourceChangedException
import eg.masroof.data.backup.BackupWriter
import eg.masroof.data.backup.PassphraseKdf
import eg.masroof.data.backup.ReceiptFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.Random

/** A provider that accepts [limit] bytes and then fails, like a full disk or a Drive that went offline. */
private class FailingSink(
    private val limit: Int,
    private val message: String,
) : OutputStream() {
    var written = 0
    var closed = false

    override fun write(b: Int) {
        if (written + 1 > limit) throw IOException(message)
        written++
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        if (written + len > limit) throw IOException(message)
        written += len
    }

    override fun close() {
        closed = true
    }
}

class BackupWriterTest {
    private val receipt = ByteArray(3_000_000).also { Random(3).nextBytes(it) }
    private val ref = BackupReceiptRef("r1", "r1.jpg", receipt.size.toLong())

    private fun env(
        files: ReceiptFiles =
            filesOf(
                "r1.jpg" to receipt,
            ),
    ) = environment(db = FakeBackupDb(receiptRefs = listOf(ref)), files = files)

    private fun input() = BackupInput(fastHeader(), PassphraseKdf.deriveKey("correct horse battery", fastHeader()))

    @Test
    fun `REQ-OPS-177 a provider that fails mid-write makes the backup fail and never returns a result`() {
        val sink = FailingSink(limit = 1_500_000, message = "ENOSPC (No space left on device)")
        try {
            runBlocking { BackupWriter(env()).write(sink, input()) }
            fail("a partial file must never be reported as a backup")
        } catch (e: IOException) {
            assertEquals(BackupFailure.STORAGE_FULL, BackupFailure.classify(e))
        }
        assertTrue("the provider stream is closed after the failure", sink.closed)
    }

    @Test
    fun `REQ-OPS-177 a stream that fails when it is closed (the last segment) is a failed backup too`() {
        val sink =
            object : ByteArrayOutputStream() {
                override fun close() = throw IOException("Drive offline")
            }
        try {
            runBlocking { BackupWriter(env()).write(sink, input()) }
            fail("close failure must surface")
        } catch (e: IOException) {
            assertEquals(BackupFailure.OTHER, BackupFailure.classify(e))
        }
    }

    @Test
    fun `REQ-OPS-177 cancelling stops the write with a cancellation and closes the sink`() {
        val sink = FailingSink(limit = Int.MAX_VALUE, message = "")
        runBlocking {
            val job =
                launch(Dispatchers.Default) {
                    BackupWriter(env()).write(sink, input()) { percent -> if (percent >= 5) this@launch.cancel() }
                }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertTrue(sink.closed)
        assertTrue("cancel was noticed before the whole file was written", sink.written < 3_000_000)
    }

    @Test
    fun `REQ-OPS-176 a receipt that changes after it was measured fails the backup instead of writing a lying manifest`() {
        var opens = 0
        val files =
            ReceiptFiles {
                opens++
                ByteArrayInputStream(if (opens == 1) receipt else receipt.copyOf().also { it[10] = 99 })
            }
        try {
            runBlocking { BackupWriter(env(files)).write(ByteArrayOutputStream(), input()) }
            fail("changed receipt")
        } catch (e: BackupSourceChangedException) {
            assertEquals(BackupFailure.RECEIPT_CHANGED, BackupFailure.classify(e))
        }
    }

    @Test
    fun `REQ-OPS-176 a receipt that vanishes between measuring and copying fails the backup`() {
        var opens = 0
        val files =
            ReceiptFiles {
                opens++
                if (opens == 1) ByteArrayInputStream(receipt) else throw FileNotFoundException()
            }
        try {
            runBlocking { BackupWriter(env(files)).write(ByteArrayOutputStream(), input()) }
            fail("vanished receipt")
        } catch (_: BackupSourceChangedException) {
            // expected
        }
    }

    @Test
    fun `REQ-OPS-177 progress rises to 100 once and a successful run reports sizes`() {
        val seen = mutableListOf<Int>()
        val out = ByteArrayOutputStream()
        val result = runBlocking { BackupWriter(env()).write(out, input()) { seen += it } }
        assertEquals(100, seen.last())
        assertEquals(seen.sorted(), seen)
        assertTrue(seen.dropLast(1).all { it < 100 })
        assertEquals(out.size().toLong(), result.fileBytes)
        assertEquals(1, result.receiptsStored)
        assertFalse(seen.isEmpty())
    }

    @Test
    fun `REQ-OPS-177 failure reasons are codes, never the provider's own message`() {
        assertEquals(BackupFailure.STORAGE_FULL, BackupFailure.classify(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(BackupFailure.CANNOT_OPEN, BackupFailure.classify(FileNotFoundException("content://x/secret-name.masroof")))
        assertEquals(BackupFailure.OTHER, BackupFailure.classify(IOException("boom")))
        assertEquals(BackupFailure.OTHER, BackupFailure.classify(IllegalStateException("x")))
    }
}
