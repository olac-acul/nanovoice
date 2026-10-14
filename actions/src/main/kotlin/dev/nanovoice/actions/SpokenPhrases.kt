package dev.nanovoice.actions

import java.time.LocalDate
import java.time.LocalTime
import kotlin.time.Duration

/** Everything the assistant says in reply to actions, per language. Written to be read aloud by a TTS. */
interface SpokenPhrases {
    fun time(time: LocalTime): String
    fun date(date: LocalDate): String
    fun timerSet(duration: Duration): String

    /** [ringsToday]: the user said "domani" but the time is still ahead today, so the alarm will ring today. */
    fun alarmSet(hour: Int, minute: Int, ringsToday: Boolean): String
    val askAlarmTime: String
    fun openingApp(label: String): String
    fun appNotFound(query: String): String
    fun calling(name: String): String
    val callingNumber: String
    fun contactNotFound(query: String): String
    fun contactAmbiguous(names: List<String>): String
    val contactsPermissionNeeded: String
    val dialerNotFound: String
    val alarmNoApp: String
    val alarmDenied: String
    val timerTooLong: String
    val timerNoApp: String
    val timerDenied: String
    val askTimerDuration: String
    fun media(command: dev.nanovoice.core.PhoneAction.MediaCommand): String
    fun volumeSet(percent: Int): String
    val volumeMuted: String
    fun flashlight(on: Boolean): String
    val noFlashlight: String
    fun navigating(destination: String): String
    val noMapsApp: String
    val openingLink: String
    val badLink: String
    val noBrowser: String
    fun smsDraft(name: String?): String
    val noMessagingApp: String
    fun playingMusic(query: String): String
    fun playingSong(title: String, artist: String): String
    fun playingAlbum(album: String): String
    fun playingArtist(artist: String): String
    fun musicNotFound(query: String): String
    val musicNotConfigured: String
    val musicServerError: String
    val noMusicApp: String
    /** Tempo is there but not signed as expected (installed from another source): its songs would get the token. */
    val musicAppUntrusted: String
    /** Tempo took the songs but no music is heard yet. */
    fun musicQueued(label: String): String
    fun signalDraft(name: String?): String
    val noSignal: String
    val noteWritten: String
    val noNotesApp: String
    fun calendarDraft(title: String): String
    val badEventTime: String
    val noCalendarApp: String
    fun battery(percent: Int, charging: Boolean): String
    val calendarPermissionNeeded: String
    fun noEvents(days: Int): String
    val locationPermissionNeeded: String
    val locationUnavailable: String
    val notificationAccessNeeded: String
    val noNotifications: String
    val notUnderstood: String

    /** Shown when the server could not start a new conversation. */
    val newConversationFailed: String

    /** On the locked phone, for anything beyond the time, the date, timers and alarms. */
    val unlockNeeded: String

    /** Shown in place of a reply the user interrupted. */
    val requestInterrupted: String

    /** Every phrase that never changes, to be synthesized in advance. */
    val fixedPhrases: List<String>
}
