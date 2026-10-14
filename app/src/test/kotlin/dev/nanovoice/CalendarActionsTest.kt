package dev.nanovoice

import dev.nanovoice.agent.ActionRequest
import dev.nanovoice.core.CalendarEditor
import dev.nanovoice.core.CalendarEvent
import dev.nanovoice.core.EventChange
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.*

class CalendarActionsTest {
    private val original = CalendarEvent(42, "Dentista", time("2026-10-20T15:30"), time("2026-10-20T16:30"), false,
        "Via Roma", false, true, ZoneId.of("Europe/Rome"), calendarName = "Personale")
    private var stored: CalendarEvent? = original
    private var permitted = true
    private var enabled = true
    private var locked = false
    private var open = true
    private var writes = 0
    private var deleted = false
    private var writtenChange: EventChange? = null
    private var beforeWrite: () -> Unit = {}
    private val questions = mutableListOf<String>()
    private val request get() = ActionRequest("m1") { open }
    private val editor = object : CalendarEditor {
        override fun permitted() = permitted
        override suspend fun find(id: Long) = stored?.takeIf { it.id == id }
        override suspend fun update(event: CalendarEvent, change: EventChange, stillAllowed: () -> Boolean): Boolean {
            beforeWrite()
            if (!stillAllowed() || !permitted || stored != event) return false
            writes++
            writtenChange = change
            return true
        }
        override suspend fun delete(event: CalendarEvent, stillAllowed: () -> Boolean): Boolean {
            beforeWrite()
            if (!stillAllowed() || !permitted || stored != event) return false
            writes++
            deleted = true
            return true
        }
    }

