package eg.masroof.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eg.masroof.ask.AskAnswer
import eg.masroof.ask.AskEngine
import eg.masroof.ask.AskIntro
import eg.masroof.ask.AskQuery
import eg.masroof.ask.QuestionParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One exchange. [answer] is null while it is being worked out. [question] is what the user typed or the chip's label. */
data class AskMessage(
    val id: Long,
    val question: String,
    val query: AskQuery,
    val answer: AskAnswer?,
)

data class AskState(
    /** Null until the greeting data has been read. */
    val intro: AskIntro? = null,
    val messages: List<AskMessage> = emptyList(),
)

/**
 * The conversation lives here and nowhere else: it is in memory only and gone when the screen is closed. Questions and
 * answers are never logged and nothing here reaches the network.
 */
@HiltViewModel
class AskViewModel
    @Inject
    constructor(
        private val engine: AskEngine,
    ) : ViewModel() {
        private val mutable = MutableStateFlow(AskState())
        val state: StateFlow<AskState> = mutable.asStateFlow()
        private var nextId = 0L

        init {
            viewModelScope.launch {
                val intro = readOrNull { engine.intro() } ?: AskIntro(null, emptyList())
                mutable.update { it.copy(intro = intro) }
            }
        }

        /** A typed question. */
        fun ask(text: String) {
            val question = text.trim().take(MAX_QUESTION)
            if (question.isEmpty()) return
            submit(question, QuestionParser.parse(question))
        }

        /** A chip or a tapped chart row: [label] is shown as the question, [query] is what is answered. */
        fun askQuery(
            query: AskQuery,
            label: String,
        ) = submit(label, query)

        fun retry(id: Long) {
            val message = mutable.value.messages.firstOrNull { it.id == id } ?: return
            replace(id, null)
            compute(id, message.query)
        }

        private fun submit(
            question: String,
            query: AskQuery,
        ) {
            val id = nextId++
            mutable.update { it.copy(messages = it.messages + AskMessage(id, question, query, null)) }
            compute(id, query)
        }

        private fun compute(
            id: Long,
            query: AskQuery,
        ) {
            viewModelScope.launch { replace(id, readOrNull { engine.answer(query) } ?: AskAnswer.Failed) }
        }

        private fun replace(
            id: Long,
            answer: AskAnswer?,
        ) = mutable.update { s -> s.copy(messages = s.messages.map { if (it.id == id) it.copy(answer = answer) else it }) }

        @Suppress("TooGenericExceptionCaught") // any read failure becomes the retry card, never a crash
        private suspend fun <T> readOrNull(block: suspend () -> T): T? =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

        private companion object {
            const val MAX_QUESTION = 200
        }
    }
