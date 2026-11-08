package eg.masroof.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.data.categories.CategoryResult
import eg.masroof.data.categories.searchEmoji
import eg.masroof.data.db.CategoryEntity
import eg.masroof.ui.components.CategoryAvatar
import eg.masroof.ui.theme.LocalMoneyStyle
import eg.masroof.ui.viewmodel.CategoriesUiState
import eg.masroof.ui.viewmodel.CategoriesViewModel

private const val EMOJI_COLUMNS = 8

/** (editingId, parentId, nameEn, nameAr, emoji, onResult): the add/edit write. */
typealias SaveCategory = (String?, String?, String, String, String?, (CategoryResult) -> Unit) -> Unit

/** What the list can do; one parameter keeps the composables short. */
class CategoryActions(
    val onSave: SaveCategory = { _, _, _, _, _, _ -> },
    val onArchive: (String, Boolean) -> Unit = { _, _ -> },
    val onMove: (String, Boolean) -> Unit = { _, _ -> },
    val onDelete: (String) -> Unit = {},
    val onClearMessage: () -> Unit = {},
)

@Composable
fun CategoriesRoute(viewModel: CategoriesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CategoriesContent(
        state,
        CategoryActions(viewModel::save, viewModel::setArchived, viewModel::move, viewModel::delete, viewModel::clearMessage),
    )
}

private fun CategoryEntity.label(arabic: Boolean) = if (arabic) nameAr else nameEn

