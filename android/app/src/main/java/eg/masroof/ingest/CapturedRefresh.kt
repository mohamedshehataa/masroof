package eg.masroof.ingest

import eg.masroof.data.ExpenseRepository
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
 * Pulls the expense list once (the server made the INCOME/TRANSFER/EXPENSE rows) and tells screens that derive figures from
 * expenses. It uses the expense repository only, never the SMS uploader, so a refresh cannot start another upload pass.
 */
@Singleton
class ExpenseListRefresher
    @Inject
    constructor(
        private val expenses: ExpenseRepository,
        private val signal: LocalDataSignal,
    ) : UploadedRecordsRefresher {
        override suspend fun refreshAfterUpload() {
            // an offline or failed refresh is fine: the next normal sync fetches the rows; the signal is sent either way
            expenses.refresh()
            signal.changed()
        }
    }
