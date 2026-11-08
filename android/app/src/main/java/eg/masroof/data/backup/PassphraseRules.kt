package eg.masroof.data.backup

/** Why "Continue" is disabled on the passphrase screen (REQ-SEC-067). */
sealed interface PassphraseProblem {
    data object Empty : PassphraseProblem

    /** [have] is the code point count after NFC normalisation. */
    data class TooShort(
        val have: Int,
    ) : PassphraseProblem

    data object Mismatch : PassphraseProblem
}

object PassphraseRules {
    /** Null when the passphrase is long enough and both entries are the same text (after NFC, so another composition form matches). */
    fun check(
        passphrase: String,
        again: String,
    ): PassphraseProblem? =
        when {
            passphrase.isBlank() -> PassphraseProblem.Empty
            !PassphraseKdf.isLongEnough(passphrase) -> PassphraseProblem.TooShort(PassphraseKdf.codePoints(passphrase))
            PassphraseKdf.normalise(passphrase) != PassphraseKdf.normalise(again) -> PassphraseProblem.Mismatch
            else -> null
        }
}
