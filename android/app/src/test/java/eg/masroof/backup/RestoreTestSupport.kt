package eg.masroof.backup

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import eg.masroof.data.backup.BackupCipher
import eg.masroof.data.backup.BackupHeader
import eg.masroof.data.backup.BackupRestorer
import eg.masroof.data.backup.BackupTables
import eg.masroof.data.backup.DirectoryReceiptFiles
import eg.masroof.data.backup.LocalIdKeyInstaller
import eg.masroof.data.backup.PassphraseKdf
import eg.masroof.data.backup.RestoreEnvironment
import eg.masroof.data.backup.RestoreException
import eg.masroof.data.backup.RestoreFailure
import eg.masroof.data.backup.RestoreHooks
import eg.masroof.data.backup.RestoreLimits
import eg.masroof.data.backup.RestoreSettingsApplier
import eg.masroof.data.backup.RestoreStage
import eg.masroof.data.backup.RestoredSettings
import eg.masroof.data.backup.RoomBackupDatabase
import eg.masroof.data.backup.RoomRestoreDatabase
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.AppDatabaseCallback
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val RESTORE_PASS = "correct horse battery"
const val NOW = 1_793_100_000_000L
const val T0 = 1_793_000_000_000L
val LOCAL_KEY = ByteArray(32) { (it + 1).toByte() }
val RECEIPT_BYTES = ByteArray(3000) { (it * 7).toByte() }

/** Fast KDF settings are below the real bounds, so tests that are not about the bounds lower them. */
val FAST = RestoreLimits(minMemoryKiB = 1)

