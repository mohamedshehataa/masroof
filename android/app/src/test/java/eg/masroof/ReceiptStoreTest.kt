package eg.masroof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.edit.DeletedEntryPurger
import eg.masroof.data.receipts.AttachResult
import eg.masroof.data.receipts.PdfProbe
import eg.masroof.data.receipts.ReceiptSniffer
import eg.masroof.data.receipts.ReceiptStore
import eg.masroof.data.receipts.Refusal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC)

private fun jpeg(
    w: Int = 40,
    h: Int = 20,
    seed: Int = Color.RED,
    exif: ByteArray? = null,
): ByteArray {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(seed)
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
    val bytes = out.toByteArray()
    if (exif == null) return bytes
    // SOI, our APP1 (EXIF with orientation 6, GPS marker text, camera model), then the rest of the file
    return bytes.copyOfRange(0, 2) + exif + bytes.copyOfRange(2, bytes.size)
}

/** A big-endian EXIF APP1 with one IFD entry: Orientation = 6, followed by GPS and Make strings in the segment. */
private fun exifOrientation6(): ByteArray {
    val tiff =
        "MM".toByteArray() + byteArrayOf(0, 0x2A, 0, 0, 0, 8) +
            byteArrayOf(0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0) +
            "GPSLatitude=30.04 Make=PhoneCo".toByteArray()
    val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
    val len = body.size + 2
    return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + body
}

