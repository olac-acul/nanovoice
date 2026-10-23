package dev.nanovoice

import dev.nanovoice.agent.ActionOutcome
import dev.nanovoice.agent.ActionRequest
import dev.nanovoice.core.ActionExecutor
import dev.nanovoice.core.ActionResult
import dev.nanovoice.core.PhoneAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PhoneActionHandlerTest {
    private companion object { val REQ = ActionRequest("m1") }

    private val executed = mutableListOf<PhoneAction>()
    private val asked = mutableListOf<String>()
    private val recorder = object : ActionExecutor {
        override suspend fun execute(action: PhoneAction): ActionResult {
            executed += action
            return ActionResult(true, "ok")
        }
    }
    private val handler = PhoneActionHandler(recorder, consent = { asked += it; Consent.Yes })

    private fun handle(name: String, vararg args: Pair<String, Long>) =
        runBlocking { handler.handle(name, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }), REQ) }

    @Test fun setTimer() {
        assertTrue(handle("set_timer", "seconds" to 600).ok)
        assertEquals(listOf<PhoneAction>(PhoneAction.SetTimer(600.seconds)), executed)
    }

    @Test fun setTimerWithoutSeconds() {
        assertFalse(handle("set_timer").ok)
        assertEquals(emptyList(), executed)
    }

    @Test fun stopsTimersAndAlarms() {
        assertTrue(handle("stop_timer").ok)
        assertTrue(handle("stop_alarm").ok)
        assertTrue(handle("stop_alarm", "hour" to 7, "minute" to 30).ok)
        assertTrue(handle("stop_alarm", "hour" to 6).ok)
        assertTrue(handle("snooze_alarm").ok)
        assertTrue(handle("snooze_alarm", "minutes" to 10).ok)
        assertEquals(
            listOf(
                PhoneAction.StopTimer, PhoneAction.StopAlarm(), PhoneAction.StopAlarm(7, 30), PhoneAction.StopAlarm(6, null),
                PhoneAction.SnoozeAlarm(), PhoneAction.SnoozeAlarm(10),
            ),
            executed,
        )
    }

    @Test fun refusesOddClockArguments() {
        assertFalse(handle("stop_alarm", "hour" to 24).ok)
        assertFalse(handle("stop_alarm", "minute" to 30).ok)
        assertFalse(handle("snooze_alarm", "minutes" to 0).ok)
        assertFalse(handle("snooze_alarm", "minutes" to 120).ok)
        assertEquals(emptyList(), executed)
    }

    @Test fun clockControlsRunWithThePhoneLockedAndAreAlwaysDeclared() {
        val lockedHandler = PhoneActionHandler(recorder, locked = { true })
        for (name in listOf("stop_timer", "stop_alarm", "snooze_alarm")) {
            assertTrue(runBlocking { lockedHandler.handle(name, JsonObject(emptyMap()), REQ) }.ok, name)
            assertTrue(name in PhoneActionHandler.BASIC, name)
        }
    }

    @Test fun tellTime() {
        assertTrue(handle("tell_time").ok)
        assertEquals(listOf<PhoneAction>(PhoneAction.TellTime), executed)
    }

    private fun handleText(name: String, key: String, value: String) =
        runBlocking { handler.handle(name, JsonObject(mapOf(key to JsonPrimitive(value))), REQ) }

    @Test fun openApp() {
        assertTrue(handleText("open_app", "name", " Signal ").ok)
        assertEquals(listOf<PhoneAction>(PhoneAction.OpenApp("Signal")), executed)
    }

    @Test fun callOnlyPreparesTheDialer() {
        assertTrue(handleText("call", "target", "Mario").ok)
        assertEquals(listOf<PhoneAction>(PhoneAction.Call("Mario")), executed)
    }

    @Test fun openAppAndCallNeedTheirArgument() {
        assertFalse(handleText("open_app", "name", "  ").ok)
        assertFalse(handle("call").ok)
        assertEquals(emptyList(), executed)
    }

    @Test fun alarmKeepsTomorrow() {
        val args = JsonObject(mapOf("hour" to JsonPrimitive(6), "minute" to JsonPrimitive(45), "tomorrow" to JsonPrimitive(true)))
        assertTrue(runBlocking { handler.handle("set_alarm", args, REQ) }.ok)
        assertEquals(listOf<PhoneAction>(PhoneAction.SetAlarm(6, 45, true)), executed)
    }

    @Test fun unknownAction() {
        assertFalse(handle("launch_missiles").ok)
        assertEquals(emptyList(), executed)
    }

    @Test fun capabilitiesFollowTheSwitches() {
        // the reads are off by default
        assertEquals(
            PhoneActionHandler.ALL - PhoneActionHandler.CALENDAR - PhoneActionHandler.CALENDAR_EDITS - PhoneActionHandler.LOCATION - PhoneActionHandler.NOTIFICATIONS,
            PhoneActionHandler.capabilities(AppSettings()),
        )
        assertEquals(PhoneActionHandler.ALL, PhoneActionHandler.capabilities(AppSettings(readCalendar = true, editCalendar = true, readLocation = true, readNotifications = true)))
        val off = AppSettings(phoneControls = false, phoneLinks = false, phoneDrafts = false, phoneStatus = false)
        assertEquals(setOf("set_timer", "tell_time", "tell_date", "set_alarm", "open_app", "call", "stop_timer", "stop_alarm", "snooze_alarm", "confirm", "choose") + PhotoActions.NAMES, PhoneActionHandler.capabilities(off))
        assertEquals(PhoneActionHandler.ALL - "battery" - PhoneActionHandler.CALENDAR - PhoneActionHandler.CALENDAR_EDITS - PhoneActionHandler.LOCATION - PhoneActionHandler.NOTIFICATIONS, PhoneActionHandler.capabilities(AppSettings(phoneStatus = false)))
    }

    private fun json(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) ->
        k to when (v) {
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is List<*> -> JsonArray(v.map { JsonPrimitive(it.toString()) })
            else -> JsonPrimitive(v.toString())
        }
    })

    @Test fun newActionsAreParsed() {
        runBlocking {
            assertTrue(handler.handle("media", json("command" to "next"), REQ).ok)
            assertTrue(handler.handle("volume", json("percent" to 40), REQ).ok)
            assertTrue(handler.handle("volume", json("change" to "down"), REQ).ok)
            assertTrue(handler.handle("flashlight", json("on" to true), REQ).ok)
            assertTrue(handler.handle("navigate", json("destination" to "Piazza Navona, Roma"), REQ).ok)
            assertTrue(handler.handle("open_url", json("url" to "https://example.org"), REQ).ok)
            assertTrue(handler.handle("sms_draft", json("to" to "Mario", "text" to "Arrivo tra dieci minuti"), REQ).ok)
            assertTrue(handler.handle("calendar_draft", json("title" to "Dentista", "start" to "2026-10-20T15:30"), REQ).ok)
            assertTrue(handler.handle("battery", json(), REQ).ok)
            assertTrue(handler.handle("calendar_read", json("days" to 30), REQ).ok)
            assertTrue(handler.handle("location", json(), REQ).ok)
            assertTrue(handler.handle("notifications", json(), REQ).ok)
        }
        assertEquals(
            listOf(
                PhoneAction.Media(PhoneAction.MediaCommand.Next), PhoneAction.Volume(percent = 40), PhoneAction.Volume(up = false),
                PhoneAction.Flashlight(true), PhoneAction.Navigate("Piazza Navona, Roma"), PhoneAction.OpenUrl("https://example.org"),
                PhoneAction.SmsDraft("Mario", "Arrivo tra dieci minuti"), PhoneAction.CalendarDraft("Dentista", "2026-10-20T15:30"), PhoneAction.Battery,
                PhoneAction.CalendarRead(14), PhoneAction.Location, PhoneAction.Notifications(5),
            ),
            executed,
        )
    }

    @Test fun badArgumentsAreRefused() {
        runBlocking {
            assertFalse(handler.handle("media", json("command" to "shuffle"), REQ).ok)
            assertFalse(handler.handle("volume", json("percent" to 150), REQ).ok)
            assertFalse(handler.handle("sms_draft", json("to" to "Mario"), REQ).ok)
            assertFalse(handler.handle("calendar_draft", json("title" to "x"), REQ).ok)
        }
        assertEquals(emptyList(), executed)
    }

    @Test fun aSwitchedOffActionIsRefusedEvenIfTheServerAsks() {
        val limited = PhoneActionHandler(recorder, enabled = { PhoneActionHandler.BASIC })
        assertFalse(runBlocking { limited.handle("flashlight", json("on" to true), REQ) }.ok)
        assertEquals(emptyList(), executed)
    }

    @Test fun photoToolsGoToThePhotoActionsAndAreAlwaysDeclared() {
        val asked = mutableListOf<String>()
        val withPhotos = PhoneActionHandler(object : ActionExecutor {
            override suspend fun execute(action: PhoneAction): ActionResult { executed += action; return ActionResult(true, "ok") }
        }, photos = { name, _, _ -> asked += name; ActionOutcome(true, "fatto") })
        assertTrue(runBlocking { withPhotos.handle("identify_plant", json("organ" to "leaf"), REQ) }.ok)
        assertTrue(runBlocking { withPhotos.handle("food_product", json("barcode" to "8001234567890"), REQ) }.ok)
        assertEquals(listOf("identify_plant", "food_product"), asked)
        assertEquals(emptyList(), executed)
        assertTrue(PhoneActionHandler.capabilities(AppSettings(phoneControls = false)).containsAll(PhotoActions.NAMES))
        assertTrue(PhoneActionHandler.capabilities(AppSettings(photoServices = false)).none { it in PhotoActions.NAMES })
    }

    @Test fun aLockedPhoneRunsOnlyWhatItAnswersByItself() {
        val locked = PhoneActionHandler(object : ActionExecutor {
            override suspend fun execute(action: PhoneAction): ActionResult { executed += action; return ActionResult(true, "ok") }
        }, photos = { _, _, _ -> ActionOutcome(true, "foto") }, locked = { true })
        runBlocking {
            assertTrue(locked.handle("set_timer", json("seconds" to 60), REQ).ok)
            assertFalse(locked.handle("open_url", json("url" to "https://example.org"), REQ).ok)
            assertFalse(locked.handle("location", JsonObject(emptyMap()), REQ).ok)
            assertFalse(locked.handle("image_search", json("engine" to "tineye"), REQ).ok)
        }
        assertEquals(1, executed.size)
    }

    @Test fun linksAndReadsRunWithoutAsking() {
        runBlocking {
            assertTrue(handler.handle("open_url", json("url" to "https://it.wikipedia.org"), REQ).ok)
            assertTrue(handler.handle("location", json(), REQ).ok)
        }
        assertEquals(emptyList(), asked)
        assertEquals(listOf(PhoneAction.OpenUrl("https://it.wikipedia.org"), PhoneAction.Location), executed)
    }

    @Test fun confirmAsksTheAgentsQuestion() {
        val q = "Sposto «Dentista» a mercoledì alle 10?"
        assertTrue(runBlocking { handler.handle("confirm", json("question" to q), REQ) }.ok)
        assertEquals(listOf(q), asked)
        assertEquals(emptyList(), executed)
    }

    @Test fun aNoOrNoAnswerIsNotAnApproval() {
        val no = PhoneActionHandler(recorder, consent = { Consent.No })
        val silent = PhoneActionHandler(recorder) // no way to ask: never approved
        runBlocking {
            assertFalse(no.handle("confirm", json("question" to "Cancello la cena?"), REQ).ok)
            assertFalse(silent.handle("confirm", json("question" to "Cancello la cena?"), REQ).ok)
            assertFalse(handler.handle("confirm", json(), REQ).ok)
            assertFalse(handler.handle("confirm", json("question" to "x".repeat(201)), REQ).ok)
        }
    }

    @Test fun anApprovalForARequestInterruptedMeanwhileDoesNotCount() {
        var open = true
        val interrupted = PhoneActionHandler(recorder, consent = { open = false; Consent.Yes })
        assertFalse(runBlocking { interrupted.handle("confirm", json("question" to "Sposto?"), ActionRequest("m1") { open }) }.ok)
    }

    @Test fun chooseGivesBackTheOptionChosenWordForWord() {
        var asked: Pair<String, List<String>>? = null
        val choosing = PhoneActionHandler(recorder, choose = { q, o -> asked = q to o; 1 })
        val outcome = runBlocking { choosing.handle("choose", json("question" to "Quale?", "options" to listOf("Otranto", "Gallipoli")), REQ) }
        assertEquals(ActionOutcome(true, "Gallipoli"), outcome)
        assertEquals("Quale?" to listOf("Otranto", "Gallipoli"), asked)
    }

    @Test fun noChoiceOrBadOptionsAreNotAChoice() {
        val refused = PhoneActionHandler(recorder, choose = { _, _ -> ActionConsent.REFUSED })
        val silent = PhoneActionHandler(recorder) // no way to ask
        val two = listOf("A", "B")
        runBlocking {
            assertFalse(refused.handle("choose", json("question" to "Quale?", "options" to two), REQ).ok)
            assertFalse(silent.handle("choose", json("question" to "Quale?", "options" to two), REQ).ok)
            assertFalse(handler.handle("choose", json("question" to "Quale?", "options" to listOf("A")), REQ).ok)
            assertFalse(handler.handle("choose", json("question" to "Quale?", "options" to listOf("A", "A")), REQ).ok)
            assertFalse(handler.handle("choose", json("question" to "Quale?", "options" to List(6) { "o$it" }), REQ).ok)
            assertFalse(handler.handle("choose", json("options" to two), REQ).ok)
        }
        var open = true
        val interrupted = PhoneActionHandler(recorder, choose = { _, _ -> open = false; 0 })
        assertFalse(runBlocking { interrupted.handle("choose", json("question" to "Quale?", "options" to two), ActionRequest("m1") { open }) }.ok)
    }

    @Test fun linksMustBeHttpsWithoutAUserName() {
        runBlocking {
            assertFalse(handler.handle("open_url", json("url" to "http://example.org"), REQ).ok)
            assertFalse(handler.handle("open_url", json("url" to "https://bank.example.com@other.test/x"), REQ).ok)
            assertFalse(handler.handle("open_url", json("url" to "https://exa mple.org"), REQ).ok)
            assertFalse(handler.handle("open_url", json("url" to "javascript:alert(1)"), REQ).ok)
        }
        assertEquals(emptyList(), executed)
    }
}
