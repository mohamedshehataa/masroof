package eg.masroof.data.receipts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ReceiptEntity
import eg.masroof.di.IoDispatcher
import eg.masroof.di.ReceiptsDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Counts the pages of a stored PDF; throws if the file cannot be opened (corrupt, password-protected). */
fun interface PdfProbe {
    fun pageCount(file: File): Int
}

class AndroidPdfProbe
    @Inject
    constructor() : PdfProbe {
        override fun pageCount(file: File): Int =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { it.pageCount }
            }
    }

/**
 * App-private receipt storage (ADR-0009 D4): `filesDir/receipts/<uuid>.<ext>` plus one `receipt` row. Receipt content and
 * file names are never logged. Any failure deletes the partial file.
 */
@Singleton
class ReceiptStore
    @Inject
    constructor(
        private val db: AppDatabase,
        @ReceiptsDir private val dir: File,
        private val probe: PdfProbe,
        private val clock: Clock,
        private val eraseLock: EraseLock,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        private val attachLock = Mutex()

        fun fileOf(receipt: ReceiptEntity): File = File(dir, receipt.fileName)

        /** How long one copy may take before it is abandoned (a provider whose stream never returns must not hold anything up). */
        @Volatile internal var copyTimeoutMillis: Long = COPY_TIMEOUT_MS

        /** Copies, checks, re-encodes and records the file behind [open] for [expenseId] (null = waits unattached). */
        @Suppress("TooGenericExceptionCaught") // any failure leaves nothing behind and is reported as Failed
        suspend fun attach(
            expenseId: String?,
            open: () -> InputStream?,
        ): AttachResult =
            withContext(io) {
                val raw = File(dir, "${UUID.randomUUID()}.part")
                var stored: File? = null
                var result: AttachResult = AttachResult.Failed
                try {
                    result = copyAndStore(expenseId, raw, open) { stored = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: OutOfMemoryError) {
                    result = AttachResult.Failed
                } catch (_: Exception) {
                    result = AttachResult.Failed
                } finally {
                    raw.delete()
                    if (result !is AttachResult.Added) stored?.delete()
                }
                result
            }

        private suspend fun copyAndStore(
            expenseId: String?,
            raw: File,
            open: () -> InputStream?,
            onStored: (File) -> Unit,
        ): AttachResult {
            if (!dir.isDirectory && !dir.mkdirs()) return AttachResult.Failed
            if (expenseId == null && quotaReached()) return AttachResult.Refused(Refusal.QUOTA)
            // The copy runs outside the lock: a slow or stuck source cannot hold up other attaches.
            val copied = copyWithTimeout(open, raw) ?: return AttachResult.Failed
            if (copied is Copied.Refused) return AttachResult.Refused(copied.reason)
            val kind = (copied as Copied.Ok).kind
            // one decode at a time: a large photo takes tens of MB, two at once could run out of memory
            return attachLock.withLock {
                val made = if (kind == ReceiptKind.PDF) storePdf(raw) else storeImage(raw, kind)
                if (made is Made.Refused) return@withLock AttachResult.Refused(made.reason)
                val ok = made as Made.Stored
                onStored(ok.file)
                commit(expenseId, ok)
            }
        }

        private suspend fun quotaReached(): Boolean {
            val t = db.receipts().unattachedTotals()
            return t.files >= ReceiptLimits.MAX_UNATTACHED_FILES || t.bytes >= ReceiptLimits.MAX_UNATTACHED_BYTES
        }

        /** Null on timeout or when the source gives no stream. The stream is closed from here on timeout to unblock the reader. */
        private suspend fun copyWithTimeout(
            open: () -> InputStream?,
            raw: File,
        ): Copied? {
            val holder =
                java.util.concurrent.atomic
                    .AtomicReference<InputStream?>(null)
            val job =
                CoroutineScope(io + SupervisorJob()).async {
                    val input = open() ?: return@async null
                    holder.set(input)
                    input.use { copyCapped(it, raw) }
                }
            val done = withTimeoutOrNull(copyTimeoutMillis) { runCatching { job.await() } }
            if (done == null) {
                runCatching { holder.get()?.close() }
                job.cancel()
                return null
            }
            return done.getOrNull()
        }

        private suspend fun commit(
            expenseId: String?,
            stored: Made.Stored,
        ): AttachResult =
            eraseLock.mutex.withLock {
                val hash = sha256(stored.file)
                val same = db.receipts().bySha(hash)
                if (same != null) {
                    val owner = same.expenseId?.let { db.expenses().find(it) }
                    if (owner != null && owner.deletedAtMillis == null) {
                        return@withLock AttachResult.Duplicate(same.id, owner.id, owner.occurredAtMillis, owner.amountMinor, owner.currency)
                    }
                    // Held by a deleted entry (or by none): the unique sha256 allows one row, so this entry takes it over and the new
                    // copy is dropped. Undo of the deleted entry then brings it back without this receipt.
                    // A receipt-first flow (expenseId null) frees it from the deleted entry so it shows in the Receipts list.
                    if (expenseId == null && same.expenseId == null) {
                        return@withLock AttachResult.Duplicate(same.id, null, null, null, null)
                    }
                    db.receipts().moveTo(same.id, expenseId)
                    stored.file.delete()
                    return@withLock AttachResult.Added(same.id)
                }
                val id = UUID.randomUUID().toString()
                db.receipts().insert(
                    ReceiptEntity(id, expenseId, stored.file.name, stored.mime, stored.file.length(), hash, stored.pages, clock.millis()),
                )
                AttachResult.Added(id)
            }

        /**
         * Attaches a receipt that waits unattached to a live entry. False if the receipt is gone or already attached, or the entry is
         * gone or deleted; nothing changes then.
         */
        suspend fun attachExisting(
            receiptId: String,
            expenseId: String,
        ): Boolean =
            eraseLock.mutex.withLock {
                val row = db.receipts().find(receiptId)
                val entry = db.expenses().find(expenseId)
                if (row == null || row.expenseId != null) return@withLock false
                if (entry == null || entry.deletedAtMillis != null) return@withLock false
                db.receipts().moveTo(receiptId, expenseId)
                true
            }

        /** Row first, then the file; a file that cannot be deleted is picked up by [sweep] at the next start. */
        suspend fun remove(id: String) {
            eraseLock.mutex.withLock {
                val row = db.receipts().find(id) ?: return
                db.receipts().delete(id)
                withContext(io) { runCatching { fileOf(row).delete() } }
            }
        }

        /** Deletes the files of receipt rows that a purge removed. */
        suspend fun deleteFiles(rows: List<ReceiptEntity>) {
            withContext(io) { rows.forEach { runCatching { fileOf(it).delete() } } }
        }

        /** Startup sweeper: a file with no row (crash between copy and insert, failed delete, `.part`) is deleted. */
        suspend fun sweep() {
            eraseLock.mutex.withLock {
                withContext(io) {
                    val known = db.receipts().fileNames().toSet()
                    dir.listFiles()?.filter { it.name !in known }?.forEach { runCatching { it.deleteRecursively() } }
                }
            }
        }

        private sealed interface Copied {
            data class Ok(
                val kind: ReceiptKind,
            ) : Copied

            data class Refused(
                val reason: Refusal,
            ) : Copied
        }

        private sealed interface Made {
            data class Stored(
                val file: File,
                val mime: String,
                val pages: Int,
            ) : Made

            data class Refused(
                val reason: Refusal,
            ) : Made
        }

        /** Sniffs the first bytes, then copies; the byte cap of the detected type stops the copy. */
        private fun copyCapped(
            input: InputStream,
            to: File,
        ): Copied {
            val header = ByteArray(ReceiptSniffer.HEADER_BYTES)
            var got = 0
            while (got < header.size) {
                val n = input.read(header, got, header.size - got)
                if (n < 0) break
                got += n
            }
            val kind = ReceiptSniffer.kindOf(header.copyOf(got)) ?: return Copied.Refused(Refusal.UNSUPPORTED)
            val cap = if (kind == ReceiptKind.PDF) ReceiptLimits.PDF_MAX_BYTES else ReceiptLimits.IMAGE_MAX_BYTES
            var total = got.toLong()
            to.outputStream().use { out ->
                out.write(header, 0, got)
                val buf = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > cap) return Copied.Refused(Refusal.TOO_LARGE)
                    out.write(buf, 0, n)
                }
            }
            return Copied.Ok(kind)
        }

        @Suppress("TooGenericExceptionCaught") // PdfRenderer throws IOException, SecurityException, IllegalArgumentException, ...
        private fun storePdf(raw: File): Made {
            val pages =
                try {
                    probe.pageCount(raw)
                } catch (_: Exception) {
                    return Made.Refused(Refusal.PDF_UNREADABLE)
                }
            if (pages < 1) return Made.Refused(Refusal.PDF_UNREADABLE)
            if (pages > ReceiptLimits.MAX_PDF_PAGES) return Made.Refused(Refusal.TOO_MANY_PAGES)
            val target = File(dir, "${UUID.randomUUID()}.pdf")
            raw.copyTo(target) // byte for byte; the PDF's metadata is kept (ADR-0009 D4)
            return Made.Stored(target, "application/pdf", pages)
        }

        private fun storeImage(
            raw: File,
            kind: ReceiptKind,
        ): Made {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return Made.Refused(Refusal.IMAGE_UNREADABLE)
            if (w.toLong() * h > ReceiptLimits.MAX_PIXELS) return Made.Refused(Refusal.TOO_LARGE)
            if (kind == ReceiptKind.JPEG && !JpegExif.isComplete(raw)) return Made.Refused(Refusal.IMAGE_UNREADABLE)
            val decoded = decode(raw, kind, w, h) ?: return Made.Refused(Refusal.IMAGE_UNREADABLE)
            val target = File(dir, "${UUID.randomUUID()}.jpg")
            try {
                target.outputStream().use { decoded.compress(Bitmap.CompressFormat.JPEG, ReceiptLimits.JPEG_QUALITY, it) }
            } finally {
                decoded.recycle()
            }
            return Made.Stored(target, "image/jpeg", 1)
        }

        /**
         * Upright, long edge at most 2560 px (sampled down while decoding, so a 50 MP photo never sits in memory whole), on a white
         * background if it has transparency. A Bitmap carries no EXIF, so writing it out strips everything.
         */
        private fun decode(
            raw: File,
            kind: ReceiptKind,
            width: Int,
            height: Int,
        ): Bitmap? {
            val matrix = JpegExif.matrixFor(if (kind == ReceiptKind.JPEG) JpegExif.orientation(raw) else 1)
            var sample = 1
            // the largest power of two that keeps the decoded long edge >= 2560 (so < 5120): full detail, bounded memory
            while (maxOf(width, height) / (sample * 2) >= ReceiptLimits.MAX_LONG_EDGE) sample *= 2
            // and never more than 16 MP in memory (5119 x 5119 would be 105 MB)
            while ((width / sample).toLong() * (height / sample) > MAX_DECODED_PIXELS) sample *= 2
            var current = BitmapFactory.decodeFile(raw.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            var done = false
            try {
                current = fitLongEdge(current)
                if (current.hasAlpha()) current = onWhite(current)
                if (!matrix.isIdentity) {
                    val turned = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
                    if (turned !== current) current.recycle()
                    current = turned
                }
                done = true
                return current
            } finally {
                if (!done) current.recycle()
            }
        }

        /** Exactly [ReceiptLimits.MAX_LONG_EDGE] on the long side when larger; never scaled up. */
        private fun fitLongEdge(src: Bitmap): Bitmap {
            val long = maxOf(src.width, src.height)
            if (long <= ReceiptLimits.MAX_LONG_EDGE) return src
            val w = maxOf(1, src.width * ReceiptLimits.MAX_LONG_EDGE / long)
            val h = maxOf(1, src.height * ReceiptLimits.MAX_LONG_EDGE / long)
            return src.scale(w, h).also { if (it !== src) src.recycle() }
        }

        private fun onWhite(src: Bitmap): Bitmap {
            val out = createBitmap(src.width, src.height)
            Canvas(out).apply {
                drawColor(Color.WHITE)
                drawBitmap(src, 0f, 0f, null)
            }
            src.recycle()
            return out
        }

        private fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private companion object {
            const val COPY_BUFFER = 16 * 1024
            const val COPY_TIMEOUT_MS = 30_000L
            const val MAX_DECODED_PIXELS = 16_000_000L
        }
    }
