package dev.nanovoice.voice

import android.os.SystemClock
import android.util.Log

/**
 * The delay the person feels: from the end of their speech to the first sound of the answer. Speech recognition marks
 * the end of speech, then the moment the text is ready (after the optional second pass); the speech output marks the
 * first audio it plays. Both are logged under [TAG] (`adb logcat -s NanoVoiceLatency`), as milliseconds only, never
 * with the words.
 */
object SpeechLatency {
    const val TAG = "NanoVoiceLatency"
    private const val STALE_MS = 60_000L

    @Volatile private var endOfSpeechAt = 0L

    /** The microphone closed: the person has finished speaking. */
    fun endOfSpeech(now: Long = SystemClock.elapsedRealtime()) {
        endOfSpeechAt = now
    }

    /** The text of what was said is ready: [secondPass] tells whether it was transcribed again. */
    fun textReady(secondPass: Boolean, now: Long = SystemClock.elapsedRealtime()) {
        val since = since(now) ?: return
        log("end of speech -> text ready: $since ms${if (secondPass) " (with the second pass)" else ""}")
    }

    /** The first audio of an answer starts playing. Logged once per utterance; an old mark is ignored. */
    fun firstAudio(now: Long = SystemClock.elapsedRealtime()) {
        val since = since(now) ?: return
        endOfSpeechAt = 0L
        log("end of speech -> first audio: $since ms")
    }

    /** Milliseconds since the end of speech, or null when there is none recent (a reply nobody spoke for). */
    internal fun since(now: Long): Long? {
        val at = endOfSpeechAt
        if (at == 0L) return null
        val elapsed = now - at
        if (elapsed !in 0..STALE_MS) {
            endOfSpeechAt = 0L
            return null
        }
        return elapsed
    }

    private fun log(message: String) {
        runCatching { Log.i(TAG, message) }
    }
}
