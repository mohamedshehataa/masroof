package eg.masroof.ui.add

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** What the recognizer reports while the user speaks. Text is never logged or stored by the app. */
sealed interface VoiceEvent {
    /** Microphone level, 0..100, for the level indicator. */
    class Level(
        val percent: Int,
    ) : VoiceEvent

    class Text(
        val text: String,
    ) : VoiceEvent

    /** Silence or nothing recognised. */
    data object NoSpeech : VoiceEvent

    /** The recognizer failed or is not there any more. */
    data object Failed : VoiceEvent
}

/** Speech to text behind an interface, so the form logic is tested with a fake (STORY-200, REQ-AND-144). */
interface VoiceInput {
    /** False when the phone has no recognizer at all: the mic is then hidden. */
    fun isAvailable(): Boolean

    /** True when the phone's own recognizer works offline; false means its speech service may send audio online. */
    fun isOnDevice(): Boolean

    /** Starts listening in [languageTag] (`ar-EG` or `en-US`); events arrive on the main thread. */
    fun start(
        languageTag: String,
        onEvent: (VoiceEvent) -> Unit,
    )

    /** Stop listening and deliver what was heard so far. */
    fun stop()

    /** Drop everything: nothing is delivered. */
    fun cancel()
}

/**
 * The phone's recognizer: the on-device one on Android 12+ when it is installed, else the default recognizer asked to stay
 * offline. The app never records or keeps audio; the recognizer service hands back text only.
 */
class AndroidVoiceInput(
    private val context: Context,
) : VoiceInput {
    private var recognizer: SpeechRecognizer? = null

    override fun isOnDevice(): Boolean = onDevice()

    private fun onDevice() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun isAvailable(): Boolean = onDevice() || SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(
        languageTag: String,
        onEvent: (VoiceEvent) -> Unit,
    ) {
        begin(languageTag, onEvent, preferOnDevice = true)
    }

    private fun begin(
        languageTag: String,
        onEvent: (VoiceEvent) -> Unit,
        preferOnDevice: Boolean,
    ) {
        cancel()
        val useOnDevice = preferOnDevice && onDevice()
        val created =
            try {
                if (useOnDevice) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } catch (_: UnsupportedOperationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        if (created == null) {
            onEvent(VoiceEvent.Failed)
            return
        }
        recognizer = created
        // The on-device recognizer may lack this language: one retry with the default recognizer, never a loop.
        val retry = if (useOnDevice) ({ begin(languageTag, onEvent, preferOnDevice = false) }) else null
        created.setRecognitionListener(Listener(onEvent, retry))
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            created.startListening(intent)
        } catch (_: SecurityException) {
            release()
            onEvent(VoiceEvent.Failed)
        }
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private inner class Listener(
        private val emit: (VoiceEvent) -> Unit,
        private val retry: (() -> Unit)?,
    ) : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
            release()
            emit(if (heard == null) VoiceEvent.NoSpeech else VoiceEvent.Text(heard))
        }

        override fun onError(error: Int) {
            release()
            val unsupported = error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE
            if (unsupported && retry != null) {
                retry.invoke()
                return
            }
            val silent = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            emit(if (silent) VoiceEvent.NoSpeech else VoiceEvent.Failed)
        }

        // The recognizer reports roughly -2..10 dB; the indicator wants 0..100.
        override fun onRmsChanged(rmsdB: Float) {
            val percent = ((rmsdB.toInt() + 2) * LEVEL_STEP).coerceIn(0, 100)
            emit(VoiceEvent.Level(percent))
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) = Unit
    }

    private companion object {
        const val LEVEL_STEP = 8
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
