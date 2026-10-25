package eg.masroof.ingest.parse

import java.text.Normalizer

/**
 * Text hygiene before any rule runs: NFKC, one ASCII digit set, no invisible or bidi characters, unified dashes and
 * spaces; plus Arabic letter folding. Nothing here keeps the text: it only returns a cleaned copy.
 */
object TextNormalizer {
    private const val MAX_LENGTH = 4000
    private const val MAX_PASSES = 6

    fun fold(text: String): String =
        buildString(text.length) {
            for (c in text) {
                append(
                    when (c.code) {
                        0x0623, 0x0625, 0x0622, 0x0671 -> 'ا'
                        0x0649, 0x06CC -> 'ي'
                        0x0629 -> 'ه'
                        0x06A9 -> 'ك'
                        else -> c
                    },
                )
            }
        }

    /** NFKC, one ASCII digit set, no invisible or bidi characters, unified dashes/dots/spaces; idempotent, bounded. */
    fun normalizeText(input: String): String {
        var current = input
        repeat(MAX_PASSES) {
            val next = pass(current)
            if (next == current) return next
            current = next
        }
        return current
    }

    private fun pass(text: String): String {
        val folded = Normalizer.normalize(truncate(text), Normalizer.Form.NFKC)
        val out = StringBuilder(folded.length)
        var lastSpace = true
        for (c in folded) {
            val mapped = mapChar(c)
            if (mapped == DROP) continue
            if (mapped.isWhitespace() || Character.isSpaceChar(mapped)) {
                if (!lastSpace) out.append(' ')
                lastSpace = true
            } else {
                out.append(mapped)
                lastSpace = false
            }
        }
        if (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        return truncate(Normalizer.normalize(out, Normalizer.Form.NFKC))
    }

    private fun truncate(text: String): String =
        if (text.codePointCount(0, text.length) <= MAX_LENGTH) text else text.substring(0, text.offsetByCodePoints(0, MAX_LENGTH))

    private const val DROP = '\u0000'

    private fun mapChar(c: Char): Char {
        val code = c.code
        return when {
            code in 0x0660..0x0669 -> '0' + (code - 0x0660)
            code in 0x06F0..0x06F9 -> '0' + (code - 0x06F0)
            code == 0x066B -> '.'
            code == 0x066C || code == 0x060C -> ','
            code == 0x066A -> '%'
            code in DASHES || code == 0x2212 || code == 0xFE58 || code == 0xFE63 -> '-'
            code == 0x2024 || code == 0x2027 -> '.'
            code == 0x00A0 || code == 0x202F -> ' '
            isInvisible(code) -> DROP
            else -> c
        }
    }

    private val DASHES = 0x2010..0x2015

    private fun isInvisible(code: Int) =
        code == 0x0640 ||
            code == 0xFEFF ||
            code in 0x200B..0x200F ||
            code == 0x2060 ||
            code == 0x061C ||
            code in 0x064B..0x065F ||
            code == 0x0670 ||
            code in 0x06D6..0x06ED ||
            code in 0x202A..0x202E ||
            code in 0x2066..0x2069 ||
            code == 0x00AD ||
            code in 0x2061..0x2064 ||
            code == 0x034F ||
            code in 0xFE00..0xFE0F ||
            code == 0x180E
}
