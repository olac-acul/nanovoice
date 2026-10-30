package dev.nanovoice.voice

import kotlinx.coroutines.flow.StateFlow

/** [NeedsVoice]: no voice is installed yet, so nothing can be spoken. */
enum class TtsStatus { Initializing, Ready, NeedsVoice, Failed }

/** Reads text aloud. Calls to [speak] queue up in order. */
interface SpokenOutput {
    val status: StateFlow<TtsStatus>

    /** True from the first queued sentence until the queue is empty again, or [stop]. */
    val speaking: StateFlow<Boolean>

    fun speak(text: String)

    /** Silences the current sentence and drops the queue (e.g. the user starts talking). */
    fun stop()

    fun shutdown()
}
