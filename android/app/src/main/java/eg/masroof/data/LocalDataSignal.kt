package eg.masroof.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** "An expense changed on this device": screens that derive figures from expenses (Insights) refresh on it. */
@Singleton
class LocalDataSignal
    @Inject
    constructor() {
        private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val events: Flow<Unit> = changes

        fun changed() {
            changes.tryEmit(Unit)
        }
    }
