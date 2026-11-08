package eg.masroof.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.core.CairoTime
import eg.masroof.core.analytics.AnalyticsPeriod
import eg.masroof.data.SettingsRepository
import eg.masroof.data.export.CsvExporter
import eg.masroof.data.export.ExportRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

enum class ExportChoice { THIS_MONTH, LAST_MONTH, THIS_YEAR, CUSTOM }

sealed interface ExportStatus {
    data object Idle : ExportStatus

    data object Exporting : ExportStatus

    data class Done(
        val entries: Int,
    ) : ExportStatus

    data object Failed : ExportStatus
}

data class ExportUiState(
    val choice: ExportChoice = ExportChoice.THIS_MONTH,
    val from: LocalDate,
    val to: LocalDate,
    /** The default file name offered by the system picker. */
    val fileName: String,
    /** The custom range ends before it starts: Export is off and the message shows. */
    val endBeforeStart: Boolean = false,
    val status: ExportStatus = ExportStatus.Idle,
) {
    val canExport: Boolean get() = !endBeforeStart && status != ExportStatus.Exporting
}

private data class ExportInputs(
    val choice: ExportChoice,
    val customFrom: LocalDate,
    val customTo: LocalDate,
    val status: ExportStatus,
)

/** Settings -> "Export CSV" (STORY-185): range choice, the picker hand-off and the result. Reads Room only; no network. */
@HiltViewModel
class ExportCsvViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val exporter: CsvExporter,
        private val clock: Clock,
    ) : ViewModel() {
        private val inputs =
            CairoTime.today(clock.instant()).let { today ->
                MutableStateFlow(ExportInputs(ExportChoice.THIS_MONTH, today, today, ExportStatus.Idle))
            }

        val state: StateFlow<ExportUiState> =
            combine(inputs, settings.settings) { i, s -> resolve(i, s.monthStartDay) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), resolve(inputs.value, 1))

        fun choose(choice: ExportChoice) = inputs.update { it.copy(choice = choice, status = ExportStatus.Idle) }

        fun setCustomFrom(date: LocalDate) = inputs.update { it.copy(customFrom = date, status = ExportStatus.Idle) }

        fun setCustomTo(date: LocalDate) = inputs.update { it.copy(customTo = date, status = ExportStatus.Idle) }

        fun dismissResult() = inputs.update { it.copy(status = ExportStatus.Idle) }

        /**
         * Writes the chosen range to the stream [open] returns (the document the user picked). [discard] removes a half-written
         * file after a failure. Cancelling the picker never calls this, so nothing is written or shown.
         */
        fun export(
            open: () -> OutputStream?,
            discard: () -> Unit = {},
        ) {
            if (inputs.value.status == ExportStatus.Exporting) return
            viewModelScope.launch {
                val resolved = resolve(inputs.value, settings.current().monthStartDay)
                if (resolved.endBeforeStart) return@launch
                inputs.update { it.copy(status = ExportStatus.Exporting) }
                // Never keep the exception text: it can carry a file path or a value. Only the outcome is kept.
                val result =
                    runCatching {
                        val stream = open() ?: throw IOException("no output stream")
                        stream.use { exporter.export(ExportRange(resolved.from, resolved.to), it) }
                    }.getOrElse {
                        if (it is CancellationException) throw it
                        runCatching(discard)
                        null
                    }
                inputs.update { it.copy(status = if (result == null) ExportStatus.Failed else ExportStatus.Done(result)) }
            }
        }

        private fun resolve(
            i: ExportInputs,
            monthStartDay: Int,
        ): ExportUiState {
            val today = CairoTime.today(clock.instant())
            val month = AnalyticsPeriod.currentMonth(today, monthStartDay)
            val (from, to, name) =
                when (i.choice) {
                    ExportChoice.THIS_MONTH -> Triple(month.from, month.to, month.label)
                    ExportChoice.LAST_MONTH -> month.previous().let { Triple(it.from, it.to, it.label) }
                    ExportChoice.THIS_YEAR ->
                        Triple(LocalDate.of(today.year, 1, 1), LocalDate.of(today.year, 12, 31), today.year.toString())
                    ExportChoice.CUSTOM -> Triple(i.customFrom, i.customTo, "${i.customFrom}_${i.customTo}")
                }
            return ExportUiState(
                choice = i.choice,
                from = from,
                to = to,
                fileName = "masroof-$name.csv",
                endBeforeStart = to.isBefore(from),
                status = i.status,
            )
        }
    }
