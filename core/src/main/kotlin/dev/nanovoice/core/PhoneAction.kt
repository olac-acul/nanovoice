package dev.nanovoice.core

import kotlin.time.Duration

/** An action the phone can perform, regardless of who requested it (local router or NanoClaw). */
sealed interface PhoneAction {
    data object TellTime : PhoneAction
    data object TellDate : PhoneAction
    data class OpenApp(val name: String) : PhoneAction

    /** [target] is a contact name or a phone number made of digits. */
    data class Call(val target: String) : PhoneAction

    /** [tomorrow]: the user said "domani"; the clock app rings at the next occurrence, which may still be today. */
    data class SetAlarm(val hour: Int, val minute: Int, val tomorrow: Boolean = false) : PhoneAction
    data class SetTimer(val duration: Duration) : PhoneAction

    /**
     * Stops a timer. One that is ringing is silenced; one still counting down cannot be stopped by another app
     * (Android has no way to), so the clock app's timers screen opens for the person to stop it with a tap.
     */
    data object StopTimer : PhoneAction

    /**
     * Stops an alarm. Without a time: the one ringing now. With a time: the clock app skips the next ring of that alarm
     * (it can do so only within the next 24 hours; a repeating alarm stays on for the following days).
     */
    data class StopAlarm(val hour: Int? = null, val minute: Int? = null) : PhoneAction

    /** Snoozes the alarm ringing now, for [minutes] or for the clock app's own snooze time. */
    data class SnoozeAlarm(val minutes: Int? = null) : PhoneAction

    enum class MediaCommand { Play, Pause, Toggle, Next, Previous }
    data class Media(val command: MediaCommand) : PhoneAction

    /** Searches [query] in the music app (Tempo when installed) and starts playing what it finds. */
    data class PlayMusic(val query: String) : PhoneAction

    /** Media volume: an absolute [percent] (0..100), or a step [up] / down, or [mute]. */
    data class Volume(val percent: Int? = null, val up: Boolean? = null, val mute: Boolean = false) : PhoneAction
    data class Flashlight(val on: Boolean) : PhoneAction
    data class Navigate(val destination: String) : PhoneAction

    /** https only: the address comes from the agent. */
    data class OpenUrl(val url: String) : PhoneAction

    /** Opens the messaging app with the text filled in; the user presses send. [to] is a contact name or a number. */
    data class SmsDraft(val to: String?, val text: String) : PhoneAction

    /** Opens Signal's share screen with [text] filled in; the user picks the chat ([to] is only mentioned) and sends. */
    data class SignalDraft(val to: String?, val text: String) : PhoneAction

    /** A new note in Nextcloud Notes, which saves and syncs it; [title] defaults to the first words. */
    data class Note(val text: String, val title: String? = null) : PhoneAction

    /** Opens the calendar's new-event screen filled in; the user saves. Times are local, "yyyy-MM-ddTHH:mm". */
    data class CalendarDraft(val title: String, val start: String, val end: String? = null, val location: String? = null) : PhoneAction
    data object Battery : PhoneAction

    /** Reads: they hand private data to the agent, so each has its own switch, off by default. */
    data class CalendarRead(val days: Int) : PhoneAction
    data object Location : PhoneAction
    data class Notifications(val limit: Int) : PhoneAction
}

data class ActionResult(val ok: Boolean, val message: String)

interface ActionExecutor {
    suspend fun execute(action: PhoneAction): ActionResult
}
