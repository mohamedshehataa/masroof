package eg.masroof.data.auth

import com.google.crypto.tink.Aead
import eg.masroof.ingest.LocalIdKey
import eg.masroof.ingest.LocalIdKeyUnavailableException

/**
 * The AEAD the session store opens its blob with, built AFTER the local-id key has left the session keyset (STORY-182). The key is
 * moved first ([LocalIdKey.get] migrates it and the copy is zeroed). While the move cannot be done (a busy Keystore) the session
 * keyset must not be reset by a transient failure, because the legacy key is still sealed under it: the non-resetting [safe] builder
 * is used then, and the resetting [resetting] one only once the move is done.
 */
fun sessionAeadAfterMove(
    localIdKey: LocalIdKey,
    resetting: () -> Aead,
    safe: () -> Aead,
): Aead {
    val moved =
        try {
            localIdKey.get().fill(0)
            true
        } catch (_: LocalIdKeyUnavailableException) {
            false
        }
    return if (moved) resetting() else safe()
}
