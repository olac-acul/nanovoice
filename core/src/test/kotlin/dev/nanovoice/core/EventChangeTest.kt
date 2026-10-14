package dev.nanovoice.core

import org.junit.Test
import org.junit.Assert.assertEquals
import java.time.LocalDateTime
import java.time.ZoneId

class EventChangeTest {
    @Test fun aMovePreservesElapsedDurationAcrossDaylightSaving() {
        val event = CalendarEvent(1, "Viaggio", time("2026-03-28T01:30"), time("2026-03-28T03:30"), false,
            null, false, true, ZoneId.of("Europe/Rome"))
        // Two elapsed hours from 01:30 on the day the clock jumps ahead end at 04:30.
        val result = EventChange(start = time("2026-03-29T01:30")).newTimes(event)
        assertEquals(time("2026-03-29T04:30"), result.second)
    }

    @Test fun anExplicitEndReplacesTheOldDuration() {
        val event = CalendarEvent(1, "Evento", time("2026-10-20T10:00"), time("2026-10-20T11:00"), false,
            null, false, true, ZoneId.of("Europe/Rome"))
        val result = EventChange(start = time("2026-10-21T09:00"), end = time("2026-10-21T12:00")).newTimes(event)
        assertEquals(time("2026-10-21T09:00") to time("2026-10-21T12:00"), result)
    }

    private fun time(s: String) = LocalDateTime.parse(s)
}
