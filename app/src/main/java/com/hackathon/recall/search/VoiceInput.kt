package com.hackathon.recall.search

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.hackathon.recall.model.Lang
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Voice input (brief §4): the on-device recognizer when available, else the default recognizer with
 * EXTRA_PREFER_OFFLINE. [installedOfflineLanguages] tells the UI which of en-IN / hi-IN / te-IN can
 * work without a network; text input always works.
 */
class VoiceInput(private val context: Context) {
    sealed interface Event {
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Error(val code: Int) : Event
    }

    private var recognizer: SpeechRecognizer? = null

    val onDeviceAvailable: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun start(lang: Lang, onEvent: (Event) -> Unit) {
        stop()
        val r = if (onDeviceAvailable) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                onEvent(Event.Final(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()))
            }
            override fun onPartialResults(partial: Bundle) {
                partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onEvent(Event.Partial(it)) }
            }
            override fun onError(error: Int) = onEvent(Event.Error(error))
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(intent(lang))
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun intent(lang: Lang) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

    /** Languages with an installed on-device model (API 33+); null when the platform can't tell. */
    suspend fun installedOfflineLanguages(): List<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !onDeviceAvailable) return null
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        return try {
            suspendCancellableCoroutine { cont ->
                r.checkRecognitionSupport(intent(Lang.EN), Executors.newSingleThreadExecutor(), object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (cont.isActive) cont.resume(support.installedOnDeviceLanguages)
                    }
                    override fun onError(error: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        } finally {
            r.destroy()
        }
    }
}
