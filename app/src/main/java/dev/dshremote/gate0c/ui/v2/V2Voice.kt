package dev.dshremote.gate0c.ui.v2

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Client-side voice input (A-class feature: speech becomes text, text goes
 * through the ordinary gated Send pipeline — no new authority is created).
 * Hidden entirely when the device has no recognition service.
 */
internal class VoiceInputController(
    private val context: Context,
    private val onText: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    val available: Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!available || _listening.value) return
        val instance = SpeechRecognizer.createSpeechRecognizer(context)
        instance.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) onText(text)
                _listening.value = false
            }

            override fun onError(error: Int) {
                _listening.value = false
            }

            override fun onEndOfSpeech() = Unit
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer = instance
        _listening.value = true
        instance.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "说点什么，转写后可编辑再发送"),
        )
    }

    fun stop() {
        recognizer?.stopListening()
        _listening.value = false
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        _listening.value = false
    }
}

@Composable
internal fun rememberVoiceInput(onText: (String) -> Unit): VoiceInputController? {
    val context = LocalContext.current
    val currentOnText = rememberUpdatedState(onText)
    val controller = remember {
        VoiceInputController(context.applicationContext) { text -> currentOnText.value(text) }
    }
    DisposableEffect(Unit) {
        onDispose { controller.destroy() }
    }
    return controller.takeIf { it.available }
}
