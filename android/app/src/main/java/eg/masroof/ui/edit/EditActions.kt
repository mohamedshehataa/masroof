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
    val onRetryItems: () -> Unit = {},
    val onAddItem: () -> Unit = {},
    val onItemName: (Long, String) -> Unit = { _, _ -> },
    val onItemQty: (Long, String) -> Unit = { _, _ -> },
    val onItemAmount: (Long, String) -> Unit = { _, _ -> },
    val onRemoveItem: (Long) -> Unit = {},
)

/** Every callback of the receipts section and its viewer. */
@Suppress("LongParameterList")
class ReceiptActions(
    val onCamera: () -> Unit = {},
    val onGallery: () -> Unit = {},
    val onPdf: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onView: (eg.masroof.data.receipts.AttachmentInfo) -> Unit = {},
    val onAskRemove: (String) -> Unit = {},
    val onConfirmRemove: () -> Unit = {},
    val onDismissRemove: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
    val onCloseViewer: () -> Unit = {},
)
