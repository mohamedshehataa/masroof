package eg.masroof.ask

/** Text folding shared by the question parser and the name matching: pure Kotlin, no Android types. */
object ArabicText {
    private const val TASHKEEL_FIRST = 'ً'
    private const val TASHKEEL_LAST = 'ٟ'
    private const val SUPERSCRIPT_ALEF = 'ٰ'
    private const val TATWEEL = 'ـ'
    private const val ARABIC_INDIC_ZERO = '٠'
    private const val PERSIAN_ZERO = '۰'
    private const val AL = "ال"
    private const val MIN_LENGTH_FOR_AL = 3
    private val spaces = Regex(" +")

    /**
     * Lower-cases, strips tashkeel and tatweel, folds alef / ya / ta marbuta / hamza forms, turns Arabic-Indic and
     * Persian digits into Western ones, drops apostrophes and turns every other non-letter into one space.
     */
    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            if (isDropped(ch)) continue
            val c =
                when (ch) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ى' -> 'ي'
                    'ة' -> 'ه'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    in '٠'..'٩' -> '0' + (ch - ARABIC_INDIC_ZERO)
                    in '۰'..'۹' -> '0' + (ch - PERSIAN_ZERO)
                    else -> ch
                }
            sb.append(if (Character.isLetterOrDigit(c)) c.lowercaseChar() else ' ')
        }
        return spaces.replace(sb.toString(), " ").trim()
    }

    /** Tashkeel, tatweel and apostrophes disappear without leaving a gap. */
    private fun isDropped(ch: Char) =
        ch in TASHKEEL_FIRST..TASHKEEL_LAST || ch == SUPERSCRIPT_ALEF || ch == TATWEEL || ch == '\'' || ch == '’' || ch == '`'

    /** Drops the Arabic article from a normalised token: the article-prefixed word for "groceries" loses its first two letters. */
    fun stripAl(token: String): String = if (token.length > MIN_LENGTH_FOR_AL && token.startsWith(AL)) token.substring(AL.length) else token

    /** Normalised tokens with the article stripped. */
    fun tokens(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }.map(::stripAl)
}
