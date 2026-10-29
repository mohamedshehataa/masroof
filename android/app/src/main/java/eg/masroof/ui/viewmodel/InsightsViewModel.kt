package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.core.Money
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.RefreshOutcome
import eg.masroof.data.insights.InsightsPeriod
import eg.masroof.data.insights.InsightsRepository
import eg.masroof.ui.insights.InsightsUi
import eg.masroof.ui.insights.TrendBar
import eg.masroof.ui.insights.toUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L
private const val LOCAL_CHANGE_DEBOUNCE_MS = 2_000L

internal fun RefreshOutcome.toStatus(): LoadStatus =
    when (this) {
        RefreshOutcome.Ok -> LoadStatus.READY
        RefreshOutcome.Offline -> LoadStatus.OFFLINE
        RefreshOutcome.Failed -> LoadStatus.ERROR
        RefreshOutcome.SignIn -> LoadStatus.SIGN_IN
    }

data class InsightsState(
    val period: InsightsPeriod = InsightsPeriod.CURRENT,
    /** Last good answer for [period], from the on-device cache; null before the first successful refresh. */
    val data: InsightsUi? = null,
    val status: LoadStatus = LoadStatus.LOADING,
)

@HiltViewModel
class InsightsViewModel
    @Inject
    constructor(
        private val repository: InsightsRepository,
        localData: LocalDataSignal = LocalDataSignal(),
    ) : ViewModel() {
        private val period = MutableStateFlow(InsightsPeriod.CURRENT)
        private val status = MutableStateFlow(LoadStatus.LOADING)
        private var refreshJob: Job? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        val state: StateFlow<InsightsState> =
            combine(
                period,
                period.flatMapLatest { p -> repository.observe(p).map { it?.toUi() } },
                status,
            ) { p, data, st -> InsightsState(p, data, st) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), InsightsState())

        init {
            refresh()
            // The figures could not be computed after they were shown: the error with Retry, not "Not enough data yet".
            viewModelScope.launch { repository.observeFailures.collect { status.value = LoadStatus.ERROR } }
            // An expense created, edited or deleted on this device changes the figures: one refresh after the burst.
            @OptIn(FlowPreview::class)
            viewModelScope.launch { localData.events.debounce(LOCAL_CHANGE_DEBOUNCE_MS).collect { refresh() } }
        }

        fun selectPeriod(next: InsightsPeriod) {
            if (next == period.value) return
            period.value = next
            refresh()
        }

        private var shownOnce = false

        /**
         * The screen became visible. The first time the constructor's refresh is already running; every later time
         * (tab revisited after an expense was added, edited or deleted) refreshes once, unless one is in flight.
         * The cached figures stay on screen meanwhile, so this is cheap and never a loop.
         */
        fun onShown() {
            if (!shownOnce) {
                shownOnce = true
                return
            }
            if (refreshJob?.isActive == true) return
            refresh()
        }

        fun refresh() {
            refreshJob?.cancel()
            status.value = LoadStatus.LOADING
            val requested = period.value
            refreshJob =
                viewModelScope.launch {
                    status.value = repository.refresh(requested).toStatus()
                }
        }
    }

/** What Home shows from the cached analytics: change vs last month, a 6-month trend and the review count. */
private const val HOME_TREND_MONTHS = 6

data class HomeInsight(
    val deltaBps: Int?,
    val previousTotal: Money,
    val trend: List<TrendBar>,
    val pendingReview: Int,
)

@HiltViewModel
class HomeInsightsViewModel
    @Inject
    constructor(
        private val repository: InsightsRepository,
    ) : ViewModel() {
        val state: StateFlow<HomeInsight?> =
            repository
                .observe(InsightsPeriod.CURRENT)
                .map { snapshot ->
                    snapshot?.toUi()?.let {
                        HomeInsight(
                            it.deltaBps,
                            it.previousTotal,
                            it.trend.takeLast(HOME_TREND_MONTHS),
                            it.pendingReview,
                        )
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        init {
            refresh()
        }

        /** Best effort: Home renders from the cache whatever happens here. */
        fun refresh() {
            viewModelScope.launch { repository.refresh(InsightsPeriod.CURRENT) }
        }
    }
