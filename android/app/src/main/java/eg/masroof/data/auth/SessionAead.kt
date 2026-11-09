package eg.masroof.data.auth

import android.content.Context
import com.google.crypto.tink.Aead
import eg.masroof.ingest.LocalIdKey
import eg.masroof.ingest.LocalIdKeyUnavailableException
import java.io.IOException

/** The session keyset cannot be built while the legacy local-id key is still sealed under it: it must not be reset, and the blob is kept. */
class SessionKeysetHeldException(
    cause: Throwable,
) : IOException("session keyset held", cause)

/**
 * The AEAD the session store opens its blob with, built AFTER the local-id key had its chance to leave the session keyset (design D5).
 * The resetting builder may be used only when no legacy key `k` exists ([LocalIdKey.dependsOnSessionKeyset] is false); while `k`
 * exists the NON-resetting builder is used and a failure is a [SessionKeysetHeldException], never a reset.
 */
@Suppress("TooGenericExceptionCaught") // any build failure while k exists is a hold, never a reset
fun sessionAeadAfterMove(
    localIdKey: LocalIdKey,
    resetting: () -> Aead,
    safe: () -> Aead,
): Aead {
    try {
        localIdKey.get().fill(0)
    } catch (_: LocalIdKeyUnavailableException) {
        // the move is not done yet: the session keyset stays safe below
    }
    if (!localIdKey.dependsOnSessionKeyset()) return resetting()
    return try {
        safe()
    } catch (e: Exception) {
        throw SessionKeysetHeldException(e)
    }
}

/** The one place the session token cipher is wired (pinned by `SessionTokenCipherTest`). */
fun sessionTokenCipher(
    context: Context,
    localIdKey: LocalIdKey,
): LazyTokenCipher =
    LazyTokenCipher {
        AeadTokenCipher(
            sessionAeadAfterMove(
                localIdKey,
                resetting = { TinkKeystore.aead(context) },
                safe = { TinkKeystore.aeadOrThrow(context) },
            ),
        )
    }
