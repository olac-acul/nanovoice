package dev.nanovoice

import dev.nanovoice.voice.SpeechToText
import dev.nanovoice.voice.SpokenOutput
import dev.nanovoice.voice.SttError
import dev.nanovoice.voice.SttEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class VoiceUiState(
    /** The microphone is open (from the start until the final text or an error). */
    val listening: Boolean = false,
    /** Listening was stopped: the text of what was said is being worked out. */
    val finishing: Boolean = false,
    /** What has been understood so far. */
    val partial: String = "",
    /** Set after a failed attempt; cleared by the next press. */
    val error: SttError? = null,
)

/** The message shown for a failed listening attempt. */
internal fun sttErrorText(error: SttError): Int = when (error) {
    SttError.NotAvailable -> R.string.voice_error_unavailable
    SttError.PermissionDenied -> R.string.voice_error_permission
    SttError.NoSpeech -> R.string.voice_error_nospeech
    SttError.Busy -> R.string.voice_error_busy
    SttError.Other -> R.string.voice_error_other
}

/** Tap to talk: [listen] opens the microphone, a pause or [finish] ends the utterance and hands the text to [onUtterance]. */
class VoiceController(
    private val scope: CoroutineScope,
    private val engine: () -> SpeechToText,
    private val spoken: SpokenOutput,
    private val onUtterance: (String) -> Unit,
) {
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state

    private var job: Job? = null
    private var active: SpeechToText? = null
    private var ready = false

    /** Starts listening and ends the utterance by itself after a pause; [finish] ends it sooner. */
    fun listen() {
        if (job?.isActive == true) return
        spoken.stop() // the user takes the floor
        val stt = engine()
        active = stt
        ready = false
        _state.value = VoiceUiState(listening = true)
        job = scope.launch {
            try {
                stt.start(endOnSilence = true).collect { event ->
                    when (event) {
                        SttEvent.Ready -> ready = true
                        is SttEvent.Partial -> _state.value = _state.value.copy(partial = event.text)
                        SttEvent.Ended -> _state.value = _state.value.copy(finishing = true)
                        is SttEvent.Final -> {
                            _state.value = VoiceUiState()
                            onUtterance(event.text)
                        }
                        is SttEvent.Error -> _state.value = VoiceUiState(error = event.error)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Whatever the recognizer throws is a failed attempt to show, not a crash of the app.
                _state.value = VoiceUiState(error = SttError.Other)
                return@launch
            }
            if (_state.value.listening) _state.value = VoiceUiState() // flow ended without a result
        }
    }

    /** Stops listening; the text follows once it is worked out. */
    fun finish() {
        if (job?.isActive != true) return
        // Stopped before the engine was ready: nothing was said, discard instead of waiting.
        if (!ready) {
            cancel()
        } else {
            _state.value = _state.value.copy(finishing = true)
            active?.stopListening()
        }
    }

    /** The message of a failed attempt goes once something else was done (a typed request, a photo sent). */
    fun clearError() {
        if (_state.value.error != null && !_state.value.listening) _state.value = _state.value.copy(error = null)
    }

    /** Throws the audio away without producing text. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = VoiceUiState()
    }
}