private fun pngChunk(
    type: String,
    data: ByteArray,
): ByteArray {
    fun be(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    val body = type.toByteArray() + data
    val crc =
        java.util.zip
            .CRC32()
            .apply { update(body) }
            .value
            .toInt()
    return be(data.size) + body + be(crc)
}

/** A real 1-bit PNG of w x h: 72 megapixels in a few KB on disk (a decompression bomb). */
private fun png(
    w: Int,
    h: Int,
): ByteArray {
    fun be(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    val raw = ByteArray(h * (1 + (w + 7) / 8))
    val deflater =
        java.util.zip.Deflater().apply {
            setInput(raw)
            finish()
        }
    val packed = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (!deflater.finished()) packed.write(buf, 0, deflater.deflate(buf))
    val ihdr = be(w) + be(h) + byteArrayOf(1, 0, 0, 0, 0)
    return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        pngChunk("IHDR", ihdr) + pngChunk("IDAT", packed.toByteArray()) + pngChunk("IEND", ByteArray(0))
}

/** Left half red, right half blue (big blocks survive JPEG), 40 x 20, optionally with EXIF orientation 6. */
private fun splitJpeg(exif: ByteArray? = null): ByteArray {
    val bmp = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(Color.BLUE)
    canvas.drawRect(0f, 0f, 20f, 20f, android.graphics.Paint().apply { color = Color.RED })
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
    val bytes = out.toByteArray()
    return if (exif == null) bytes else bytes.copyOfRange(0, 2) + exif + bytes.copyOfRange(2, bytes.size)
}

private fun pdf(extra: Int = 0) = "%PDF-1.4\n% fake body\n".toByteArray() + ByteArray(extra) { 'x'.code.toByte() }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ReceiptStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var dir: File
    private var pages = 2
    private var probeFails = false
    private lateinit var store: ReceiptStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dir = File(ctx.filesDir, "receipts-test-${System.nanoTime()}")
        store =
            ReceiptStore(
                db,
                dir,
                PdfProbe { if (probeFails) throw IOException("cannot open") else pages },
                CLOCK,
                EraseLock(),
                Dispatchers.Unconfined,
            )
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private suspend fun entry(id: String = "e1") =
        db.expenses().insert(
            ExpenseEntity(id, 125_00, "EGP", 1_760_000_000_000, null, null, null, null, pending = false),
        )

    private suspend fun attach(
        bytes: ByteArray,
        to: String = "e1",
    ) = store.attach(to) { ByteArrayInputStream(bytes) }

    private fun files() = dir.listFiles().orEmpty().toList()

    @Test
    fun `REQ-RCPT-015 a photo is stored as a generated jpg with one row and page count 1`() =
        runTest {
            entry()
            assertTrue(attach(jpeg()) is AttachResult.Added)
            val f = files().single()
            assertTrue(f.name.matches(Regex("[0-9a-f-]{36}\\.jpg")))
            val row = db.receipts().fileNames().single()
            assertEquals(f.name, row)
            val r =
                db
                    .receipts()
                    .observeFor("e1")
                    .first()
                    .single()
            assertEquals("image/jpeg", r.mimeType)
            assertEquals(f.length(), r.sizeBytes)
            assertEquals(1, r.pageCount)
            assertEquals(64, r.sha256.length)
        }

    @Test
    fun `REQ-RCPT-015 a pdf is stored byte for byte as pdf with its page count and several receipts keep their order`() =
        runTest {
            entry()
            val bytes = pdf(100)
            assertTrue(attach(bytes) is AttachResult.Added)
            assertTrue(attach(jpeg(seed = Color.BLUE)) is AttachResult.Added)
            val rows = db.receipts().observeFor("e1").first()
            assertEquals(listOf("application/pdf", "image/jpeg"), rows.map { it.mimeType })
            assertEquals(2, rows[0].pageCount)
            assertTrue(bytes.contentEquals(store.fileOf(rows[0]).readBytes()))
            assertTrue(rows[0].fileName.endsWith(".pdf"))
        }

    @Test
    fun `REQ-RCPT-015 a copy that fails half way leaves no row and no file`() =
        runTest {
            entry()
            val broken =
                object : InputStream() {
                    var n = 0

                    override fun read(): Int = throw IOException("x")

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        if (n++ == 0) {
                            val j = jpeg()
                            System.arraycopy(j, 0, b, off, minOf(len, j.size, 16))
                            return minOf(len, j.size, 16)
                        }
                        throw IOException("storage full")
                    }
                }
            assertEquals(AttachResult.Failed, store.attach("e1") { broken })
            assertEquals(0, db.receipts().count())
            assertTrue(files().isEmpty())
            assertEquals(AttachResult.Failed, store.attach("e1") { null })
        }

    @Test
    fun `REQ-RCPT-016 the type comes from the first bytes, not the name or MIME`() =
        runTest {
            entry()
            for (bytes in listOf(
                "hello, I am a text file".toByteArray(),
                "PK\u0003\u0004zip".toByteArray(),
                "<svg xmlns='x'/>".toByteArray(),
            )) {
                assertEquals(AttachResult.Refused(Refusal.UNSUPPORTED), attach(bytes))
            }
            assertEquals(0, db.receipts().count())
            assertTrue(files().isEmpty())
            assertNotNull(ReceiptSniffer.kindOf(jpeg()))
            assertNotNull(ReceiptSniffer.kindOf("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray()))
            assertNotNull(ReceiptSniffer.kindOf(byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray()))
            assertNull(ReceiptSniffer.kindOf(ByteArray(0)))
        }

    @Test
    fun `REQ-RCPT-016 oversized images and pdfs stop at the byte cap and leave nothing`() =
        runTest {
            entry()
            val bigImage = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(21 * 1024 * 1024)
            assertEquals(AttachResult.Refused(Refusal.TOO_LARGE), attach(bigImage))
            assertEquals(AttachResult.Refused(Refusal.TOO_LARGE), attach(pdf(11 * 1024 * 1024)))
            assertTrue(files().isEmpty())
        }

    @Test
    fun `REQ-RCPT-016 a 72 megapixel png is refused from its header and a pdf needs 30 pages or fewer and must open`() =
        runTest {
            entry()
            assertEquals(AttachResult.Refused(Refusal.TOO_LARGE), attach(png(12_000, 6_000)))
            pages = 31
            assertEquals(AttachResult.Refused(Refusal.TOO_MANY_PAGES), attach(pdf()))
            pages = 30
            assertTrue(attach(pdf(1)) is AttachResult.Added)
            probeFails = true
            assertEquals(AttachResult.Refused(Refusal.PDF_UNREADABLE), attach(pdf(2)))
            assertEquals(1, db.receipts().count())
            assertEquals(1, files().size)
        }

    @Test
    fun `REQ-RCPT-017 gps and camera data are gone, the image is upright and a png becomes jpeg`() =
        runTest {
            entry()
            val source = jpeg(40, 20, exif = exifOrientation6())
            assertTrue(String(source, Charsets.ISO_8859_1).contains("GPSLatitude"))
            assertTrue(attach(source) is AttachResult.Added)
            val stored = files().single().readBytes()
            val text = String(stored, Charsets.ISO_8859_1)
            assertFalse(text.contains("Exif"))
            assertFalse(text.contains("GPS"))
            assertFalse(text.contains("PhoneCo"))
            val o =
                android.graphics.BitmapFactory
                    .Options()
                    .apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(stored, 0, stored.size, o)
            assertEquals(20, o.outWidth) // orientation 6 turned the 40 x 20 landscape upright
            assertEquals(40, o.outHeight)

            val pngBytes =
                ByteArrayOutputStream().also {
                    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            assertTrue(attach(pngBytes.toByteArray()) is AttachResult.Added)
            assertEquals(2, files().count { it.name.endsWith(".jpg") })
        }

    @Test
    fun `REQ-RCPT-017 the stored long edge is exactly 2560 for big photos and never upscaled`() =
        runTest {
            entry()
            for ((w, h, expected) in listOf(Triple(3000, 2000, 2560), Triple(6000, 4000, 2560), Triple(1200, 800, 1200))) {
                dir.deleteRecursively()
                db.clearAllTables()
                entry()
                assertTrue(attach(jpeg(w, h)) is AttachResult.Added)
                val o =
                    android.graphics.BitmapFactory
                        .Options()
                        .apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(files().single().path, o)
                assertEquals("$w x $h", expected, maxOf(o.outWidth, o.outHeight))
            }
        }

    @Test
    fun `REQ-RCPT-017 a long edge over 2560 is scaled down`() =
        runTest {
            entry()
            assertTrue(attach(jpeg(5_000, 100)) is AttachResult.Added)
            val o =
                android.graphics.BitmapFactory
                    .Options()
                    .apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(files().single().path, o)
            assertTrue(o.outWidth <= 2560)
        }

    @Test
    fun `REQ-RCPT-017 a truncated jpeg is refused and nothing remains`() =
        runTest {
            entry()
            val whole = jpeg(200, 200)
            assertEquals(AttachResult.Refused(Refusal.IMAGE_UNREADABLE), attach(whole.copyOf(whole.size / 2)))
            assertEquals(0, db.receipts().count())
            assertTrue(files().isEmpty())
        }

    @Test
    fun `REQ-RCPT-020 the same file twice is stored once and the message points at the first entry`() =
        runTest {
            entry("e1")
            entry("e2")
            val bytes = pdf(5)
            val first = attach(bytes, "e1") as AttachResult.Added
            val second = attach(bytes, "e2") as AttachResult.Duplicate
            assertEquals(first.receiptId, second.receiptId)
            assertEquals("e1", second.expenseId)
            assertEquals(125_00L, second.amountMinor)
            assertEquals("EGP", second.currency)
            assertEquals(1, db.receipts().count())
            assertEquals(1, files().size)
        }

    @Test
    fun `REQ-RCPT-019 removing deletes the row then the file, a missing file is fine`() =
        runTest {
            entry()
            val id = (attach(pdf()) as AttachResult.Added).receiptId
            val row = db.receipts().find(id)!!
            store.fileOf(row).delete() // the viewer's "file is missing" case
            store.remove(id)
            assertEquals(0, db.receipts().count())
            val id2 = (attach(pdf(3)) as AttachResult.Added).receiptId
            store.remove(id2)
            assertTrue(files().isEmpty())
        }

    @Test
    fun `REQ-RCPT-019 soft delete keeps receipts and the purge deletes rows and files of old deletions only`() =
        runTest {
            entry("old")
            entry("fresh")
            attach(pdf(1), "old")
            attach(pdf(2), "fresh")
            val now = CLOCK.millis()
            db.expenses().softDelete("old", now - 31 * 86_400_000L)
            db.expenses().softDelete("fresh", now - 1_000L)
            assertEquals(2, db.receipts().count())
            assertEquals(2, files().size)
            val purger = DeletedEntryPurger(db, CLOCK, EraseLock(), dir)
            assertEquals(1, purger.purge())
            assertEquals(1, db.receipts().count())
            assertEquals(1, files().size)
        }

    @Test
    fun `REQ-RCPT-019 the startup sweeper deletes files without a row and part files, and keeps files with a row`() =
        runTest {
            entry()
            attach(pdf(7))
            File(dir, "orphan.jpg").writeBytes(byteArrayOf(1))
            File(dir, "x.part").writeBytes(byteArrayOf(1))
            store.sweep()
            assertEquals(1, files().size)
            assertEquals(db.receipts().fileNames(), files().map { it.name })
        }

    @Test
    fun `REQ-RCPT-019 settings totals count files and bytes`() =
        runTest {
            entry()
            attach(pdf(7))
            attach(pdf(9))
            val t = db.receipts().observeTotals().first()
            assertEquals(2, t.files)
            assertEquals(files().sumOf { it.length() }, t.bytes)
        }

    @Test
    fun `REQ-RCPT-017 orientation 6 turns the picture clockwise so the left half ends on top`() =
        runTest {
            entry()
            assertTrue(attach(splitJpeg(exifOrientation6())) is AttachResult.Added)
            val bmp = android.graphics.BitmapFactory.decodeFile(files().single().path)
            assertEquals(20, bmp.width)
            assertEquals(40, bmp.height)
            val top = bmp.getPixel(10, 5)
            val bottom = bmp.getPixel(10, 35)
            assertTrue("top should be red", Color.red(top) > 180 && Color.blue(top) < 90)
            assertTrue("bottom should be blue", Color.blue(bottom) > 180 && Color.red(bottom) < 90)
        }

    @Test
    fun `REQ-RCPT-017 a transparent png is put on a white background`() =
        runTest {
            entry()
            val clear = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val png = ByteArrayOutputStream().also { clear.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            assertTrue(attach(png) is AttachResult.Added)
            val px =
                android.graphics.BitmapFactory
                    .decodeFile(files().single().path)
                    .getPixel(8, 8)
            assertTrue(Color.red(px) > 240 && Color.green(px) > 240 && Color.blue(px) > 240)
        }

    @Test
    fun `REQ-RCPT-017 a valid jpeg with a Samsung trailer or an appended motion photo video is accepted and the trailer is dropped`() =
        runTest {
            entry()
            val seft = jpeg(seed = Color.GREEN) + "\u0000\u0000SEFHxxxxxxxxImage_UTC_Data1700000000000SEFT".toByteArray(Charsets.ISO_8859_1)
            assertTrue(attach(seft) is AttachResult.Added)
            val mp4 = jpeg(seed = Color.CYAN) + byteArrayOf(0, 0, 0, 0x18) + "ftypmp42isommp42".toByteArray() + ByteArray(5000)
            assertTrue(attach(mp4) is AttachResult.Added)
            assertEquals(2, files().size)
            for (f in files()) {
                val text = String(f.readBytes(), Charsets.ISO_8859_1)
                assertFalse(text.contains("SEFT") || text.contains("ftyp") || text.contains("Image_UTC"))
            }
        }

    @Test
    fun `REQ-RCPT-016 an endless stream is cut at the cap plus one buffer`() =
        runTest {
            entry()
            var read = 0L
            val endless =
                object : InputStream() {
                    private val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

                    override fun read(): Int = 0

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        for (i in 0 until len) b[off + i] = if (read + i < head.size) head[(read + i).toInt()] else 0
                        read += len
                        return len
                    }
                }
            assertEquals(AttachResult.Refused(Refusal.TOO_LARGE), store.attach("e1") { endless })
            assertTrue("read $read", read <= 20L * 1024 * 1024 + 16 * 1024)
            assertTrue(files().isEmpty())
        }

    @Test
    fun `REQ-RCPT-020 a file held by a soft deleted entry moves to the new entry instead of reporting a duplicate`() =
        runTest {
            entry("e1")
            entry("e2")
            val bytes = pdf(11)
            val first = attach(bytes, "e1") as AttachResult.Added
            db.expenses().softDelete("e1", CLOCK.millis())
            val second = attach(bytes, "e2")
            assertEquals(AttachResult.Added(first.receiptId), second)
            assertEquals("e2", db.receipts().find(first.receiptId)!!.expenseId)
            assertEquals(1, files().size)
        }

    @Test
    fun `REQ-RCPT-017 an exif ifd offset near Int MAX does not wrap around`() {
        val tiff = "MM".toByteArray() + byteArrayOf(0, 0x2A, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xF2.toByte()) + ByteArray(8)
        val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = body.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + body
        val file = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segment + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2)
        assertEquals(
            1,
            eg.masroof.data.receipts.JpegExif
                .orientationOf(file),
        )
    }
}
