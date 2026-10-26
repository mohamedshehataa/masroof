package eg.masroof.ingest.parse

/**
 * The ONE merchant/counterparty label spec, shared with the server (`ParsedTransactionService.normalizeLabel`) and
 * pinned by `fixtures/sms/labels.jsonl` which both sides test against: curly quotes become an apostrophe, the registered,
 * trademark and copyright signs are dropped, every other character outside letters, marks, space and `. & ' @ _ / * ( ) , + -`
 * becomes a space, and spaces collapse. A label that passes here can never be refused by the server's charset check, so
 * a genuine purchase is not lost to a 422.
 */
object LabelNormalizer {
    private val quotes = Regex("[‘’ʼ`´]")
    private val marks = Regex("[®™©]")
    private val disallowed = Regex("[^\\p{L}\\p{M} .&'@_/*(),+-]")
    private val spaces = Regex("\\s+")

    fun normalize(label: String): String =
        spaces.replace(disallowed.replace(marks.replace(quotes.replace(label, "'"), ""), " "), " ").trim()
}