    private fun actions(answer: suspend (String) -> Consent = { Consent.Yes }) = CalendarActions(editor,
        consent = { questions += it; answer(it) }, enabled = { enabled }, locked = { locked })
    private fun args(vararg pairs: Pair<String, Any>) = JsonObject((listOf("event_id" to 42L) + pairs).associate { (k, v) ->
        k to when (v) { is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
    })

    @Test fun movingAnEventShowsTheActualBeforeAndAfterAndKeepsItsLength() = runBlocking {
        assertTrue(actions().handle("calendar_update", args("start" to "2026-10-21T10:00"), request).ok)
        val question = questions.single()
        for (text in listOf("Dentista", "Personale", "20 ott 2026", "15:30", "16:30", "21 ott 2026", "10:00", "11:00", "Via Roma")) {
            assertContains(question, text)
        }
        assertEquals("Sposto «Dentista» da martedì 20 ottobre alle 15:30 a mercoledì 21 ottobre alle 10:00?", ConsentRequest(0, question).headline)
        assertEquals(1, writes)
        assertEquals(time("2026-10-21T11:00"), writtenChange!!.newTimes(original).second)
    }

    @Test fun deletingRequiresApprovalForTheActualEvent() = runBlocking {
        assertTrue(actions().handle("calendar_delete", args(), request).ok)
        assertTrue(deleted)
        assertTrue(questions.single().startsWith("Cancello «Dentista» di martedì 20 ottobre alle 15:30?\n"), questions.single())
    }

    @Test fun rejectionOrSilenceNeverWrites() = runBlocking {
        for (answer in listOf(Consent.No, Consent.NoAnswer)) {
            assertFalse(actions { answer }.handle("calendar_delete", args(), request).ok)
            assertFalse(actions { answer }.handle("calendar_update", args("title" to "Controllo"), request).ok)
        }
        assertEquals(0, writes)
    }

    @Test fun approvalButtonsUseTheExistingConsentFlow() = runBlocking {
        val consent = ActionConsent()
        val handler = actions(consent::ask)
        val task = async { handler.handle("calendar_delete", args(), request) }
        val shown = consent.pending.filterNotNull().first()
        assertContains(shown.question, "Dentista")
        consent.answer(shown.id, true)
        assertTrue(task.await().ok)
        assertEquals(1, writes)
    }

    @Test fun anInterruptedRequestCannotUseAnApproval() = runBlocking {
        assertFalse(actions { open = false; Consent.Yes }.handle("calendar_delete", args(), request).ok)
        assertEquals(0, writes)
    }

    @Test fun aPhoneLockedDuringApprovalCannotWrite() = runBlocking {
        assertFalse(actions { locked = true; Consent.Yes }.handle("calendar_delete", args(), request).ok)
        assertEquals(0, writes)
    }

    @Test fun switchingEditsOffDuringApprovalCannotWrite() = runBlocking {
        assertFalse(actions { enabled = false; Consent.Yes }.handle("calendar_delete", args(), request).ok)
        assertEquals(0, writes)
    }

    @Test fun revokingPermissionDuringApprovalCannotWrite() = runBlocking {
        assertFalse(actions { permitted = false; Consent.Yes }.handle("calendar_delete", args(), request).ok)
        assertEquals(0, writes)
    }

    @Test fun anEventChangedBySyncNeedsANewApproval() = runBlocking {
        val result = actions { stored = original.copy(title = "Altro evento"); Consent.Yes }
            .handle("calendar_delete", args(), request)
        assertFalse(result.ok)
        assertContains(result.detail, "cambiato")
        assertEquals(0, writes)
    }

    @Test fun anEventRemovedWhileWaitingCannotBeModified() = runBlocking {
        assertFalse(actions { stored = null; Consent.Yes }.handle("calendar_update", args("title" to "Nuovo"), request).ok)
        assertEquals(0, writes)
    }

    @Test fun theWriterChecksTheRequestAgainAfterDispatchingToIo() = runBlocking {
        beforeWrite = { open = false }
        assertFalse(actions().handle("calendar_update", args("title" to "Nuovo"), request).ok)
        assertEquals(0, writes)
    }

    @Test fun recurringEventsAreRefusedBeforeAsking() = runBlocking {
        stored = original.copy(recurring = true)
        assertFalse(actions().handle("calendar_delete", args(), request).ok)
        assertEquals(emptyList(), questions)
        assertEquals(0, writes)
    }

    @Test fun readOnlyCalendarsAreRefusedBeforeAsking() = runBlocking {
        stored = original.copy(writable = false)
        assertFalse(actions().handle("calendar_update", args("title" to "Nuovo"), request).ok)
        assertEquals(emptyList(), questions)
    }

    @Test fun permissionsAndSettingsAreRequiredBeforeReadingAnEvent() = runBlocking {
        permitted = false
        assertFalse(actions().handle("calendar_delete", args(), request).ok)
        permitted = true
        enabled = false
        assertFalse(actions().handle("calendar_delete", args(), request).ok)
        enabled = true
        locked = true
        assertFalse(actions().handle("calendar_delete", args(), request).ok)
        assertEquals(emptyList(), questions)
    }

    @Test fun invalidIdsAndUnknownOrEmptyChangesAreRefused() = runBlocking {
        val handler = actions()
        for (bad in listOf(args("event_id" to -1), args("event_id" to "42"), args("event_id" to 42.5),
            args("event_id" to 9_007_199_254_740_992L), args("event_id" to 99), args(), args("title" to ""),
            args("title" to "x".repeat(201)), args("all_day" to true), args("title" to "a\nb"))) {
            assertFalse(handler.handle("calendar_update", bad, request).ok, bad.toString())
        }
        assertFalse(handler.handle("calendar_delete", args("title" to "altro"), request).ok)
        assertEquals(emptyList(), questions)
        assertEquals(0, writes)
    }

    @Test fun invalidDatesAndReversedIntervalsAreRefused() = runBlocking {
        for (bad in listOf(args("start" to "2026-02-30T10:00"), args("start" to "domani"),
            args("start" to "2026-10-21T24:00"), args("end" to "2026-10-20T15:00"),
            args("start" to "2026-10-21T10:00", "end" to "2026-10-21T10:00"))) {
            assertFalse(actions().handle("calendar_update", bad, request).ok, bad.toString())
        }
        assertEquals(emptyList(), questions)
    }

    @Test fun daylightSavingGapsAndAmbiguousHoursAreRefused() = runBlocking {
        for (start in listOf("2026-03-29T02:30", "2026-10-25T02:30")) {
            assertFalse(actions().handle("calendar_update", args("start" to start), request).ok)
        }
        assertEquals(emptyList(), questions)
    }

    @Test fun allDayEventsUseMidnightAndAnExclusiveEndDate() = runBlocking {
        stored = original.copy(start = time("2026-10-20T00:00"), end = time("2026-10-21T00:00"), allDay = true, zone = ZoneId.of("UTC"))
        assertFalse(actions().handle("calendar_update", args("start" to "2026-10-21T10:00"), request).ok)
        assertTrue(actions().handle("calendar_update", args("start" to "2026-10-22T00:00"), request).ok)
        assertContains(questions.single(), "la data di fine è esclusa")
        assertEquals(time("2026-10-23T00:00"), writtenChange!!.newTimes(stored!!).second)
    }

    @Test fun aLocationCanBeClearedAndANoopDoesNotAsk() = runBlocking {
        assertFalse(actions().handle("calendar_update", args("title" to "Dentista"), request).ok)
        assertTrue(actions().handle("calendar_update", args("location" to ""), request).ok)
        assertEquals("", writtenChange!!.location)
        assertContains(questions.single(), "Luogo: nessuno")
    }

    @Test fun editingRequiresBothCalendarSwitches() {
        assertFalse(PhoneActionHandler.capabilities(AppSettings(readCalendar = true)).contains("calendar_update"))
        assertFalse(PhoneActionHandler.capabilities(AppSettings(editCalendar = true)).contains("calendar_update"))
        assertTrue(PhoneActionHandler.capabilities(AppSettings(readCalendar = true, editCalendar = true)).containsAll(CalendarActions.NAMES))
    }

    @Test fun anApprovalIsNotReusedForAnotherChange() = runBlocking {
        var approvals = 0
        val handler = actions { if (approvals++ == 0) Consent.Yes else Consent.No }
        assertTrue(handler.handle("calendar_update", args("title" to "Controllo"), request).ok)
        assertFalse(handler.handle("calendar_delete", args(), request).ok)
        assertEquals(2, questions.size)
        assertEquals(1, writes)
        assertFalse(deleted)
    }

    private fun time(s: String) = LocalDateTime.parse(s)
}
