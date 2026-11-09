package eg.masroof.data.auth

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One app-wide lock between "Erase all data", its start-up recovery and manual saves. A save made while an erase or a recovery
 * runs waits for it and then lands in the cleared database, so it can never be wiped half way.
 */
@Singleton
class EraseLock
    @Inject
    constructor() {
        val mutex = Mutex()
        private val generation = AtomicLong()

        /**
         * Which erase generation the app is in. A long job (a backfill) remembers it when it starts and refuses to write once it
         * changed: its work belongs to data that an erase removed.
         */
        val epoch: Long get() = generation.get()

        /** Called when an erase begins; every job that started before it is stale from now on. */
        fun bump() {
            generation.incrementAndGet()
        }
    }
