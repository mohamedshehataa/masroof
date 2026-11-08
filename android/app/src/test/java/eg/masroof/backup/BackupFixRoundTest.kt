package eg.masroof.backup

import eg.masroof.data.backup.BackupInput
import eg.masroof.data.backup.BackupReceiptRef
import eg.masroof.data.backup.BackupWriter
import eg.masroof.data.backup.DirectoryReceiptFiles
import eg.masroof.data.backup.PassphraseKdf
import eg.masroof.data.backup.PassphraseProblem
import eg.masroof.data.backup.PassphraseRules
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/** Round 1 fixes of STORY-183: each test is the repro of one verify/security finding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupFixRoundTest {
    private fun input() = BackupInput(fastHeader(), PassphraseKdf.deriveKey("correct horse battery", fastHeader()))

    @Test
    fun `REQ-SEC-066 the database access builds no SQL from string templates (semgrep masroof-no-template-sql-kotlin)`() {
        val text = File("src/main/java/eg/masroof/data/backup/RoomBackupDatabase.kt").readText()
        val offenders = Regex(""""\s*(SELECT|INSERT|UPDATE|DELETE)[^"\n]*\$""").findAll(text).map { it.value }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `REQ-OPS-177 the snapshot is closed before the first byte goes to the provider so a slow provider cannot block writers`() {
        // About 3 MiB after compression: more than one AEAD segment, so the provider is written to while rows are still being read.
        val noise = java.util.Random(1)
        val rows =
            (0 until 30_000).map {
                mapOf(
                    "id" to "e$it",
                    "notes" to ByteArray(100).also { b -> noise.nextBytes(b) }.joinToString("") { "%02x".format(it) },
                )
            }
        val db = FakeBackupDb(mapOf("expense" to rows))
        var snapshotOpenWhileWriting = false
        val slowProvider =
            object : ByteArrayOutputStream() {
                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    if (db.inSnapshot) snapshotOpenWhileWriting = true
                    super.write(b, off, len)
                }
            }
        runBlocking { BackupWriter(environment(db = db)).write(slowProvider, input()) }
        assertTrue("the provider received data", slowProvider.size() > 0)
        assertFalse("the database snapshot (the write lock) was still open while the provider was written", snapshotOpenWhileWriting)
        assertTrue(db.snapshots >= 1)
    }

    @Test
    fun `REQ-OPS-176 a receipt file name with a NUL character is listed as missing and does not fail the backup`() {
        val dir =
            File.createTempFile("rcpt", "dir").also {
                it.delete()
                it.mkdirs()
            }
        try {
            val ref = BackupReceiptRef("r1", "bad\u0000name.jpg", 3)
            val env = environment(db = FakeBackupDb(receiptRefs = listOf(ref)), files = DirectoryReceiptFiles(dir))
            val (bytes, result) = writeBackup(env)
            assertEquals(1, result.receiptsMissing)
            val plain = BackupTestDecryptor.decrypt(bytes, "correct horse battery")
            assertTrue(JSONObject(plain.text("manifest.json")).getJSONArray("receipts").getJSONObject(0).getBoolean("missing"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `REQ-OPS-176 two receipt ids that clean to the same ZIP name both get a distinct entry`() {
        val refs = listOf(BackupReceiptRef("r/1", "a.jpg", 3), BackupReceiptRef("r1", "b.jpg", 3))
        val files = filesOf("a.jpg" to byteArrayOf(1, 2, 3), "b.jpg" to byteArrayOf(4, 5, 6))
        val (bytes, result) = writeBackup(environment(db = FakeBackupDb(receiptRefs = refs), files = files))
        assertEquals(2, result.receiptsStored)
        val plain = BackupTestDecryptor.decrypt(bytes, "correct horse battery")
        val entries = plain.names.filter { it.startsWith("receipts/") }
        assertEquals(2, entries.toSet().size)
        val manifest = JSONObject(plain.text("manifest.json")).getJSONArray("receipts")
        val byId = (0 until 2).associate { manifest.getJSONObject(it).getString("id") to manifest.getJSONObject(it).getString("entry") }
        assertEquals(listOf(1, 2, 3), plain.entries.getValue(byId.getValue("r/1")).map { it.toInt() })
        assertEquals(listOf(4, 5, 6), plain.entries.getValue(byId.getValue("r1")).map { it.toInt() })
    }

    @Test
    fun `REQ-OPS-176 a lone surrogate in a text column is escaped so the round trip is exact, not written as a question mark`() {
        val odd = "note \uD800 end \uDC00 and 😀 pair"
        val db = FakeBackupDb(mapOf("expense" to listOf(mapOf("id" to "e1", "notes" to odd))))
        val (bytes, _) = writeBackup(environment(db = db))
        val plain = BackupTestDecryptor.decrypt(bytes, "correct horse battery")
        val back = JSONObject(plain.text("data/expenses.jsonl").lines().first()).getString("notes")
        assertEquals(odd, back)
    }

    @Test
    fun `REQ-SEC-067 a passphrase of only whitespace is refused even when it has 12 characters`() {
        assertEquals(PassphraseProblem.Empty, PassphraseRules.check(" ".repeat(12), " ".repeat(12)))
        assertEquals(PassphraseProblem.Empty, PassphraseRules.check("\t  ".repeat(5), "\t  ".repeat(5)))
        assertEquals(null, PassphraseRules.check("a" + " ".repeat(11), "a" + " ".repeat(11)))
    }

    @Test
    fun `REQ-OPS-176 the column set of every included table is pinned so a new column must be reviewed against the backup`() {
        val pinned = PinnedColumns.expected
        val actual = PinnedColumns.fromSchema()
        assertEquals(pinned.keys, actual.keys)
        for ((table, columns) in pinned) assertEquals("columns of $table", columns, actual.getValue(table))
    }
}
