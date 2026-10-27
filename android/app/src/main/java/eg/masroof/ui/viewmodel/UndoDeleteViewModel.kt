package eg.masroof.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.data.edit.ExpenseEditor
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Feeds the "Deleted - Undo" snackbar: [deleted] fires after a delete, [undo] restores that entry. */
@HiltViewModel
class UndoDeleteViewModel
    @Inject
    constructor(
        private val editor: ExpenseEditor,
    ) : ViewModel() {
        val deleted: SharedFlow<String> = editor.deleted

        fun undo(id: String) {
            viewModelScope.launch { editor.restore(id) }
        }
    }
