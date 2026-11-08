package eg.masroof.ui.edit

/** Every callback of the edit screen, so the stateless content composable takes one parameter. */
@Suppress("LongParameterList")
class EditActions(
    val onType: (String) -> Unit = {},
    val onAmount: (String) -> Unit = {},
    val onDate: (java.time.LocalDate) -> Unit = {},
    val onCategory: (String?) -> Unit = {},
    val onMerchant: (String) -> Unit = {},
    val onNotes: (String) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onAskDelete: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDismissDelete: () -> Unit = {},
    val onPaidBy: (String?) -> Unit = {},
    val onExcluded: (Boolean) -> Unit = {},
    /** "Delete both" on one side of a linked transfer between own accounts (STORY-208). */
    val onDeleteBoth: () -> Unit = {},
    /** "Unlink" a transfer or refund (STORY-208) and its Undo. */
    val onUnlink: () -> Unit = {},
    val onUndoUnlink: () -> Unit = {},
)
