package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.NetworkMonitor
import eg.masroof.data.SettingsRepository
import eg.masroof.data.auth.LoginResult
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.normalizeServerUrl
import eg.masroof.work.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Generic on purpose: the screen never says whether the email or the password was wrong. */
enum class LoginError { INVALID_CREDENTIALS, RATE_LIMITED, NETWORK, SERVER }

data class LoginUiState(
    val serverUrl: String = "",
    val email: String = "",
    val password: String = "",
    val showPassword: Boolean = false,
    val loading: Boolean = false,
    val offline: Boolean = false,
    val urlInvalid: Boolean = false,
    val emailMissing: Boolean = false,
    val passwordMissing: Boolean = false,
    val error: LoginError? = null,
    /** Whole minutes to wait after a 429, rounded up; null when the server did not say. */
    val retryMinutes: Int? = null,
    /** Set when these credentials belong to another member and this many entries of the previous one are unsynced. */
    val confirmSwitchUnsynced: Int? = null,
)

private const val SECONDS_PER_MINUTE = 60

private fun ceilMinutes(seconds: Int): Int = ((seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).coerceAtLeast(1)

@HiltViewModel
class LoginViewModel
    @Inject
    constructor(
        private val session: SessionManager,
        private val settings: SettingsRepository,
        private val scheduler: SyncScheduler,
        network: NetworkMonitor,
    ) : ViewModel() {
        private val _state = MutableStateFlow(LoginUiState())
        val state: StateFlow<LoginUiState> = _state

        init {
            viewModelScope.launch {
                val url = settings.current().serverUrl
                _state.update { if (it.serverUrl.isEmpty()) it.copy(serverUrl = url) else it }
            }
            viewModelScope.launch { network.online.collect { online -> _state.update { it.copy(offline = !online) } } }
        }

        fun onServerUrl(text: String) = _state.update { it.copy(serverUrl = text, urlInvalid = false, error = null) }

        fun onEmail(text: String) = _state.update { it.copy(email = text, emailMissing = false, error = null) }

        fun onPassword(text: String) = _state.update { it.copy(password = text, passwordMissing = false, error = null) }

        fun toggleShowPassword() = _state.update { it.copy(showPassword = !it.showPassword) }

        fun submit() {
            val s = _state.value
            if (s.loading || s.offline) return
            val invalid =
                s.copy(
                    urlInvalid = normalizeServerUrl(s.serverUrl) == null,
                    emailMissing = s.email.isBlank(),
                    passwordMissing = s.password.isEmpty(),
                    error = null,
                )
            if (invalid.urlInvalid || invalid.emailMissing || invalid.passwordMissing) {
                _state.value = invalid // validation only: no request is made
                return
            }
            _state.value = invalid.copy(loading = true)
            viewModelScope.launch {
                when (val result = session.login(s.serverUrl, s.email, s.password)) {
                    LoginResult.Success -> signedIn()
                    is LoginResult.AccountSwitch ->
                        _state.update { it.copy(loading = false, confirmSwitchUnsynced = result.unsynced) }
                    LoginResult.InvalidUrl -> fail(urlInvalid = true)
                    LoginResult.InvalidCredentials -> fail(LoginError.INVALID_CREDENTIALS)
                    is LoginResult.RateLimited -> fail(LoginError.RATE_LIMITED, result.retryAfterSeconds?.let(::ceilMinutes))
                    LoginResult.Network -> fail(LoginError.NETWORK)
                    LoginResult.Server -> fail(LoginError.SERVER)
                }
            }
        }

        /** The user accepts deleting the previous member's unsynced entries. */
        fun confirmSwitch() {
            _state.update { it.copy(confirmSwitchUnsynced = null, loading = true) }
            viewModelScope.launch {
                session.confirmAccountSwitch()
                signedIn()
            }
        }

        fun cancelSwitch() {
            _state.update { it.copy(confirmSwitchUnsynced = null, password = "") }
            viewModelScope.launch { session.cancelAccountSwitch() }
        }

        private fun signedIn() {
            // Entries queued while signed out can go now. The screen is replaced by the session state.
            scheduler.schedule()
            _state.update { it.copy(loading = false, password = "") }
        }

        private fun fail(
            error: LoginError? = null,
            minutes: Int? = null,
            urlInvalid: Boolean = false,
        ) = _state.update { it.copy(loading = false, error = error, retryMinutes = minutes, urlInvalid = urlInvalid) }
    }
