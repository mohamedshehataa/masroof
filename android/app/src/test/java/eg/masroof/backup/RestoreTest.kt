package eg.masroof.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.backup.BackupFormat
import eg.masroof.data.backup.BackupHeader
import eg.masroof.data.backup.BackupPrefsInventory
import eg.masroof.data.backup.RestoreException
import eg.masroof.data.backup.RestoreFailure
import eg.masroof.data.backup.RestoreLimits
import eg.masroof.data.backup.RestoreSettingKeys
import eg.masroof.data.backup.RoomRestoreDatabase
import eg.masroof.ingest.LocalIdHasher
import eg.masroof.ingest.parse.IncomingMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreTest {
    private lateinit var context: Context
    private lateinit var source: Phone
    private lateinit var target: Phone
    private lateinit var file: ByteArray

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        source = Phone(context, "source")
        fillSource(source)
        file = sourceBackup(source)
        target = Phone(context, "target", seeded = true)
        // The wiped (or new) phone is not empty: it holds what a fresh install seeds and one entry that must go.
        insertRow(
            target.db,
            "expense",
            "id" to "old",
            "amountMinor" to 1L,
            "currency" to "EGP",
            "occurredAtMillis" to T0,
            "pending" to false,
            "failed" to false,
        )
        File(target.receipts, "old.bin").writeBytes(byteArrayOf(1, 2, 3))
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun stage(
        p: Phone = target,
        bytes: ByteArray = file,
        restorer: eg.masroof.data.backup.BackupRestorer = p.restorer(),
    ): Pair<eg.masroof.data.backup.BackupRestorer, eg.masroof.data.backup.StagedRestore> {
        val header = restorer.checkHeader(ByteArrayInputStream(bytes))
        return restorer to runBlocking { restorer.stage({ ByteArrayInputStream(bytes) }, bytes.size.toLong(), header, RESTORE_PASS) }
    }

    // ---- check 1 and 2: header only ------------------------------------------------------------------------------------------

    @Test
    fun `REQ-SEC-068 check 1 a file that is not a backup is refused before anything is read`() {
        expectRefused(target, ByteArray(500) { it.toByte() }, RestoreFailure.NOT_A_BACKUP, 1)
        expectRefused(target, ByteArray(3), RestoreFailure.NOT_A_BACKUP, 1)
    }

    @Test
    fun `REQ-SEC-068 check 1 a newer format version says to update the app`() {
        val newer = BackupHeader(2, 1, 64, 1, 1, ByteArray(16), 1, 0)
        expectRefused(target, newer.encode() + ByteArray(100), RestoreFailure.NEWER_VERSION, 1)
    }

    @Test
    fun `REQ-SEC-068 check 2 a KDF of 4 GiB and 1000 passes is refused before any key is derived`() {
        val bomb = BackupHeader(1, 1, 4L * 1024 * 1024, 1000, 1, ByteArray(16), 1, 0)
        val started = System.nanoTime()
        expectRefused(target, bomb.encode() + ByteArray(100), RestoreFailure.CANNOT_OPEN, 2, limits = RestoreLimits())
        assertTrue("no key derivation happened", (System.nanoTime() - started) < 2_000_000_000L)
        for (h in listOf(
            BackupHeader(1, 1, 18 * 1024, 3, 1, ByteArray(16), 1, 0),
            BackupHeader(1, 1, 257 * 1024, 3, 1, ByteArray(16), 1, 0),
            BackupHeader(1, 1, 64 * 1024, 0, 1, ByteArray(16), 1, 0),
            BackupHeader(1, 1, 64 * 1024, 11, 1, ByteArray(16), 1, 0),
            BackupHeader(1, 1, 64 * 1024, 3, 0, ByteArray(16), 1, 0),
            BackupHeader(1, 1, 64 * 1024, 3, 5, ByteArray(16), 1, 0),
        )) {
            expectRefused(target, h.encode() + ByteArray(100), RestoreFailure.CANNOT_OPEN, 2, limits = RestoreLimits())
        }
    }

    @Test
    fun `REQ-SEC-068 cancel at the picker or the passphrase reads nothing beyond the header`() {
        var read = 0
        val counting =
            object : java.io.FilterInputStream(ByteArrayInputStream(file)) {
                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = super.read(b, off, len).also { if (it > 0) read += it }

                override fun read(): Int = super.read().also { if (it >= 0) read++ }
            }
        target.restorer().checkHeader(counting)
        assertTrue("only the 39 header bytes were read", read <= BackupHeader.SIZE)
    }

    // ---- check 3: decryption -------------------------------------------------------------------------------------------------

    @Test
    fun `REQ-SEC-068 check 3 a wrong passphrase gives one message and changes nothing`() {
        expectRefused(target, file, RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED, 3, pass = "another long passphrase")
    }

    @Test
    fun `REQ-SEC-068 check 3 a flipped byte, a cut tail and an edited header are the same message`() {
        val flipped = file.copyOf().also { it[file.size / 2] = (it[file.size / 2] + 1).toByte() }
        expectRefused(target, flipped, RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED, 3)
        expectRefused(target, file.copyOf(file.size - 20), RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED, 3)
        val edited = file.copyOf().also { it[20] = (it[20] + 1).toByte() } // a salt byte
        expectRefused(target, edited, RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED, 3)
        val reserved = file.copyOf().also { it[BackupHeader.SIZE - 1] = 1 }
        expectRefused(target, reserved, RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED, 3)
    }

    @Test
    fun `REQ-SEC-068 check 3 the stream is read to its end, so a damaged last segment is refused`() {
        // damage inside the last bytes: the ZIP central directory is fine, only the final AEAD tag is wrong
        val last = file.copyOf().also { it[file.size - 3] = (it[file.size - 3] + 1).toByte() }
        expectRefused(target, last, RestoreFailure.WRONG_PASSPHRASE_OR_DAMAGED, 3)
    }

    // ---- checks 4 and 5: ZIP ---------------------------------------------------------------------------------------------------

    @Test
    fun `REQ-SEC-068 check 4 an entry that climbs out of the folder is refused`() {
        val crafted = encrypt(zipOf(listOf("../../databases/masroof.db" to ByteArray(10))))
        expectRefused(target, crafted, RestoreFailure.INVALID_CONTENT, 4)
        expectRefused(
            target,
            encrypt(zipOf(listOf("/data/data/eg.masroof/databases/masroof.db" to ByteArray(10)))),
            RestoreFailure.INVALID_CONTENT,
            4,
        )
        expectRefused(target, encrypt(zipOf(listOf("data/../../x" to ByteArray(10)))), RestoreFailure.INVALID_CONTENT, 4)
        expectRefused(target, encrypt(zipOf(listOf("other/file.txt" to ByteArray(10)))), RestoreFailure.INVALID_CONTENT, 4)
        expectRefused(target, encrypt(zipOf(listOf("receipts/..\\x.jpg" to ByteArray(10)))), RestoreFailure.INVALID_CONTENT, 4)
    }

    @Test
    fun `REQ-SEC-068 check 4 a duplicate entry name is refused`() {
        val zip = zipOf(listOf("receipts/aaaa.jpg" to ByteArray(10), "receipts/bbbb.jpg" to ByteArray(10)))
        val patched = String(zip, Charsets.ISO_8859_1).replace("bbbb.jpg", "aaaa.jpg").toByteArray(Charsets.ISO_8859_1)
        expectRefused(target, encrypt(patched), RestoreFailure.INVALID_CONTENT, 4)
    }

    @Test
    fun `REQ-SEC-068 check 5 an entry that inflates to 5 GiB at the best deflate ratio is stopped by the ratio cap while streaming`() {
        val out = ByteArrayOutputStream()
        val header = fastHeader()
        val key =
            eg.masroof.data.backup.PassphraseKdf
                .deriveKey(RESTORE_PASS, header)
        out.write(header.encode())
        val zip =
            ZipOutputStream(
                eg.masroof.data.backup.BackupCipher
                    .encrypt(out, key, header.encode()),
            )
        zip.setLevel(Deflater.BEST_COMPRESSION)
        zip.putNextEntry(ZipEntry("receipts/bomb.bin"))
        val zeros = ByteArray(1 shl 20)
        repeat(5 * 1024) { zip.write(zeros) }
        zip.closeEntry()
        zip.close()
        val bomb = out.toByteArray()
        assertTrue("the crafted file is small (${bomb.size} bytes)", bomb.size < 8_000_000)
        expectRefused(target, bomb, RestoreFailure.INVALID_CONTENT, 5)
        assertTrue("nothing was staged", target.stage.listFiles().isNullOrEmpty())
    }

    @Test
    fun `REQ-SEC-068 check 5 the total uncompressed size is capped while streaming`() {
        val noise = ByteArray(300_000).also { java.util.Random(1).nextBytes(it) }
        val crafted = encrypt(zipOf(listOf("receipts/a.jpg" to noise, "receipts/b.jpg" to noise)))
        expectRefused(target, crafted, RestoreFailure.INVALID_CONTENT, 5, limits = RestoreLimits(minMemoryKiB = 1, maxTotalBytes = 500_000))
    }

    // ---- check 6: JSON ---------------------------------------------------------------------------------------------------------

    private fun mutated(
        entry: String,
        from: String,
        to: String,
    ) = repack(file) { name, bytes -> if (name == entry) bytes.replaceText(from, to) else bytes }

    @Test
    fun `REQ-SEC-068 check 6 a fractional amount is refused`() {
        expectRefused(
            target,
            mutated("data/expenses.jsonl", "\"amountMinor\":12500", "\"amountMinor\":12.5"),
            RestoreFailure.INVALID_CONTENT,
            6,
        )
        expectRefused(
            target,
            mutated("data/expenses.jsonl", "\"amountMinor\":12500", "\"amountMinor\":\"12500\""),
            RestoreFailure.INVALID_CONTENT,
            6,
        )
    }

    @Test
    fun `REQ-SEC-068 check 6 a currency that is not ISO-4217 is refused`() {
        expectRefused(
            target,
            mutated("data/expenses.jsonl", "\"currency\":\"EGP\"", "\"currency\":\"EGPX\""),
            RestoreFailure.INVALID_CONTENT,
            6,
        )
    }

    @Test
    fun `REQ-SEC-068 check 6 an instant before year 0 or after year 9999 is refused`() {
        expectRefused(
            target,
            mutated("data/expenses.jsonl", "\"occurredAtMillis\":1793000000000", "\"occurredAtMillis\":-70000000000000"),
            RestoreFailure.INVALID_CONTENT,
            6,
        )
        expectRefused(
            target,
            mutated("data/expenses.jsonl", "\"occurredAtMillis\":1793000000000", "\"occurredAtMillis\":300000000000000"),
            RestoreFailure.INVALID_CONTENT,
            6,
        )
    }

    @Test
    fun `REQ-SEC-068 check 6 an unknown enum value is refused, TRANSFER_IN is known`() {
        expectRefused(
            target,
            mutated("data/expenses.jsonl", "\"type\":\"EXPENSE\"", "\"type\":\"BOGUS\""),
            RestoreFailure.INVALID_CONTENT,
            6,
        )
        val (_, staged) = stage()
        assertEquals(2L, staged.info.entries) // e3 is deleted; the TRANSFER_IN entry passed
    }

    @Test
    fun `REQ-SEC-068 check 6 a foreign key that does not resolve is refused and a budget keeps its category`() {
        expectRefused(
            target,
            mutated("data/budgets.jsonl", "\"categoryId\":\"c1\"", "\"categoryId\":\"ghost\""),
            RestoreFailure.INVALID_CONTENT,
            6,
        )
        expectRefused(
            target,
            mutated("data/receipts.jsonl", "\"expenseId\":\"e1\"", "\"expenseId\":\"ghost\""),
            RestoreFailure.INVALID_CONTENT,
            6,
        )
    }

    @Test
    fun `REQ-SEC-068 check 6 a row with a missing or extra column, a bad boolean and a null in a required column is refused`() {
        expectRefused(
            target,
            mutated("data/members.jsonl", "\"displayName\":\"Me\"", "\"displayNameX\":\"Me\""),
            RestoreFailure.INVALID_CONTENT,
            6,
        )
        expectRefused(target, mutated("data/expenses.jsonl", "\"pending\":0", "\"pending\":7"), RestoreFailure.INVALID_CONTENT, 6)
        expectRefused(
            target,
            mutated("data/expenses.jsonl", "\"currency\":\"EGP\"", "\"currency\":null"),
            RestoreFailure.INVALID_CONTENT,
            6,
        )
    }

    @Test
    fun `REQ-SEC-068 check 6 a data schema older or newer than this app is refused, never half mapped`() {
        expectRefused(target, mutated("manifest.json", "\"dataSchemaVersion\":10", "\"dataSchemaVersion\":9"), RestoreFailure.TOO_OLD, 6)
        expectRefused(
            target,
            mutated("manifest.json", "\"dataSchemaVersion\":10", "\"dataSchemaVersion\":11"),
            RestoreFailure.NEWER_VERSION,
            6,
        )
    }

    @Test
    fun `REQ-SEC-068 check 6 a settings value of the wrong type is refused and keys that are not on the IN list are ignored`() {
        val bad =
            repack(file) { n, b ->
                if (n ==
                    "data/settings.json"
                ) {
                    b.replaceText("\"type\":\"bool\",\"value\":true", "\"type\":\"string\",\"value\":\"x\"")
                } else {
                    b
                }
            }
        expectRefused(target, bad, RestoreFailure.INVALID_CONTENT, 6)
        val text =
            """{"formatVersion":1,"stores":{"shared:masroof_session":{"session":{"type":"string","value":"tok"}},
            |"datastore:settings":{"server_url":{"type":"string","value":"https://evil"},"month_start_day":{"type":"int","value":7}}}}
            """.trimMargin()
        val parsed = RestoreSettingKeys.parse(text)
        assertEquals(setOf("datastore:settings"), parsed.stores.keys)
        assertEquals(mapOf("month_start_day" to 7), parsed.stores.getValue("datastore:settings"))
    }

    @Test
    fun `REQ-SEC-068 check 6 a local-id key that is not 32 bytes is refused`() {
        val bad = repack(file) { n, b -> if (n == "keys/local-id.key") ByteArray(31) else b }
        expectRefused(target, bad, RestoreFailure.INVALID_CONTENT, 6)
    }

    // ---- check 7: receipts -----------------------------------------------------------------------------------------------------

    @Test
    fun `REQ-SEC-068 check 7 a receipt whose hash differs from the manifest is refused`() {
        val bad = repack(file) { n, b -> if (n == "receipts/r1.jpg") b.copyOf().also { it[5] = (it[5] + 1).toByte() } else b }
        expectRefused(target, bad, RestoreFailure.INVALID_CONTENT, 7)
        val short = repack(file) { n, b -> if (n == "receipts/r1.jpg") b.copyOf(b.size - 1) else b }
        expectRefused(target, short, RestoreFailure.INVALID_CONTENT, 7)
    }

    @Test
    fun `REQ-SEC-068 the checks run in order, so an earlier failure wins`() {
        // a file that has a bad name (check 4) AND a bad amount (check 6) is reported as check 4
        val both =
            repack(file) { n, b -> if (n == "data/expenses.jsonl") b.replaceText("\"amountMinor\":12500", "\"amountMinor\":12.5") else b }
        val extra = BackupTestDecryptor.decrypt(both, RESTORE_PASS)
        val crafted = encrypt(zipOf(extra.names.map { it to extra.entries.getValue(it) } + ("evil.txt" to ByteArray(1))))
        expectRefused(target, crafted, RestoreFailure.INVALID_CONTENT, 4)
    }

    // ---- REQ-OPS-178: confirmation, replace, round trip -----------------------------------------------------------------------

    @Test
    fun `REQ-OPS-178 the confirmation carries the backup's date and counts and what it replaces`() {
        val (_, staged) = stage()
        assertEquals(T0 + 1000, staged.info.createdAtMillis)
        assertEquals(2L, staged.info.entries)
        assertEquals(1, staged.info.receipts)
        assertEquals(2L, staged.info.budgets)
        assertEquals(1L, staged.info.currentEntries)
    }

    @Test
    fun `REQ-OPS-178 cancel at the confirmation deletes the staging directory and changes nothing`() {
        val rows = target.dump()
        val files = target.receiptHashes()
        val (restorer, staged) = stage()
        assertTrue(target.stage.exists())
        restorer.discardSession(staged.gen)
        target.assertUntouched(rows, files)
    }

    @Test
    fun `REQ-OPS-178 round trip every table is identical column by column and every receipt hash matches`() {
        val (restorer, staged) = stage()
        runBlocking { restorer.apply(staged) }
        assertEquals(source.dump(), target.dump())
        assertEquals(mapOf("r1.jpg" to sha256Of(RECEIPT_BYTES)), target.receiptHashes())
        assertFalse(target.stage.exists())
        assertFalse(target.journal.exists())
        assertFalse(File(target.receipts.parentFile, "receipts.old").exists())
        // 20 tables, the EPIC-18 ones and the budgets included, and a restored receipt row without a file stays as it was
        assertEquals(20, target.dump().size)
        assertEquals(2, target.dump().getValue("budget").size)
        assertEquals(2, target.dump().getValue("receipt").size)
        assertTrue(target.dump().getValue("expense").any { it["type"] == "TRANSFER_IN" })
        // the settings of the IN lists were handed over typed; the key is the backup's
        assertEquals(1, target.applied.size)
        assertEquals(
            SOURCE_SETTINGS.keys,
            target.applied
                .single()
                .stores.keys,
        )
        assertEquals(
            5,
            target.applied
                .single()
                .stores
                .getValue("datastore:settings")["month_start_day"],
        )
        assertArrayEquals(LOCAL_KEY, target.installedKey)
    }

    @Test
    fun `REQ-OPS-178 a budget keeps its category and the unmarked total budget is not duplicated`() {
        val (restorer, staged) = stage()
        runBlocking { restorer.apply(staged) }
        val budgets = target.dump().getValue("budget").associate { it["id"] to it["categoryId"] }
        assertEquals(mapOf("b1" to "c1", "b2" to null), budgets)
    }

    @Test
    fun `REQ-OPS-178 an SMS recorded before the backup is not recorded twice after the restore`() {
        val (restorer, staged) = stage()
        runBlocking { restorer.apply(staged) }
        val message = IncomingMessage(eg.masroof.ingest.parse.CaptureSource.SMS, "CIB", "Purchase EGP 125.00 at Shop", T0)
        // the id the source phone gave the message is the id this phone computes now, because the HMAC key came back
        val stored = LocalIdHasher.id(LOCAL_KEY, message)
        insertRow(
            source.db,
            "expense_source",
            "id" to "s9",
            "expenseId" to "e2",
            "channel" to "SMS",
            "localSourceId" to stored,
            "receivedAtMillis" to T0,
        )
        assertEquals(stored, LocalIdHasher.id(target.installedKey!!, message))
        // the unique index on the restored table drops a second record of a message the backup already had
        val db = target.db.openHelper.writableDatabase
        val cv =
            android.content.ContentValues().apply {
                put("id", "s-new")
                put("expenseId", "e2")
                put("channel", "SMS")
                put("localSourceId", "local-id-1")
                put("receivedAtMillis", T0)
            }
        assertEquals(-1L, db.insert("expense_source", android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE, cv))
    }

    @Test
    fun `REQ-OPS-178 the settings keys on the IN lists have a type and nothing else`() {
        val inKeys = BackupPrefsInventory.DATASTORE_KEYS + BackupPrefsInventory.SHARED_KEYS.mapKeys { "shared:" + it.key }
        assertEquals(inKeys, RestoreSettingKeys.TYPES.mapValues { it.value.keys })
    }

    @Test
    fun `REQ-SEC-068 every foreign key of the live schema points at an id column and all 20 tables are read`() {
        val schema = RoomRestoreDatabase(target.db).schema()
        assertEquals(20, schema.tables.size)
        assertTrue(
            schema.tables.values
                .flatMap { it.foreignKeys }
                .all { it.parentColumn == "id" },
        )
        assertTrue(
            schema.tables.values
                .flatMap { it.foreignKeys }
                .isNotEmpty(),
        )
        assertTrue(schema.tables.values.all { t -> t.columns.any { it.name == "id" } })
    }

    // ---- REQ-OPS-179: kill hooks, storage ----------------------------------------------------------------------------------------

    @Test
    fun `REQ-OPS-179 not enough free space refuses with the size needed and changes nothing`() {
        val big = source
        insertRow(
            big.db,
            "receipt",
            "id" to "r3",
            "expenseId" to null,
            "fileName" to "big.bin",
            "mimeType" to "image/jpeg",
            "sizeBytes" to 40_000_000,
            "sha256" to "cd".repeat(32),
            "createdAtMillis" to T0,
        )
        val rows = target.dump()
        val files = target.receiptHashes()
        try {
            val restorer = target.restorer(freeSpace = { 1_000L })
            val header = restorer.checkHeader(ByteArrayInputStream(file))
            runBlocking { restorer.stage({ ByteArrayInputStream(file) }, file.size.toLong(), header, RESTORE_PASS) }
            fail("expected storage full")
        } catch (e: RestoreException) {
            assertEquals(RestoreFailure.STORAGE_FULL, e.failure)
            assertTrue("needs at least the 8 MB margin", e.neededMb >= 8)
        }
        target.assertUntouched(rows, files)
    }

    @Test
    fun `REQ-OPS-179 a disk that fills while receipts are copied refuses and changes nothing`() {
        var written = 0
        val restorer =
            target.restorer(
                openStage = { f ->
                    object : java.io.FilterOutputStream(java.io.FileOutputStream(f)) {
                        override fun write(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ) {
                            written += len
                            if (f.name.startsWith("receipt-in")) throw IOException("No space left on device")
                            out.write(b, off, len)
                        }
                    }
                },
            )
        expectRefused(target, file, RestoreFailure.STORAGE_FULL, 0, restorer = restorer)
        assertTrue(written > 0)
    }

    @Test
    fun `REQ-SEC-068 the restore logs no content and never prints the passphrase`() {
        val sources =
            File("src/main/java/eg/masroof/data/backup").listFiles { f ->
                f.name.startsWith("Restore") ||
                    f.name == "BackupRestorer.kt"
            }
        assertNotNull(sources)
        for (f in sources!!) assertFalse("${f.name} logs", Regex("\\b(Log|Timber)\\.|println\\(").containsMatchIn(f.readText()))
        assertEquals(BackupFormat.KEY_BYTES, 32)
    }
}
