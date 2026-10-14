package dev.nanovoice.voice

import kotlinx.coroutines.flow.Flow

enum class SttError { NotAvailable, PermissionDenied, NoSpeech, Busy, Other }

sealed interface SttEvent {
    /** The engine is listening: the user can speak now. */
    data object Ready : SttEvent

    /** What has been recognized so far; replaced by every new partial. */
    data class Partial(val text: String) : SttEvent

    /** The microphone is closed (a pause, or stopListening); the text follows once it is worked out. */
    data object Ended : SttEvent

    /** The recognized utterance. The flow ends after this. */
    data class Final(val text: String) : SttEvent

    /** The flow ends after this. */
    data class Error(val error: SttError, val detail: String? = null) : SttEvent
}

/** Push-to-talk speech recognition. Collect [start] to listen; cancelling the collection discards the audio. */
interface SpeechToText {
    /** Stable identifier used in settings: "system", "vosk". */
    val id: String

    fun isAvailable(): Boolean

    /**
     * Listens until [stopListening], then emits [SttEvent.Final] or an error. With [endOnSilence] the engine decides
     * when the user has finished (a pause after speech), and gives up if nothing is said for a while: this is the
     * mode for the assistant, where there is no button to release.
     */
    fun start(endOnSilence: Boolean = false): Flow<SttEvent>

    /** The user released the button: finish and deliver what was heard. */
    fun stopListening()
}