fun sha256Of(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun insertRow(
    db: AppDatabase,
    table: String,
    vararg values: Pair<String, Any?>,
) {
    val cv = ContentValues()
    for ((k, v) in values) {
        when (v) {
            null -> cv.putNull(k)
            is Int -> cv.put(k, v.toLong())
            is Long -> cv.put(k, v)
            is Boolean -> cv.put(k, if (v) 1L else 0L)
            else -> cv.put(k, v as String)
        }
    }
    check(db.openHelper.writableDatabase.insert(table, 0, cv) != -1L) { "insert into $table failed" }
}

/** One phone: a Room database, a receipts directory and the fakes the restore writes to. */
class Phone(
    context: Context,
    name: String,
    seeded: Boolean = false,
) {
    val db: AppDatabase =
        Room
            .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .apply { if (seeded) addCallback(AppDatabaseCallback) }
            .build()
    val root = File(context.cacheDir, "phone-$name").also { it.deleteRecursively() }
    val receipts = File(root, "files/receipts").also { it.mkdirs() }
    val stage = RestoreStage.dir(receipts)
    val marker = RestoreStage.marker(receipts)
    val applied = mutableListOf<RestoredSettings>()
    var installedKey: ByteArray? = null
    var keyFailures = 0
    var onInstall: () -> Unit = {}

    val settings = RestoreSettingsApplier { applied += it }
    val keys =
        LocalIdKeyInstaller {
            onInstall()
            if (keyFailures > 0) {
                keyFailures--
                error("Keystore unavailable")
            }
            installedKey = it.copyOf()
        }

    fun restorer(
        limits: RestoreLimits = FAST,
        hooks: RestoreHooks = RestoreHooks.None,
        freeSpace: (File) -> Long = { Long.MAX_VALUE },
        openStage: (File) -> OutputStream = { java.io.FileOutputStream(it) },
    ) = BackupRestorer(
        RestoreEnvironment(
            db = RoomRestoreDatabase(db),
            settings = settings,
            keys = keys,
            receiptsDir = receipts,
            nowMillis = { NOW },
            limits = limits,
            freeSpace = freeSpace,
            openStageFile = openStage,
            hooks = hooks,
        ),
    )

    fun dump(): Map<String, List<Map<String, Any?>>> =
        BackupTables.INCLUDED.associate { t ->
            val rows = mutableListOf<Map<String, Any?>>()
            RoomBackupDatabase(db).forEachRow(t.name) { c, v -> rows += c.zip(v).toMap() }
            t.name to rows
        }

    fun receiptHashes(): Map<String, String> = receipts.listFiles().orEmpty().associate { it.name to sha256Of(it.readBytes()) }

    /** Everything the restore must not touch when it fails. */
    fun assertUntouched(
        rows: Map<String, List<Map<String, Any?>>>,
        files: Map<String, String>,
    ) {
        assertEquals("rows changed", rows, dump())
        assertEquals("receipts changed", files, receiptHashes())
        assertTrue("settings were applied", applied.isEmpty())
        assertNull("key was installed", installedKey)
        assertFalse("staging left behind", stage.exists())
        assertFalse("marker left behind", marker.exists())
    }

    fun close() {
        db.close()
        root.deleteRecursively()
    }
}

/** Source phone data: every kind of table the backup carries, with EPIC-18 rows and a TRANSFER_IN entry. */
fun fillSource(p: Phone) {
    val db = p.db
    insertRow(db, "member", "id" to "m1", "displayName" to "Me", "sortOrder" to 0)
    insertRow(db, "category", "id" to "c1", "nameEn" to "Food", "nameAr" to "أكل", "sortOrder" to 1)
    insertRow(db, "account", "id" to "a1", "label" to "Main", "currency" to "EGP", "last4" to "1234")
    for ((id, amount, type) in listOf(
        Triple("e1", 12500L, "EXPENSE"),
        Triple("e2", 99000L, "TRANSFER_IN"),
        Triple("e3", 500L, "EXPENSE"),
    )) {
        insertRow(
            db,
            "expense",
            "id" to id,
            "amountMinor" to amount,
            "currency" to "EGP",
            "occurredAtMillis" to T0,
            "pending" to false,
            "failed" to false,
            "type" to type,
            "categoryId" to "c1",
            "merchant" to "Shop $id",
            "createdAtMillis" to T0,
            "updatedAtMillis" to T0,
            "deletedAtMillis" to (if (id == "e3") T0 else null),
        )
    }
    insertRow(
        db,
        "expense_source",
        "id" to "s1",
        "expenseId" to "e1",
        "channel" to "SMS",
        "localSourceId" to "local-id-1",
        "receivedAtMillis" to T0,
        "senderId" to "CIB",
        "instrumentLast4" to "1234",
    )
    insertRow(
        db,
        "receipt",
        "id" to "r1",
        "expenseId" to "e1",
        "fileName" to "r1.jpg",
        "mimeType" to "image/jpeg",
        "sizeBytes" to RECEIPT_BYTES.size,
        "sha256" to sha256Of(RECEIPT_BYTES),
        "createdAtMillis" to T0,
    )
    insertRow(
        db,
        "receipt",
        "id" to "r2",
        "expenseId" to null,
        "fileName" to "gone.pdf",
        "mimeType" to "application/pdf",
        "sizeBytes" to 10,
        "sha256" to "ab".repeat(32),
        "createdAtMillis" to T0,
    )
    for ((id, cat) in listOf("b1" to "c1", "b2" to null)) {
        insertRow(
            db,
            "budget",
            "id" to id,
            "categoryId" to cat,
            "period" to "MONTHLY",
            "amountMinor" to 500000L,
            "currency" to "EGP",
            "createdAtMillis" to T0,
            "updatedAtMillis" to T0,
        )
    }
    insertRow(
        db,
        "account_balance",
        "id" to "ab1",
        "accountId" to "a1",
        "balanceMinor" to 1_000_000L,
        "currency" to "EGP",
        "atMillis" to T0,
        "source" to "MANUAL",
        "createdAtMillis" to T0,
    )
    insertRow(
        db,
        "savings_goal",
        "id" to "g1",
        "name" to "Trip",
        "targetMinor" to 9_000_000L,
        "currency" to "EGP",
        "sortOrder" to 0,
        "createdAtMillis" to T0,
    )
    insertRow(db, "goal_movement", "id" to "gm1", "goalId" to "g1", "amountMinor" to -500L, "atMillis" to T0)
    insertRow(
        db,
        "debt",
        "id" to "d1",
        "name" to "Ali",
        "kind" to "PERSON",
        "direction" to "I_OWE",
        "amountMinor" to 70000L,
        "currency" to "EGP",
        "createdAtMillis" to T0,
    )
    insertRow(db, "merchant_rule", "id" to "mr1", "merchantKey" to "shop", "categoryId" to "c1", "createdAtMillis" to T0)
    File(p.receipts, "r1.jpg").writeBytes(RECEIPT_BYTES)
}

val SOURCE_SETTINGS: Map<String, Map<String, Any>> =
    mapOf(
        "datastore:settings" to mapOf("arabic_digits" to true, "digits_set" to true, "month_start_day" to 5),
        "datastore:answers" to mapOf("kept_apart_pairs" to setOf("a|b"), "atm_choice" to "TRACK"),
        "shared:masroof_ui" to mapOf("language" to "ar"),
    )

/** The `.masroof` file of the filled source phone. */
fun sourceBackup(
    p: Phone,
    header: BackupHeader = fastHeader(),
): ByteArray =
    writeBackup(
        environment(
            db = RoomBackupDatabase(p.db),
            prefs = mapPrefs(SOURCE_SETTINGS),
            files = DirectoryReceiptFiles(p.receipts),
            localKey = LOCAL_KEY,
            now = T0 + 1000,
        ),
        RESTORE_PASS,
        header,
    ).first

/** The plaintext ZIP bytes of [entries]. */
fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/** A valid `.masroof` file around [zip] (any plaintext, so crafted ZIPs can be tested). */
fun encrypt(
    zip: ByteArray,
    header: BackupHeader = fastHeader(),
    pass: String = RESTORE_PASS,
): ByteArray {
    val key = PassphraseKdf.deriveKey(pass, header)
    val out = ByteArrayOutputStream()
    out.write(header.encode())
    BackupCipher.encrypt(out, key, header.encode()).use { it.write(zip) }
    return out.toByteArray()
}

fun repack(
    file: ByteArray,
    change: (String, ByteArray) -> ByteArray,
): ByteArray {
    val plain = BackupTestDecryptor.decrypt(file, RESTORE_PASS)
    return encrypt(zipOf(plain.names.map { it to change(it, plain.entries.getValue(it)) }))
}

fun ByteArray.replaceText(
    from: String,
    to: String,
): ByteArray = String(this, Charsets.UTF_8).also { check(from in it) { "fixture has no $from" } }.replace(from, to).toByteArray()

/** Stages [file] on [p] and expects check [step] to refuse it with [failure]; nothing on the phone may have changed. */
fun expectRefused(
    p: Phone,
    file: ByteArray,
    failure: RestoreFailure,
    step: Int,
    limits: RestoreLimits = FAST,
    pass: String = RESTORE_PASS,
    restorer: BackupRestorer = p.restorer(limits),
) {
    val rows = p.dump()
    val files = p.receiptHashes()
    try {
        val header = restorer.checkHeader(ByteArrayInputStream(file))
        runBlocking { restorer.stage({ ByteArrayInputStream(file) }, file.size.toLong(), header, pass) }
        fail("expected $failure at step $step")
    } catch (e: RestoreException) {
        assertEquals(failure, e.failure)
        assertEquals("step", step, e.step)
    }
    p.assertUntouched(rows, files)
}
