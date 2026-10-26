package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.SettingsRepository
import eg.masroof.data.members.CreatedInvite
import eg.masroof.data.members.InviteInfo
import eg.masroof.data.members.MemberInfo
import eg.masroof.data.members.MembersException
import eg.masroof.data.members.MembersFailure
import eg.masroof.data.members.MembersRemote
import eg.masroof.data.members.inviteLink
import eg.masroof.data.normalizeServerUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MembersError { CONFLICT, INVALID, OFFLINE, OTHER }

/** The code and link are held only while the "created" panel is open; [dismissCreated] drops them. */
data class MembersUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val members: List<MemberInfo> = emptyList(),
    val isAdmin: Boolean = false,
    val invites: List<InviteInfo> = emptyList(),
    val name: String = "",
    val email: String = "",
    val creating: Boolean = false,
    val created: CreatedInvite? = null,
    val link: String? = null,
    val error: MembersError? = null,
) {
    /** The link carries the invite code, so it is never printed. */
    override fun toString(): String = "MembersUiState(members=${members.size}, invites=${invites.size}, isAdmin=$isAdmin)"
}

@HiltViewModel
class MembersViewModel
    @Inject
    constructor(
        private val remote: MembersRemote,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(MembersUiState())
        val state: StateFlow<MembersUiState> = _state

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                _state.value = _state.value.copy(loading = true, loadFailed = false)
                val members =
                    try {
                        remote.members()
                    } catch (_: MembersException) {
                        _state.value = _state.value.copy(loading = false, loadFailed = true)
                        return@launch
                    }
                // The admin flag comes from the member's own role, not from whether invites could be listed.
                val me = runCatching { remote.myMemberId() }.getOrNull()
                val isAdmin = members.any { it.id == me && it.isAdmin }
                val invites =
                    if (isAdmin) {
                        try {
                            remote.invites()
                        } catch (_: MembersException) {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                _state.value =
                    _state.value.copy(
                        loading = false,
                        members = members,
                        isAdmin = isAdmin,
                        invites = invites,
                    )
            }
        }

        fun onName(text: String) {
            _state.value = _state.value.copy(name = text.take(MAX_NAME), error = null)
        }

        fun onEmail(text: String) {
            _state.value = _state.value.copy(email = text.trim(), error = null)
        }

        fun create() {
            val s = _state.value
            val name = s.name.trim()
            if (s.creating || name.isEmpty()) return
            viewModelScope.launch {
                _state.value = _state.value.copy(creating = true, error = null)
                try {
                    val created = remote.createInvite(name, s.email.ifBlank { null })
                    val base = normalizeServerUrl(settings.current().serverUrl).orEmpty()
                    _state.value =
                        _state.value.copy(
                            creating = false,
                            name = "",
                            email = "",
                            created = created,
                            link = inviteLink(base, created.code),
                        )
                    reloadInvites()
                } catch (e: MembersException) {
                    _state.value = _state.value.copy(creating = false, error = e.failure.toUi())
                }
            }
        }

        /** Closes the one-time panel and forgets the code. */
        fun dismissCreated() {
            _state.value = _state.value.copy(created = null, link = null)
        }

        fun revoke(id: String) {
            viewModelScope.launch {
                try {
                    remote.revokeInvite(id)
                } catch (e: MembersException) {
                    _state.value = _state.value.copy(error = e.failure.toUi())
                    return@launch
                }
                reloadInvites()
            }
        }

        private suspend fun reloadInvites() {
            try {
                _state.value = _state.value.copy(invites = remote.invites())
            } catch (_: MembersException) {
                // keep the list we have; the next refresh corrects it
            }
        }

        private fun MembersFailure.toUi(): MembersError =
            when (this) {
                MembersFailure.Conflict -> MembersError.CONFLICT
                MembersFailure.Invalid -> MembersError.INVALID
                MembersFailure.Offline -> MembersError.OFFLINE
                else -> MembersError.OTHER
            }

        private companion object {
            const val MAX_NAME = 80
        }
    }
