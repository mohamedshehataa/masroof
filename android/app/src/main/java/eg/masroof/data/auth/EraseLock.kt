package eg.masroof.data.auth

import kotlinx.coroutines.sync.Mutex
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
    }
