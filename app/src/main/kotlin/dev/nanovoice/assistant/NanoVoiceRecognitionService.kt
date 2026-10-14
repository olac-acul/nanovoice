package dev.nanovoice.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import dev.nanovoice.NanoVoiceApp
import dev.nanovoice.voice.SttError
import dev.nanovoice.voice.SttEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** NanoVoice's offline Italian recognizer (Vosk) offered to other apps through Android's `SpeechRecognizer` API. */
class NanoVoiceRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val stt get() = (application as NanoVoiceApp).container.stt

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        job?.cancel()
        job = scope.launch {
            // An unexpected failure of the recognizer is an error for the caller, not a crash of the app.
            runCatching { listen(listener) }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                listener.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    private suspend fun listen(listener: Callback) {
        stt.start(endOnSilence = true).collect { event ->
            when (event) {
                SttEvent.Ready -> {
                    listener.readyForSpeech(Bundle())
                    listener.beginningOfSpeech()
                }
                is SttEvent.Partial -> if (event.text.isNotEmpty()) listener.partialResults(RecognitionBundles.of(event.text))
                SttEvent.Ended -> listener.endOfSpeech()
                is SttEvent.Final -> listener.results(RecognitionBundles.of(event.text))
                is SttEvent.Error -> listener.error(RecognitionErrors.toAndroid(event.error))
            }
        }
    }

    override fun onStopListening(listener: Callback) {
        stt.stopListening()
    }

    override fun onCancel(listener: Callback) {
        job?.cancel()
        job = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

internal object RecognitionBundles {
    fun of(text: String): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
    }
}

internal object RecognitionErrors {
    fun toAndroid(error: SttError): Int = when (error) {
        SttError.PermissionDenied -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        SttError.NoSpeech -> SpeechRecognizer.ERROR_NO_MATCH
        SttError.Busy -> SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        SttError.NotAvailable, SttError.Other -> SpeechRecognizer.ERROR_CLIENT
    }
}
