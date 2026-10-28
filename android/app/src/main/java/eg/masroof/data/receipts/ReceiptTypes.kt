package eg.masroof.data.receipts

/** What a receipt file really is, decided from its first bytes only (never the provider's MIME type or the file name). */
enum class ReceiptKind { JPEG, PNG, WEBP, HEIF, PDF }

object ReceiptLimits {
    const val IMAGE_MAX_BYTES = 20L * 1024 * 1024
    const val PDF_MAX_BYTES = 10L * 1024 * 1024
    const val MAX_PIXELS = 50_000_000L
    const val MAX_PDF_PAGES = 30
    const val MAX_LONG_EDGE = 2560
    const val JPEG_QUALITY = 85
}

/** Magic-byte sniffing. SVG, text, ZIP and anything else return null. */
object ReceiptSniffer {
    const val HEADER_BYTES = 16
    private const val RIFF_HEADER = 12

    private val heifBrands = listOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "avif")

    fun kindOf(h: ByteArray): ReceiptKind? =
        when {
            h.startsWith(0xFF, 0xD8, 0xFF) -> ReceiptKind.JPEG
            h.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ReceiptKind.PNG
            h.startsWith('%'.code, 'P'.code, 'D'.code, 'F'.code, '-'.code) -> ReceiptKind.PDF
            h.size >= RIFF_HEADER && h.ascii(0, "RIFF") && h.ascii(8, "WEBP") -> ReceiptKind.WEBP
            h.size >= RIFF_HEADER && h.ascii(4, "ftyp") && heifBrands.any { h.ascii(8, it) } -> ReceiptKind.HEIF
            else -> null
        }

    private fun ByteArray.startsWith(vararg prefix: Int) =
        size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }

    private fun ByteArray.ascii(
        at: Int,
        text: String,
    ) = text.indices.all { this[at + it].toInt() == text[it].code }
}

/** Why a file was not stored. */
enum class Refusal { UNSUPPORTED, TOO_LARGE, TOO_MANY_PAGES, PDF_UNREADABLE, IMAGE_UNREADABLE }

sealed interface AttachResult {
    data class Added(
        val receiptId: String,
    ) : AttachResult

    /** The same bytes are already stored; the entry's id, date and amount say where (all null if the entry is gone). */
    data class Duplicate(
        val receiptId: String,
        val expenseId: String?,
        val occurredAtMillis: Long?,
        val amountMinor: Long?,
        val currency: String?,
    ) : AttachResult

    data class Refused(
        val reason: Refusal,
    ) : AttachResult

    data object Failed : AttachResult
}
