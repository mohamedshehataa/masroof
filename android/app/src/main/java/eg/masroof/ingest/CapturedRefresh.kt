package eg.masroof.ingest

import eg.masroof.data.LocalDataSignal
import javax.inject.Inject
import javax.inject.Singleton

/** What happens once records captured on this phone reached the server: the rows it made must show up in the app. */
interface UploadedRecordsRefresher {
    suspend fun refreshAfterUpload()
}

object NoUploadedRecordsRefresher : UploadedRecordsRefresher {
    override suspend fun refreshAfterUpload() = Unit
}

/**
 * Tells screens that derive figures from expenses that something changed. It no longer pulls the expense list from the server
 * (REQ-AND-130: no `/api/expenses*` call); rows the server made from an upload are not fetched until STORY-171 writes captures
 * locally.
 */
@Singleton
class ExpenseListRefresher
    @Inject
    constructor(
        private val signal: LocalDataSignal,
    ) : UploadedRecordsRefresher {
        override suspend fun refreshAfterUpload() {
            signal.changed()
        }
    }
