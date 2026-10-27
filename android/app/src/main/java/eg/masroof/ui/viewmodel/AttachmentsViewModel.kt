package eg.masroof.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.receipts.AttachmentInfo
import eg.masroof.data.receipts.MAX_ATTACHMENTS
import eg.masroof.data.receipts.Prepared
import eg.masroof.data.receipts.ReceiptFiles
import eg.masroof.data.receipts.ReceiptRemote
import eg.masroof.data.remote.HttpFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Why the last attach / remove / view did not work; one short line above the section. */
enum class AttachMessage { TOO_LARGE, UNSUPPORTED, TOO_MANY, UNREADABLE, OFFLINE, SIGN_IN, FAILED }

/** The in-app viewer: the bytes are downloaded to a cache file that is deleted when the viewer closes. */
sealed interface ViewerState {
    data object Loading : ViewerState

    data class Ready(
        val file: File,
        val isPdf: Boolean,
    ) : ViewerState

    data object Failed : ViewerState
}

data class AttachmentsUiState(
    val load: SectionLoad = SectionLoad.LOADING,
    val list: List<AttachmentInfo> = emptyList(),
    val uploading: Boolean = false,
    /** The attachment whose DELETE is in flight. */
    val removingId: String? = null,
    /** The attachment the "Remove?" dialog is about. */
    val confirmRemoveId: String? = null,
    val message: AttachMessage? = null,
    val viewer: ViewerState? = null,
) {
    val busy: Boolean
        get() = uploading || removingId != null

    val canAttach: Boolean
        get() = load == SectionLoad.LOADED && !busy && list.size < MAX_ATTACHMENTS
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_TOO_LARGE = 413
private const val HTTP_UNSUPPORTED = 415

/** The receipt attachments of the transaction being edited: list, attach (photo or PDF), view in-app, remove. Online only. */
@HiltViewModel
class AttachmentsViewModel
    @Inject
    constructor(
        private val remote: ReceiptRemote,
        private val files: ReceiptFiles,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        private val id: String = savedState.get<String>("id").orEmpty()
        private val _state = MutableStateFlow(AttachmentsUiState())
        val state: StateFlow<AttachmentsUiState> = _state.asStateFlow()
        private var pendingCamera: Uri? = null
        private var viewerJob: Job? = null
        private var viewerFile: File? = null

        init {
            files.cleanUp() // leftovers of an earlier run that was killed mid-upload or mid-view
            load()
        }

        fun load() {
            _state.update { it.copy(load = SectionLoad.LOADING) }
            viewModelScope.launch {
                try {
                    val list = remote.list(id)
                    _state.update { it.copy(load = SectionLoad.LOADED, list = list) }
                } catch (_: IOException) {
                    _state.update { it.copy(load = SectionLoad.FAILED) }
                } catch (_: HttpFailure) {
                    _state.update { it.copy(load = SectionLoad.FAILED) }
                } catch (_: SerializationException) {
                    _state.update { it.copy(load = SectionLoad.FAILED) }
                }
            }
        }

        fun dismissMessage() = _state.update { it.copy(message = null) }

        /** The URI the system camera should write the photo to. */
        fun cameraTarget(): Uri = files.newCameraTarget().also { pendingCamera = it }

        fun onCameraResult(saved: Boolean) {
            val target = pendingCamera
            pendingCamera = null
            if (saved && target != null) upload { files.prepareImage(target) } else files.cleanUp()
        }

        fun onImagePicked(uri: Uri?) {
            if (uri != null) upload { files.prepareImage(uri) }
        }

        fun onPdfPicked(uri: Uri?) {
            if (uri != null) upload { files.preparePdf(uri) }
        }

        private fun upload(prepare: suspend () -> Prepared) {
            val s = _state.value
            if (!s.canAttach) {
                if (!s.busy && s.list.size >= MAX_ATTACHMENTS) _state.update { it.copy(message = AttachMessage.TOO_MANY) }
                return
            }
            _state.update { it.copy(uploading = true, message = null) }
            viewModelScope.launch {
                var temp: File? = null
                try {
                    when (val prepared = prepare()) {
                        Prepared.TooLarge -> fail(AttachMessage.TOO_LARGE)
                        Prepared.Unreadable -> fail(AttachMessage.UNREADABLE)
                        is Prepared.Ready -> {
                            temp = prepared.file
                            val info = remote.upload(id, prepared.file, prepared.contentType)
                            _state.update { it.copy(list = it.list + info) }
                        }
                    }
                } catch (e: HttpFailure) {
                    fail(messageFor(e.code))
                } catch (_: IOException) {
                    fail(AttachMessage.OFFLINE)
                } finally {
                    temp?.delete()
                    _state.update { it.copy(uploading = false) }
                }
            }
        }

        private fun messageFor(code: Int) =
            when (code) {
                HTTP_TOO_LARGE -> AttachMessage.TOO_LARGE
                HTTP_UNSUPPORTED -> AttachMessage.UNSUPPORTED
                HTTP_CONFLICT -> AttachMessage.TOO_MANY
                HTTP_UNAUTHORIZED -> AttachMessage.SIGN_IN
                else -> AttachMessage.FAILED
            }

        private fun fail(message: AttachMessage) = _state.update { it.copy(message = message) }

        fun askRemove(attachmentId: String) = _state.update { if (it.busy) it else it.copy(confirmRemoveId = attachmentId) }

        fun dismissRemove() = _state.update { it.copy(confirmRemoveId = null) }

        /** One DELETE after the user confirmed; a 404 means it is already gone, which is what they wanted. */
        fun confirmRemove() {
            val target = _state.value.confirmRemoveId ?: return
            if (_state.value.busy) return
            _state.update { it.copy(confirmRemoveId = null, removingId = target, message = null) }
            viewModelScope.launch {
                try {
                    remote.delete(id, target)
                    removeLocally(target)
                } catch (e: HttpFailure) {
                    if (e.code == HTTP_NOT_FOUND) removeLocally(target) else fail(messageFor(e.code))
                } catch (_: IOException) {
                    fail(AttachMessage.OFFLINE)
                } finally {
                    _state.update { it.copy(removingId = null) }
                }
            }
        }

        private fun removeLocally(attachmentId: String) = _state.update { s -> s.copy(list = s.list.filterNot { it.id == attachmentId }) }

        fun view(info: AttachmentInfo) {
            if (_state.value.viewer != null) return
            _state.update { it.copy(viewer = ViewerState.Loading, message = null) }
            val dest = files.newTempFile(if (info.isPdf) ".pdf" else ".img")
            viewerFile = dest
            viewerJob =
                viewModelScope.launch {
                    try {
                        remote.download(id, info.id, dest)
                        _state.update { if (it.viewer == null) it else it.copy(viewer = ViewerState.Ready(dest, info.isPdf)) }
                    } catch (_: HttpFailure) {
                        viewerFailed()
                    } catch (_: IOException) {
                        viewerFailed()
                    }
                }
        }

        private fun viewerFailed() {
            viewerFile?.delete()
            viewerFile = null
            _state.update { if (it.viewer == null) it else it.copy(viewer = ViewerState.Failed) }
        }

        /** Closes the viewer and deletes the downloaded bytes. */
        fun closeViewer() {
            viewerJob?.cancel()
            viewerJob = null
            viewerFile?.delete()
            viewerFile = null
            _state.update { it.copy(viewer = null) }
        }

        override fun onCleared() {
            viewerFile?.delete()
            files.cleanUp()
        }
    }
