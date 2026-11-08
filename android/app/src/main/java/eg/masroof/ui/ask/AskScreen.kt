package eg.masroof.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eg.masroof.R
import eg.masroof.ask.AskAnswer
import eg.masroof.ask.AskChip
import eg.masroof.ask.AskQuery
import eg.masroof.ask.CategoryShare
import eg.masroof.ask.EntryLine
import eg.masroof.ask.MerchantStat
import eg.masroof.core.Money
import eg.masroof.core.PercentFormat
import eg.masroof.ui.components.AmountText
import eg.masroof.ui.insights.BarChart
import eg.masroof.ui.insights.BarItem
import eg.masroof.ui.insights.CategoryDonut
import eg.masroof.ui.insights.DonutSlice
import eg.masroof.ui.theme.CategoryPalette
import eg.masroof.ui.theme.LocalMoneyStyle

private const val MAX_INPUT = 200
private const val DONUT_DP = 140
private const val BARS_DP = 96
private const val BUBBLE_MAX_DP = 280

@Composable
fun AskRoute(
    onBack: () -> Unit,
    onOpenExpense: (String) -> Unit,
    viewModel: AskViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AskContent(
        state,
        onSend = viewModel::ask,
        onQuery = viewModel::askQuery,
        onRetry = viewModel::retry,
        onOpenExpense = onOpenExpense,
        onBack = onBack,
    )
}

@Composable
fun AskContent(
    state: AskState,
    onSend: (String) -> Unit,
    onQuery: (AskQuery, String) -> Unit,
    onRetry: (Long) -> Unit,
    onOpenExpense: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalMoneyStyle.current
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val texts = remember(resources, style, configuration) { AskTexts(resources, style) }
    val listState = rememberLazyListState()
    val answered = state.messages.count { it.answer != null }
    LaunchedEffect(state.messages.size, answered) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.size)
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ask_back))
            }
            Text(
                stringResource(R.string.ask_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") { IntroCard(state, texts, onQuery) }
            items(state.messages, key = { it.id }) { m ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuestionBubble(m.question)
                    AnswerCard(m, texts, onQuery, onRetry, onOpenExpense)
                }
            }
        }
        InputBar(onSend)
    }
}

@Composable
private fun InputBar(onSend: (String) -> Unit) {
    // Plain remember: the question text must never reach the saved-state bundle
    var text by remember { mutableStateOf("") }
    val send = {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }
    Row(Modifier.fillMaxWidth().imePadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_INPUT) },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.ask_input_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        IconButton(onClick = { send() }, enabled = text.isNotBlank(), modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.ask_send))
        }
    }
}

@Composable
private fun IntroCard(
    state: AskState,
    texts: AskTexts,
    onQuery: (AskQuery, String) -> Unit,
) {
    val intro = state.intro
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ask_greeting), style = MaterialTheme.typography.titleMedium)
                if (intro != null) {
                    val fact = intro.topMerchant
                    Text(
                        if (fact != null) texts.greetingFact(fact) else stringResource(R.string.ask_greeting_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (intro != null) Chips(intro.chips, texts, onQuery)
        }
    }
}

@Composable
private fun QuestionBubble(question: String) {
    val label = stringResource(R.string.ask_you_asked, question)
    // End is the right edge in LTR and the left edge in RTL.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = BUBBLE_MAX_DP.dp).semantics { contentDescription = label },
        ) {
            Text(
                question,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AnswerCard(
    message: AskMessage,
    texts: AskTexts,
    onQuery: (AskQuery, String) -> Unit,
    onRetry: (Long) -> Unit,
    onOpenExpense: (String) -> Unit,
) {
    val answer = message.answer
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (answer) {
                null ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.ask_thinking), style = MaterialTheme.typography.bodyMedium)
                    }
                AskAnswer.Failed -> {
                    Text(stringResource(R.string.ask_failed), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { onRetry(message.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.ask_retry))
                    }
                }
                else -> AnswerBody(answer, texts, onQuery, onOpenExpense)
            }
        }
    }
}

