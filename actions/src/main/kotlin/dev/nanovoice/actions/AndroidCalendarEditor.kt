package dev.nanovoice.actions

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import dev.nanovoice.core.CalendarEditor
import dev.nanovoice.core.CalendarEvent
import dev.nanovoice.core.EventChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Changes events in the phone's calendar provider. The calendars synced by DAVx5 (Nextcloud) or another sync app are
 * marked dirty and carried to their server by it. All-day events are stored at UTC midnight, as the provider requires.
 */
class AndroidCalendarEditor(context: Context, private val clock: Clock = Clock.systemDefaultZone()) : CalendarEditor {
    private val context = context.applicationContext

    override fun permitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun find(id: Long): CalendarEvent? = withContext(Dispatchers.IO) {
        if (!permitted()) return@withContext null
        val columns = arrayOf(
            Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_LOCATION,
            Events.RRULE, Events.RDATE, Events.ORIGINAL_ID, Events.CALENDAR_ACCESS_LEVEL, Events.DELETED,
            Events.CALENDAR_ID, Events.EVENT_TIMEZONE, Events.DURATION, Events.EXDATE, Events.EXRULE,
            Events.CALENDAR_DISPLAY_NAME,
        )
        context.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), columns, null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getInt(9) == 1) return@withContext null
            val allDay = c.getInt(3) == 1
            val zone = if (allDay) ZoneOffset.UTC else clock.zone
            CalendarEvent(
                id = id,
                title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(senza titolo)",
                start = local(c.getLong(1), zone),
                end = if (c.isNull(2)) null else local(c.getLong(2), zone),
                allDay = allDay,
                location = c.getString(4)?.takeIf { it.isNotBlank() },
                recurring = !c.getString(5).isNullOrBlank() || !c.getString(6).isNullOrBlank() || !c.isNull(7),
                writable = c.getInt(8) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                zone = zone,
                revision = columns.withIndex().filter { it.index != 8 && it.index != 15 }.associate { (index, name) ->
                    name to if (c.isNull(index)) null else c.getString(index)
                },
                calendarName = c.getString(15).orEmpty(),
            )
        }
    }

    override suspend fun update(event: CalendarEvent, change: EventChange, stillAllowed: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!current(event)) return@withContext false
        val zone = if (event.allDay) ZoneOffset.UTC else clock.zone
        val values = ContentValues()
        change.title?.let { values.put(Events.TITLE, it) }
        change.location?.let { values.put(Events.EVENT_LOCATION, it) }
        if (change.start != null || change.end != null) {
            val (start, end) = change.newTimes(event)
            values.put(Events.DTSTART, millis(start, zone, event.allDay))
            end?.let { values.put(Events.DTEND, millis(it, zone, event.allDay)) }
        }
        if (values.size() == 0) return@withContext false
        val (selection, args) = unchanged(event)
        if (!stillAllowed() || !permitted()) return@withContext false
        context.contentResolver.update(Events.CONTENT_URI, values, selection, args) == 1
    }

    override suspend fun delete(event: CalendarEvent, stillAllowed: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!current(event)) return@withContext false
        val (selection, args) = unchanged(event)
        if (!stillAllowed() || !permitted()) return@withContext false
        context.contentResolver.delete(Events.CONTENT_URI, selection, args) == 1
    }

    private suspend fun current(event: CalendarEvent): Boolean =
        event.writable && !event.recurring && event.revision.isNotEmpty() && find(event.id) == event

    /** Compare and write in the same provider operation, so a sync after the last read cannot overwrite new data. */
    private fun unchanged(event: CalendarEvent): Pair<String, Array<String>> {
        val args = mutableListOf(event.id.toString())
        val parts = mutableListOf("${Events._ID} = ?")
        for ((column, value) in event.revision) {
            if (value == null) parts += "$column IS NULL" else {
                parts += "$column = ?"
                args += value
            }
        }
        return parts.joinToString(" AND ") to args.toTypedArray()
    }

    private fun local(epochMillis: Long, zone: ZoneId) = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)

    private fun millis(time: LocalDateTime, zone: ZoneId, allDay: Boolean): Long =
        (if (allDay) time.toLocalDate().atStartOfDay() else time).atZone(zone).toInstant().toEpochMilli()

}
