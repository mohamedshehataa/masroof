package eg.masroof.backup

import eg.masroof.data.backup.BackupCipher
import eg.masroof.data.backup.BackupHeader
import eg.masroof.data.backup.BackupInput
import eg.masroof.data.backup.BackupReceiptRef
import eg.masroof.data.backup.BackupWriter
import eg.masroof.data.backup.ReceiptFiles
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/**
 * Runs in a child JVM with a small heap (see BackupStreamingTest): writes the given number of megabytes of generated receipts through
 * the real writer and decrypts the stream on a second thread at the same time. Nothing is held whole, so 500 MB fits in 48 MB.
 */
object LargeBackupMain {
    private const val RECEIPT_COUNT = 50

    /** Deterministic noise, no allocation per byte. */
    private class Noise(
        private var remaining: Long,
    ) : InputStream() {
        private var state = 0x1E3779B97F4A7C15L

        override fun read(): Int = if (remaining-- > 0) next().toInt() and 0xFF else -1

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            for (i in 0 until n) b[off + i] = next()
            remaining -= n
            return n
        }

        private fun next(): Byte {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            return (state ushr 24).toByte()
        }
    }

    private fun readFully(
        input: InputStream,
        into: ByteArray,
    ) {
        var off = 0
        while (off < into.size) {
            val n = input.read(into, off, into.size - off)
            check(n >= 0) { "short header" }
            off += n
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val totalMb = args.firstOrNull()?.toLong() ?: 500L
        val each = totalMb * 1024 * 1024 / RECEIPT_COUNT
        val refs = (0 until RECEIPT_COUNT).map { BackupReceiptRef("r$it", "r$it.jpg", each) }
        val env = environment(db = FakeBackupDb(receiptRefs = refs), files = ReceiptFiles { Noise(each) })
        val header = BackupHeader(1, 1, 64, 1, 1, ByteArray(16) { 5 }, 1, 0)
        val key = ByteArray(32) { (it * 5).toByte() }
        val pipeIn = PipedInputStream(1 shl 20)
        val pipeOut = PipedOutputStream(pipeIn)
        val failure = AtomicReference<Throwable?>(null)
        var receiptBytes = 0L
        var firstEntry: String? = null
        val reader =
            Thread {
                try {
                    val headerBytes = ByteArray(BackupHeader.SIZE).also { readFully(pipeIn, it) }
                    ZipInputStream(BackupCipher.decrypt(pipeIn, key, headerBytes)).use { zip ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val e = zip.nextEntry ?: break
                            if (firstEntry == null) firstEntry = e.name
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                if (e.name.startsWith("receipts/")) receiptBytes += n
                            }
                        }
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                    pipeIn.close()
                }
            }
        reader.start()
        val result = runBlocking { BackupWriter(env).write(pipeOut, BackupInput(header, key)) }
        reader.join(120_000)
        failure.get()?.let { throw it }
        check(firstEntry == "manifest.json") { "manifest first" }
        check(receiptBytes == each * RECEIPT_COUNT) { "receipt bytes $receiptBytes" }
        println("OK $receiptBytes stored=${result.receiptsStored} heap=${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB")
    }
}
