package eg.masroof.backup

import eg.masroof.data.backup.BackupInput
import eg.masroof.data.backup.BackupWriter
import eg.masroof.data.backup.PassphraseKdf
import eg.masroof.data.backup.PassphraseProblem
import eg.masroof.data.backup.PassphraseRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** The temp files the writer stages rows in (STORY-183 round 2): what is in them, who can read them, and when they are gone. */
class BackupStagingTest {
    @get:Rule val folder = TemporaryFolder()

    private val marker = "MARKER-NOTES-9f3a-private"
    private val rows = (0 until 50).map { mapOf("id" to "e$it", "notes" to "$marker $it") }

    private fun input() = BackupInput(fastHeader(), PassphraseKdf.deriveKey("correct horse battery", fastHeader()))

    private fun db() = FakeBackupDb(mapOf("expense" to rows, "category" to listOf(mapOf("id" to "c1", "nameEn" to "Food"))))

    private fun staged(dir: File) = dir.listFiles().orEmpty().filter { it.name.startsWith("masroof-stage") }

    @Test
    fun `REQ-SEC-066 the temp folder is empty after a successful backup`() {
        val dir = folder.newFolder("stage")
        runBlocking { BackupWriter(environment(db = db(), tempDir = dir)).write(ByteArrayOutputStream(), input()) }
        assertEquals(emptyList<File>(), staged(dir))
    }

    @Test
    fun `REQ-SEC-066 the temp folder is empty after a provider failure`() {
        val dir = folder.newFolder("stage")
        val failing =
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("ENOSPC")
            }
        try {
            runBlocking { BackupWriter(environment(db = db(), tempDir = dir)).write(failing, input()) }
            fail("provider failure")
        } catch (_: IOException) {
            // expected
        }
        assertEquals(emptyList<File>(), staged(dir))
    }

    @Test
    fun `REQ-SEC-066 the temp folder is empty after a cancel`() {
        val dir = folder.newFolder("stage")
        val database = db()
        runBlocking {
            val job =
                launch(Dispatchers.Default) {
                    BackupWriter(environment(db = database, tempDir = dir)).write(ByteArrayOutputStream(), input()) { this@launch.cancel() }
                }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertEquals(emptyList<File>(), staged(dir))
    }

    @Test
    fun `REQ-SEC-066 the staged bytes are encrypted - a known notes marker is not in any temp file while the snapshot runs`() {
        val dir = folder.newFolder("stage")
        val database = db()
        var seen = 0
        database.onSnapshotEnd = {
            val files = staged(dir)
            assertTrue("rows were staged", files.isNotEmpty())
            for (f in files) {
                seen += f.length().toInt()
                assertFalse("plaintext in ${f.name}", String(f.readBytes(), Charsets.ISO_8859_1).contains("MARKER-NOTES"))
                assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("Food"))
            }
        }
        runBlocking { BackupWriter(environment(db = database, tempDir = dir)).write(ByteArrayOutputStream(), input()) }
        assertTrue(seen > 0)
    }

    @Test
    fun `REQ-SEC-066 a temp file with one flipped byte fails the backup and nothing at all reaches the provider`() {
        val dir = folder.newFolder("stage")
        val database = db()
        database.onSnapshotEnd = {
            val f = staged(dir).first()
            val bytes = f.readBytes()
            bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 0x40).toByte()
            f.writeBytes(bytes)
        }
        val provider = ByteArrayOutputStream()
        try {
            runBlocking { BackupWriter(environment(db = database, tempDir = dir)).write(provider, input()) }
            fail("tampered temp file")
        } catch (_: IOException) {
            // expected
        }
        assertEquals("nothing was written to the provider", 0, provider.size())
        assertEquals(emptyList<File>(), staged(dir))
    }

    @Test
    fun `REQ-SEC-066 two temp files swapped with each other fail the backup (the table name is the associated data)`() {
        val dir = folder.newFolder("stage")
        val database = db()
        database.onSnapshotEnd = {
            val files = staged(dir).sortedBy { it.name }
            val a = files[0].readBytes()
            files[0].writeBytes(files[1].readBytes())
            files[1].writeBytes(a)
        }
        val provider = ByteArrayOutputStream()
        try {
            runBlocking { BackupWriter(environment(db = database, tempDir = dir)).write(provider, input()) }
            fail("swapped temp files")
        } catch (_: IOException) {
            // expected
        }
        assertEquals(0, provider.size())
    }

    @Test
    fun `REQ-SEC-067 a passphrase of only invisible characters is refused, visible ones around them still count`() {
        assertEquals(PassphraseProblem.Empty, PassphraseRules.check("​".repeat(12), "​".repeat(12)))
        assertEquals(PassphraseProblem.Empty, PassphraseRules.check("﻿".repeat(12), "﻿".repeat(12)))
        assertEquals(PassphraseProblem.Empty, PassphraseRules.check("‍ ⁠ \u0007".repeat(4), "‍ ⁠ \u0007".repeat(4)))
        assertNull(PassphraseRules.check("correct horse battery", "correct horse battery"))
        // invisible characters do not count towards the 12
        assertEquals(PassphraseProblem.TooShort(5), PassphraseRules.check("abc​​de" + "​".repeat(10), "x"))
    }
}
