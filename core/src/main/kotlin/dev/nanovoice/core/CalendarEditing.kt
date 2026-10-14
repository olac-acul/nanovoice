package dev.nanovoice.core

import java.time.LocalDateTime
import java.time.Duration
import java.time.ZoneId

/**
 * An event of the phone's calendar as it is stored, read again before any change: the person approves what is really
 * there, not what the agent says. [start] and [end] are local times; for an all-day event only their dates count.
 */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime?,
    val allDay: Boolean,
    val location: String?,
    /** A repeating event: changing one occurrence is not supported. */
    val recurring: Boolean,
    /** Its calendar lets this phone change it (owner or contributor access). */
    val writable: Boolean,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Opaque provider values used for a conditional write; never sent to the agent. */
    val revision: Map<String, String?> = emptyMap(),
    val calendarName: String = "",
)

/** What to change in an event; null fields stay as they are. */
data class EventChange(
    val title: String? = null,
    val start: LocalDateTime? = null,
    val end: LocalDateTime? = null,
    val location: String? = null,
) {
    val isEmpty get() = title == null && start == null && end == null && location == null

    /** Moving the start alone preserves elapsed duration, including across a daylight saving transition. */
    fun newTimes(event: CalendarEvent): Pair<LocalDateTime, LocalDateTime?> {
        val from = start ?: event.start
        val to = end ?: event.end?.let {
            from.atZone(event.zone).plus(Duration.between(event.start.atZone(event.zone), it.atZone(event.zone))).toLocalDateTime()
        }
        return from to to
    }
}

/** Changes the phone's calendar; the calendar app's sync (DAVx5 for Nextcloud) carries them to the server. */
interface CalendarEditor {
    /** The permission to change the calendar was granted. */
    fun permitted(): Boolean

    suspend fun find(id: Long): CalendarEvent?

    /** Applies [change] only to the unchanged snapshot, checking [stillAllowed] on the writing thread. True when written. */
    suspend fun update(event: CalendarEvent, change: EventChange, stillAllowed: () -> Boolean): Boolean

    suspend fun delete(event: CalendarEvent, stillAllowed: () -> Boolean): Boolean
}