@Composable
private fun AnswerBody(
    answer: AskAnswer,
    texts: AskTexts,
    onQuery: (AskQuery, String) -> Unit,
    onOpenExpense: (String) -> Unit,
) {
    val t = texts.text(answer)
    // The words come first and are read as one block; the chart and the lists follow.
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        t.title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(t.headline, style = MaterialTheme.typography.titleMedium)
        t.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    when (answer) {
        is AskAnswer.Breakdown -> if (answer.shares.isNotEmpty()) BreakdownChart(answer.shares, texts, onQuery)
        is AskAnswer.Compare -> CompareChart(answer, texts)
        is AskAnswer.TopMerchants -> answer.merchants.forEach { MerchantRow(it, texts) }
        is AskAnswer.SpendTotal -> answer.entries.forEach { EntryRow(it, texts, onOpenExpense) }
        is AskAnswer.EntryList -> answer.entries.forEach { EntryRow(it, texts, onOpenExpense) }
        is AskAnswer.Biggest -> answer.entry?.let { EntryRow(it, texts, onOpenExpense) }
        else -> Unit
    }
    Chips(answer.followUps, texts, onQuery)
}

@Composable
private fun BreakdownChart(
    shares: List<CategoryShare>,
    texts: AskTexts,
    onQuery: (AskQuery, String) -> Unit,
) {
    val style = LocalMoneyStyle.current
    val description =
        stringResource(
            R.string.ask_donut_desc,
            shares.joinToString(", ") {
                texts.shareName(it) + " " + PercentFormat.format(it.percent * PERCENT_TO_BPS, style.arabic, style.digits)
            },
        )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryDonut(
            slices =
                shares.map {
                    DonutSlice(
                        if (it.isOther) CategoryPalette.neutral else CategoryPalette.colorFor(it.categoryId),
                        it.bps,
                    )
                },
            description = description,
            size = DONUT_DP.dp,
        )
        shares.forEach { ShareRow(it, texts, onQuery) }
    }
}

private const val PERCENT_TO_BPS = 100

@Composable
private fun ShareRow(
    share: CategoryShare,
    texts: AskTexts,
    onQuery: (AskQuery, String) -> Unit,
) {
    val style = LocalMoneyStyle.current
    val name = texts.shareName(share)
    val percent = PercentFormat.format(share.percent * PERCENT_TO_BPS, style.arabic, style.digits)
    val spoken = stringResource(R.string.ask_share_row, name, percent, texts.money(share.amountMinor))
    val query = share.query
    val click = if (query != null) Modifier.clickable(role = Role.Button) { onQuery(query, name) } else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(click)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val color = if (share.isOther) CategoryPalette.neutral else CategoryPalette.colorFor(share.categoryId)
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(percent, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
        AmountText(Money(share.amountMinor), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CompareChart(
    a: AskAnswer.Compare,
    texts: AskTexts,
) {
    val prev = texts.barLabel(a.previous)
    val cur = texts.barLabel(a.current)
    BarChart(
        items = listOf(BarItem(prev, a.previousMinor, false), BarItem(cur, a.currentMinor, true)),
        description = stringResource(R.string.ask_bars_desc, prev, texts.money(a.previousMinor), cur, texts.money(a.currentMinor)),
        height = BARS_DP.dp,
    )
}

@Composable
private fun MerchantRow(
    m: MerchantStat,
    texts: AskTexts,
) {
    val spoken = texts.topRow(m)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(texts.visits(m.visits), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AmountText(Money(m.totalMinor), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EntryRow(
    e: EntryLine,
    texts: AskTexts,
    onOpenExpense: (String) -> Unit,
) {
    val spoken = texts.entryDescription(e)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { onOpenExpense(e.id) }
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(texts.entryTitle(e), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(texts.entryDate(e), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AmountText(Money(e.amountMinor), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(
    chips: List<AskChip>,
    texts: AskTexts,
    onQuery: (AskQuery, String) -> Unit,
) {
    if (chips.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chips.forEach { chip ->
            val text = texts.chipLabel(chip)
            AssistChip(
                onClick = { onQuery(chip.query, text) },
                label = { Text(text) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}
