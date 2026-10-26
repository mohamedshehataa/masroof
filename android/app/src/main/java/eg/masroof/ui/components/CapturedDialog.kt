package eg.masroof.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.ui.viewmodel.CapturedViewModel

/** After an Undo of a record that a learned format made: "Stop learning this format?" (yes forgets that format). */
@Composable
fun StopLearningDialog(viewModel: CapturedViewModel) {
    val templateId by viewModel.stopLearning.collectAsStateWithLifecycle()
    if (templateId == null) return
    AlertDialog(
        onDismissRequest = { viewModel.answerStopLearning(false) },
        title = { Text(stringResource(R.string.captured_stop_learning_title)) },
        text = { Text(stringResource(R.string.captured_stop_learning_text)) },
        confirmButton = { TextButton(onClick = { viewModel.answerStopLearning(true) }) { Text(stringResource(R.string.captured_yes)) } },
        dismissButton = { TextButton(onClick = { viewModel.answerStopLearning(false) }) { Text(stringResource(R.string.captured_no)) } },
    )
}
