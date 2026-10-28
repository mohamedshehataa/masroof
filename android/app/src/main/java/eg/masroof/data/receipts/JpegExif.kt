package eg.masroof.data.receipts

import android.graphics.Matrix
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile

/** Just enough JPEG parsing to read the EXIF orientation and to see whether the entropy-coded data was cut off. */
internal object JpegExif {
    private const val TAG_ORIENTATION = 0x0112
    private const val MAX_SCAN = 256 * 1024
    private const val ENTRY = 12L
    private const val MARKER_PREFIX = 0xFF
    private const val SOS = 0xDA
    private const val EOI = 0xD9
    private const val APP1 = 0xE1

    /**
     * True if an end-of-image marker follows the start of scan. Data after the EOI is fine (Samsung SEFT trailers, Motion
     * Photo videos); a file cut off inside the scan has no EOI at all. The trailer is dropped by the re-encode.
     */
    fun isComplete(file: File): Boolean {
        val scanStart = scanStart(file) ?: return false
        BufferedInputStream(file.inputStream()).use { input ->
            if (input.skip(scanStart) != scanStart) return false
            var previous = 0
            while (true) {
                val b = input.read()
                if (b < 0) return false
                if (previous == MARKER_PREFIX && b == EOI) return true
                previous = b
            }
        }
    }

    private fun scanStart(file: File): Long? {
        RandomAccessFile(file, "r").use { f ->
            var pos = 2L
            val head = ByteArray(4)
            while (pos + head.size <= f.length()) {
                f.seek(pos)
                f.readFully(head)
                if ((head[0].toInt() and 0xFF) != MARKER_PREFIX) return null
                val marker = head[1].toInt() and 0xFF
                if (marker == SOS) return pos + 2
                pos += 2 + (((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF))
            }
        }
        return null
    }

    /** EXIF orientation 1..8, 1 when absent or unreadable. */
    fun orientation(file: File): Int {
        val b =
            file.inputStream().use { s ->
                val buf = ByteArray(MAX_SCAN)
                var got = 0
                while (got < buf.size) {
                    val n = s.read(buf, got, buf.size - got)
                    if (n < 0) break
                    got += n
                }
                buf.copyOf(got)
            }
        return orientationOf(b)
    }

    internal fun orientationOf(b: ByteArray): Int {
        var i = 2
        while (i + 4 < b.size && (b[i].toInt() and 0xFF) == MARKER_PREFIX) {
            val marker = b[i + 1].toInt() and 0xFF
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (marker == SOS) return 1
            if (marker == APP1 && i + 10 < b.size && String(b, i + 4, 4, Charsets.US_ASCII) == "Exif") {
                return Tiff(b, i + 10).orientation()
            }
            i += 2 + len
        }
        return 1
    }

    /** All offsets are checked in Long: a hostile 32-bit offset must not wrap around. */
    private class Tiff(
        private val b: ByteArray,
        private val t: Int,
    ) {
        private val little = t < b.size && b[t].toInt() == 'I'.code

        private fun u16(p: Long): Int {
            val i = p.toInt()
            return if (little) {
                (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            } else {
                ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            }
        }

        private fun u32(p: Long): Long =
            if (little) {
                u16(p).toLong() or (u16(p + 2).toLong() shl 16)
            } else {
                (u16(p).toLong() shl 16) or u16(p + 2).toLong()
            }

        fun orientation(): Int {
            val size = b.size.toLong()
            if (t + 8L > size) return 1
            val ifd = t + u32(t + 4L)
            if (ifd < 0 || ifd + 2 > size) return 1
            for (k in 0 until u16(ifd)) {
                val e = ifd + 2 + k * ENTRY
                if (e + ENTRY > size) return 1
                if (u16(e) == TAG_ORIENTATION) return u16(e + 8).takeIf { it in 1..8 } ?: 1
            }
            return 1
        }
    }

    fun matrixFor(orientation: Int): Matrix =
        Matrix().apply {
            when (orientation) {
                2 -> postScale(-1f, 1f)
                3 -> postRotate(180f)
                4 -> postScale(1f, -1f)
                5 -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                6 -> postRotate(90f)
                7 -> {
                    postRotate(270f)
                    postScale(-1f, 1f)
                }
                8 -> postRotate(270f)
            }
        }
}