/** Settings -> Categories: grouped by parent, reorder, add/edit sheet, archive, delete when unused. */
@Composable
fun CategoriesContent(
    state: CategoriesUiState,
    actions: CategoryActions,
    modifier: Modifier = Modifier,
) {
    val arabic = LocalMoneyStyle.current.arabic
    // null = closed; an entity = editing it; NEW = adding
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var archiveAsk by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleteAsk by remember { mutableStateOf<CategoryEntity?>(null) }
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.cat_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Button(onClick = { adding = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cat_add)) }
        state.deleteBlocked?.let { blocked ->
            val msg = if (blocked == CategoryResult.SystemProtected) R.string.cat_error_system else R.string.cat_error_in_use
            Text(stringResource(msg), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        when {
            !state.loaded -> Text(stringResource(R.string.cat_loading))
            state.groups.isEmpty() -> Text(stringResource(R.string.cat_empty))
            else ->
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.groups.forEach { g ->
                        item(key = g.parent.id) {
                            CategoryRow(g.parent, 0, arabic, actions, { editing = it }, { archiveAsk = it }, { deleteAsk = it })
                        }
                        items(g.children, key = { it.id }) { c ->
                            CategoryRow(c, 1, arabic, actions, { editing = it }, { archiveAsk = it }, { deleteAsk = it })
                        }
                    }
                }
        }
    }
    if (adding || editing != null) {
        val target = editing
        CategoryEditorSheet(
            editing = target,
            parents = state.groups.map { it.parent }.filter { !it.archived },
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { parentId, en, ar, emoji, onResult ->
                actions.onSave(target?.id, parentId, en, ar, emoji) { r ->
                    if (r is CategoryResult.Ok) {
                        adding = false
                        editing = null
                    }
                    onResult(r)
                }
            },
        )
    }
    archiveAsk?.let { c ->
        AlertDialog(
            onDismissRequest = { archiveAsk = null },
            title = { Text(stringResource(R.string.cat_archive_title, c.label(arabic))) },
            text = { Text(stringResource(R.string.cat_archive_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onArchive(c.id, true)
                        archiveAsk = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.cat_archive)) }
            },
            dismissButton = {
                TextButton(onClick = { archiveAsk = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cat_cancel))
                }
            },
        )
    }
    deleteAsk?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteAsk = null },
            title = { Text(stringResource(R.string.cat_delete_title, c.label(arabic))) },
            text = { Text(stringResource(R.string.cat_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onDelete(c.id)
                        deleteAsk = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.cat_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteAsk = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cat_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryRow(
    c: CategoryEntity,
    depth: Int,
    arabic: Boolean,
    actions: CategoryActions,
    onEdit: (CategoryEntity) -> Unit,
    onArchive: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
) {
    val name = c.label(arabic)
    Column(Modifier.fillMaxWidth().padding(start = (depth * 24).dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CategoryAvatar(name, c.id, size = 40.dp, emoji = c.emoji)
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (c.archived) {
                Text(
                    stringResource(R.string.cat_archived),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val up = stringResource(R.string.cat_move_up_desc, name)
            val down = stringResource(R.string.cat_move_down_desc, name)
            val edit = stringResource(R.string.cat_edit_desc, name)
            TextButton(
                onClick = { actions.onMove(c.id, true) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = up },
            ) { Text("↑") }
            TextButton(
                onClick = { actions.onMove(c.id, false) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = down },
            ) { Text("↓") }
            TextButton(
                onClick = { onEdit(c) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = edit },
            ) { Text(stringResource(R.string.cat_edit)) }
            if (c.archived) {
                TextButton(onClick = { actions.onArchive(c.id, false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cat_restore))
                }
            } else {
                TextButton(onClick = { onArchive(c) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cat_archive))
                }
            }
            if (!c.system) {
                TextButton(onClick = { onDelete(c) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cat_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryEditorSheet(
    editing: CategoryEntity?,
    parents: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (parentId: String?, nameEn: String, nameAr: String, emoji: String?, onResult: (CategoryResult) -> Unit) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CategoryEditorContent(editing, parents, onDismiss, onSave)
    }
}

/** The add/edit form: names in both languages, parent (add only), emoji grid with keyword search. Stateless to the outside. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryEditorContent(
    editing: CategoryEntity?,
    parents: List<CategoryEntity>,
    onCancel: () -> Unit,
    onSave: (parentId: String?, nameEn: String, nameAr: String, emoji: String?, onResult: (CategoryResult) -> Unit) -> Unit,
) {
    val arabic = LocalMoneyStyle.current.arabic
    var nameEn by remember(editing?.id) { mutableStateOf(editing?.nameEn.orEmpty()) }
    var nameAr by remember(editing?.id) { mutableStateOf(editing?.nameAr.orEmpty()) }
    var emoji by remember(editing?.id) { mutableStateOf(editing?.emoji) }
    var parentId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<CategoryResult?>(null) }
    val shown = searchEmoji(query)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(if (editing == null) R.string.cat_editor_add_title else R.string.cat_editor_edit_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        val nameError = error == CategoryResult.EmptyName || error == CategoryResult.Duplicate
        val errorText = if (error == CategoryResult.Duplicate) R.string.cat_error_duplicate else R.string.cat_error_empty
        OutlinedTextField(
            value = nameEn,
            onValueChange = {
                nameEn = it
                error = null
            },
            label = { Text(stringResource(R.string.cat_name_en)) },
            isError = nameError,
            supportingText = { if (nameError) Text(stringResource(errorText)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = nameAr,
            onValueChange = {
                nameAr = it
                error = null
            },
            label = { Text(stringResource(R.string.cat_name_ar)) },
            isError = nameError,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (editing == null && parents.isNotEmpty()) {
            Text(stringResource(R.string.cat_parent), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = parentId == null,
                    onClick = { parentId = null },
                    label = { Text(stringResource(R.string.cat_parent_none)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                parents.forEach { p ->
                    FilterChip(
                        selected = parentId == p.id,
                        onClick = { parentId = p.id },
                        label = { Text(listOfNotNull(p.emoji, p.label(arabic)).joinToString(" ")) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.cat_emoji_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (shown.isEmpty()) {
            Text(stringResource(R.string.cat_emoji_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            EmojiGrid(shown.map { it.emoji }, emoji) { emoji = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cat_cancel)) }
            Button(
                onClick = { onSave(parentId, nameEn, nameAr, emoji) { error = it } },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.cat_save)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiGrid(
    options: List<String>,
    selected: String?,
    onPick: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = EMOJI_COLUMNS,
    ) {
        options.forEach { e ->
            val desc = stringResource(R.string.cat_emoji_desc, e)
            FilterChip(
                selected = selected == e,
                onClick = { onPick(e) },
                label = { Text(e, textAlign = TextAlign.Center, modifier = Modifier.size(28.dp)) },
                modifier = Modifier.semantics { contentDescription = desc },
            )
        }
    }
}
