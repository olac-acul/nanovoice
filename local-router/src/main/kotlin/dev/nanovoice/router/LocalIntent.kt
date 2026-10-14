package dev.nanovoice.router

import kotlin.time.Duration

sealed interface LocalIntent {
    data object TellTime : LocalIntent
    data object TellDate : LocalIntent
    data class OpenApp(val name: String) : LocalIntent

    /** A song, an album or an artist to search and play in the music app. */
    data class PlayMusic(val query: String) : LocalIntent

    /** A note to write in Nextcloud Notes. */
    data class Note(val text: String) : LocalIntent
    data class Call(val target: String) : LocalIntent

    /** [time] == null means the user did not say when: the assistant must ask. */
    data class SetAlarm(val time: ClockTime?, val tomorrow: Boolean = false) : LocalIntent

    /** [duration] == null means the user did not say how long: the assistant must ask. */
    data class SetTimer(val duration: Duration?) : LocalIntent
}

data class MatchResult(val intent: LocalIntent, val confidence: Float)

interface LocalMatcher {
    /** Returns null when the text is not recognized. */
    fun match(text: String): MatchResult?
}
