package eg.masroof.ui.startup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eg.masroof.R
import eg.masroof.data.db.DbStartup

/** The only UI of the v9 update: a blocking message when the database could not be updated. No retry loop while open. */
@Composable
fun StartupBlockedScreen(state: DbStartup) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = startupMessage(state),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun startupMessage(state: DbStartup): String =
    when (state) {
        is DbStartup.FreeUpStorage -> stringResource(R.string.startup_free_up_storage, state.neededMb.toInt())
        else -> stringResource(R.string.startup_could_not_finish)
    }
