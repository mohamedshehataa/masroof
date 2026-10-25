package eg.masroof.work

import kotlin.random.Random

/**
 * Wait before the next outbox attempt after a transient failure (network down, 5xx, 408, 429): exponential from 30 s
 * with +-20% jitter, never above a 6 h ceiling, and never below a server-sent Retry-After (itself clamped to the ceiling).
 * Integer math only. Pure, so it is unit tested without WorkManager.
 */
object OutboxBackoff {
    const val BASE_SECONDS = 30L
    const val CEILING_SECONDS = 6L * 60 * 60
    private const val MAX_DOUBLINGS = 20
    private const val JITTER_MIN_PERCENT = 80
    private const val JITTER_SPAN_PERCENT = 41 // 80..120 inclusive
    private const val PERCENT = 100L

    fun delaySeconds(
        attempt: Int,
        retryAfterSeconds: Long? = null,
        random: Random = Random.Default,
    ): Long {
        val exponential = (BASE_SECONDS shl attempt.coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(CEILING_SECONDS)
        val jittered = exponential * (JITTER_MIN_PERCENT + random.nextInt(JITTER_SPAN_PERCENT)) / PERCENT
        val floor = (retryAfterSeconds ?: 0L).coerceIn(0L, CEILING_SECONDS)
        return maxOf(jittered, floor).coerceAtMost(CEILING_SECONDS)
    }
}
