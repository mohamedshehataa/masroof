package eg.masroof.data.receipts

import android.graphics.Matrix
import java.io.File
import java.io.RandomAccessFile

/** Just enough JPEG parsing to read the EXIF orientation and to see whether the file ends properly (a cut-off file is refused). */
internal object JpegExif {
    private const val TAG_ORIENTATION = 0x0112
    private const val MAX_SCAN = 256 * 1024
    private const val TAIL = 64
    private const val ENTRY = 12

    fun isComplete(file: File): Boolean {
        val len = file.length()
        if (len < 4) return false
        RandomAccessFile(file, "r").use { f ->
            val tail = ByteArray(minOf(len, TAIL.toLong()).toInt())
            f.seek(len - tail.size)
            f.readFully(tail)
            var end = tail.size
            while (end > 0 && tail[end - 1].toInt() == 0) end--
            return end >= 2 && (tail[end - 2].toInt() and 0xFF) == 0xFF && (tail[end - 1].toInt() and 0xFF) == 0xD9
        }
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
        var i = 2
        while (i + 4 < b.size && (b[i].toInt() and 0xFF) == 0xFF) {
            val marker = b[i + 1].toInt() and 0xFF
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (marker == 0xDA) return 1
            if (marker == 0xE1 && i + 10 < b.size && String(b, i + 4, 4, Charsets.US_ASCII) == "Exif") {
                return Tiff(b, i + 10).orientation()
            }
            i += 2 + len
        }
        return 1
    }

    private class Tiff(
        private val b: ByteArray,
        private val t: Int,
    ) {
        private val little = t < b.size && b[t].toInt() == 'I'.code

        private fun u16(p: Int) =
            if (little) {
                (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
            } else {
                ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
            }

        private fun u32(p: Int) = if (little) u16(p) or (u16(p + 2) shl 16) else (u16(p) shl 16) or u16(p + 2)

        fun orientation(): Int {
            if (t + 8 > b.size) return 1
            val ifd = t + u32(t + 4)
            if (ifd < 0 || ifd + 2 > b.size) return 1
            for (k in 0 until u16(ifd)) {
                val e = ifd + 2 + k * ENTRY
                if (e + ENTRY > b.size) return 1
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
