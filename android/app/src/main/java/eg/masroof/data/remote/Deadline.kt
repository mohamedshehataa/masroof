package eg.masroof.data.remote

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long a screen waits for the server before it shows its error state instead of an endless spinner. */
val SCREEN_DEADLINE: Duration = 30.seconds

/**
 * Runs [block] with a client-side deadline. Expiry becomes an [IOException] ("unreachable"), which every repository
 * already maps to its offline/error state, rather than a CancellationException that would silently end the caller.
 */
suspend fun <T> withDeadline(
    deadline: Duration = SCREEN_DEADLINE,
    block: suspend () -> T,
): T =
    try {
        withTimeout(deadline) { block() }
    } catch (e: TimeoutCancellationException) {
        throw IOException("No answer from the server within $deadline", e)
    }
