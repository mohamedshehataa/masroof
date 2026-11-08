package eg.masroof.ui.settings

/** Actions of the Backup section; a plain class so screens and tests can pass fakes. */
@Suppress("LongParameterList")
class BackupActions(
    val onBackUpNow: () -> Unit,
    val onUnderstood: (Boolean) -> Unit,
    val onContinueExplain: () -> Unit,
    val onPassphrase: (String) -> Unit,
    val onAgain: (String) -> Unit,
    val onContinuePassphrase: () -> Unit,
    val onDismiss: () -> Unit,
    val onPicked: (android.net.Uri?) -> Unit,
    val onCancelBackup: () -> Unit,
    val onAcknowledge: () -> Unit,
)
