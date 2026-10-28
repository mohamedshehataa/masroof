package eg.masroof.data.insights

import eg.masroof.data.RefreshOutcome
import eg.masroof.data.remote.HttpFailure
import eg.masroof.data.remote.apiJson
import eg.masroof.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Network seam for the four analytics reads; faked in tests. */
interface InsightsRemote {
    /** @throws IOException when unreachable, [HttpFailure] on a non-2xx response. */
    suspend fun fetch(
        period: InsightsPeriod,
        nowMillis: Long,
    ): InsightsSnapshot
}

/** Persistent key-value store for cached JSON documents (the last good answer per period). */
interface InsightsCache {
    fun read(key: String): String?

    fun write(
        key: String,
        json: String,
    )

    fun clear()
}

/** [InsightsCache] as files in the app's private storage: atomic replace, removed on logout. */
class FileInsightsCache(
    private val dir: File,
) : InsightsCache {
    private fun file(key: String) = File(dir, "insights-$key.json")

    @Synchronized
    override fun read(key: String): String? = file(key).takeIf { it.isFile }?.readText()

    @Synchronized
    override fun write(
        key: String,
        json: String,
    ) {
        dir.mkdirs()
        val tmp = File(dir, "insights-$key.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file(key))) {
            file(key).delete()
            tmp.renameTo(file(key))
        }
    }

    @Synchronized
    override fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

interface InsightsRepository {
    /** Last cached snapshot for [period] (null before the first successful refresh); re-emits after each refresh. */
    fun observe(period: InsightsPeriod): Flow<InsightsSnapshot?>

    suspend fun refresh(period: InsightsPeriod): RefreshOutcome

    fun clear()

    /** Emits when an observed computation failed after [observe] started; the screens then show the error state. */
    val observeFailures: Flow<Unit> get() = kotlinx.coroutines.flow.emptyFlow()
}

@Singleton
class DefaultInsightsRepository
    @Inject
    constructor(
        private val cache: InsightsCache,
        private val remote: InsightsRemote,
        private val clock: Clock,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) : InsightsRepository {
        private val version = MutableStateFlow(0)

        override fun observe(period: InsightsPeriod): Flow<InsightsSnapshot?> =
            version
                .map { decode(cache.read(period.spec)) }
                .flowOn(io)
                .distinctUntilChanged()

        override suspend fun refresh(period: InsightsPeriod): RefreshOutcome =
            try {
                val snapshot = remote.fetch(period, clock.millis())
                withContext(io) {
                    cache.write(period.spec, apiJson.encodeToString(InsightsSnapshot.serializer(), snapshot))
                }
                version.update { it + 1 }
                RefreshOutcome.Ok
            } catch (_: IOException) {
                RefreshOutcome.Offline
            } catch (e: HttpFailure) {
                if (e.code == UNAUTHORIZED) RefreshOutcome.SignIn else RefreshOutcome.Failed
            } catch (_: SerializationException) {
                RefreshOutcome.Failed
            }

        override fun clear() {
            cache.clear()
            version.update { it + 1 }
        }

        private fun decode(json: String?): InsightsSnapshot? =
            json?.let {
                try {
                    apiJson.decodeFromString(InsightsSnapshot.serializer(), it)
                } catch (_: SerializationException) {
                    null // written by an older build: treat as not cached
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

        private companion object {
            const val UNAUTHORIZED = 401
        }
    }
