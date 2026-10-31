package dev.nanovoice.voice

import kotlinx.coroutines.flow.StateFlow

/** [NeedsVoice]: no voice is installed yet, so nothing can be spoken. */
enum class TtsStatus { Initializing, Ready, NeedsVoice, Failed }

/** The pace assumed before any measure: what a Pixel 8 does with Serena when not hot. */
const val DEFAULT_PACE = 0.75

/** Reads text aloud. Calls to [speak] queue up in order. */
interface SpokenOutput {
    val status: StateFlow<TtsStatus>

    /** True from the first queued sentence until the queue is empty again, or [stop]. */
    val speaking: StateFlow<Boolean>

    /**
     * How long synthesis takes for each second of speech it makes, as last measured: under 1 the voice keeps up with
     * itself, over 1 (a hot phone) it cannot. Lets the caller size the pieces it queues.
     */
    val pace: Double get() = DEFAULT_PACE

    fun speak(text: String)

    /** Silences the current sentence and drops the queue (e.g. the user starts talking). */
    fun stop()

    fun shutdown()
}
