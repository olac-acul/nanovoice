package dev.nanovoice

import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import dev.nanovoice.actions.AndroidCalendarEditor
import dev.nanovoice.agent.ActionRequest
import dev.nanovoice.core.EventChange
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.*
import java.util.UUID

/** Runs only on an explicitly selected device. Every write is to a unique fixture; no existing event is edited. */
@android.annotation.TargetApi(29)
class CalendarDeviceRunner : Instrumentation() {
    private var live = false
    private val marker = "NanoVoice-test-${UUID.randomUUID()}"
    private val eventIds = mutableSetOf<Long>()
    private var localCalendar: Uri? = null
    private lateinit var editor: AndroidCalendarEditor
    private var syncAccount: Account? = null
    private var liveEventId: Long? = null
    private val resolver get() = targetContext.contentResolver

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        live = arguments?.getString("live") == "true"
        start()
    }

    override fun onStart() {
        var failure: Throwable? = null
        try {
            editor = AndroidCalendarEditor(targetContext)
            check(editor.permitted()) { "Grant READ_CALENDAR and WRITE_CALENDAR to dev.nanovoice before running." }
            runBlocking {
                val calendarId = createLocalCalendar()
                providerTests(calendarId)
                approvalTests(calendarId)
                if (live) liveTest()
            }
        } catch (e: Throwable) {
            failure = e
        } finally {
            try {
                // IDs are exclusively those returned when this runner inserted its own fixtures.
                eventIds.forEach { resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, it), null, null) }
                syncAccount?.let {
                    requestSync(it)
                    liveEventId?.let { id ->
                        runBlocking { withTimeout(180_000) { while (!deletionSynced(id)) delay(1_000) } }
                    }
                    status("DAVx5 acknowledged test event cleanup: ok")
                }
                localCalendar?.let { resolver.delete(it, null, null) }
                status("fixture cleanup: ok")
            } catch (e: Throwable) { if (failure == null) failure = e }
        }
        val result = Bundle()
        if (failure == null) {
            result.putString("stream", "\nCalendar device verification: PASS\n")
            finish(Activity.RESULT_OK, result)
        } else {
            // Never print calendar contents, app settings, credentials or server replies.
            result.putString("stream", "\nCalendar device verification: FAIL (${failure.javaClass.simpleName}: ${failure.message?.take(200)})\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun status(message: String) = sendStatus(0, Bundle().apply { putString("stream", "$message\n") })

    private fun createLocalCalendar(): Long {
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, marker)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, marker)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, marker)
            put(Calendars.CALENDAR_DISPLAY_NAME, "NanoVoice verifica temporanea")
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, marker)
            put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }
        val added = checkNotNull(resolver.insert(uri, values))
        val id = ContentUris.parseId(added)
        localCalendar = ContentUris.withAppendedId(uri, id)
        return id
    }

    private fun createEvent(calendar: Long, allDay: Boolean = false, recurring: Boolean = false, title: String = marker): Long {
        val zone = if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()
        val start = if (allDay) LocalDate.now().plusDays(1).atStartOfDay(zone) else ZonedDateTime.now(zone).plusHours(2).withSecond(0).withNano(0)
        val end = if (allDay) start.plusDays(1) else start.plusHours(1)
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar)
            put(Events.TITLE, title)
            put(Events.DTSTART, start.toInstant().toEpochMilli())
            put(Events.EVENT_TIMEZONE, zone.id)
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            put(Events.EVENT_LOCATION, "Luogo di prova")
            if (recurring) {
                put(Events.RRULE, "FREQ=DAILY;COUNT=2")
                put(Events.DURATION, "PT3600S")
            } else put(Events.DTEND, end.toInstant().toEpochMilli())
        }
        val id = ContentUris.parseId(checkNotNull(resolver.insert(Events.CONTENT_URI, values)))
        eventIds += id
        return id
    }

    private suspend fun providerTests(calendar: Long) {
        val id = createEvent(calendar)
        val original = checkNotNull(editor.find(id))
        check(original.title == marker && original.writable && !original.recurring) { "Provider lookup failed" }
        check(!editor.update(original, EventChange(title = "forbidden")) { false }) { "A refused write ran" }
        check(editor.find(id) == original) { "Refused write changed the event" }
        val change = EventChange(start = original.start.plusHours(2))
        check(editor.update(original, change) { true }) { "Provider update failed" }
        val moved = checkNotNull(editor.find(id))
        check(moved.start == original.start.plusHours(2) && moved.end == original.end?.plusHours(2)) { "Duration was not preserved" }
        check(!editor.update(original, EventChange(title = "stale")) { true }) { "A stale snapshot was written" }

        // Deterministic sync race: mutate the fixture after the editor's last read, immediately before its SQL write.
        check(!editor.update(moved, EventChange(title = "stale")) {
            resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply { put(Events.EVENT_LOCATION, "Concurrent test edit") }, null, null)
            true
        }) { "Conditional update overwrote a concurrent edit" }
        val concurrent = checkNotNull(editor.find(id))
        check(concurrent.title == marker && concurrent.location == "Concurrent test edit") { "Concurrent update was lost" }
        check(!editor.delete(concurrent) {
            resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply { put(Events.EVENT_LOCATION, "Second concurrent edit") }, null, null)
            true
        }) { "Conditional delete removed a changed event" }
        check(!editor.delete(checkNotNull(editor.find(id))) { false }) { "Refused deletion ran" }
        check(editor.delete(checkNotNull(editor.find(id))) { true }) { "Provider delete failed" }
        check(editor.find(id) == null) { "Deleted event remained visible" }
        status("provider: lookup, move, duration, stale snapshot, atomic update/delete race, guard, delete: ok")

        val dayId = createEvent(calendar, allDay = true)
        val day = checkNotNull(editor.find(dayId))
        check(day.allDay && day.zone == ZoneOffset.UTC) { "All-day timezone failed" }
        check(editor.update(day, EventChange(start = day.start.plusDays(1))) { true }) { "All-day move failed" }
        check(editor.find(dayId)?.end == day.end?.plusDays(1)) { "All-day exclusive end failed" }
        val recurring = checkNotNull(editor.find(createEvent(calendar, recurring = true)))
        check(recurring.recurring && !editor.delete(recurring) { true }) { "Repeating series was not protected" }
        status("provider: all-day UTC and recurring event protection: ok")
    }

    private suspend fun CoroutineScope.approvalTests(calendar: Long) {
        val id = createEvent(calendar)
        val original = checkNotNull(editor.find(id))
        var open = true
        val consent = ActionConsent(timeoutMs = 500)
        val actions = CalendarActions(editor, consent::ask, { true }, { false })
        val args = JsonObject(mapOf("event_id" to JsonPrimitive(id), "title" to JsonPrimitive("$marker-approved")))
        val request = ActionRequest("device-test") { open }
        val refused = async { actions.handle("calendar_update", args, request) }
        var pending = consent.pending.filterNotNull().first()
        check(pending.question.contains(marker) && pending.question.contains(original.calendarName)) { "Approval did not show the fixture" }
        consent.answer(pending.id, false)
        check(!refused.await().ok && editor.find(id) == original) { "Refused approval changed the provider" }
        check(!actions.handle("calendar_update", args, request).ok && editor.find(id) == original) { "Expired approval changed the provider" }
        val interrupted = async { actions.handle("calendar_update", args, request) }
        pending = consent.pending.filterNotNull().first()
        open = false
        consent.answer(pending.id, true)
        check(!interrupted.await().ok && editor.find(id) == original) { "Interrupted approval changed the provider" }
        open = true
        val accepted = async { actions.handle("calendar_update", args, request) }
        pending = consent.pending.filterNotNull().first()
        consent.answer(pending.id, true)
        check(accepted.await().ok && editor.find(id)?.title == "$marker-approved") { "Approved change did not reach the provider" }
        status("approval + provider: reject, expire, interrupt and approve: ok")
    }

    private suspend fun liveTest() {
        check(!targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock the phone for the live UI test" }
        val container = (targetContext.applicationContext as NanoVoiceApp).container
        val before = container.settingsStore.settings.value
        val calendar = resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE),
            "${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ?", arrayOf("bitfire.at.davdroid", Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use {
            check(it.moveToFirst()) { "No writable DAVx5 calendar" }
            syncAccount = Account(it.getString(1), it.getString(2))
            it.getLong(0)
        } ?: error("No DAVx5 calendar")
        val fixtureTitle = "$marker-live"
        val id = createEvent(calendar, title = fixtureTitle)
        liveEventId = id
        try {
            container.settingsStore.save(before.copy(readCalendar = true, editCalendar = true))
            withTimeout(30_000) { container.connection.status.first { it == AgentStatus.Connected } }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var vm: ChatViewModel
            runOnMainSync { vm = ViewModelProvider(activity, chatViewModelFactory(container))[ChatViewModel::class.java] }
            val old = checkNotNull(editor.find(id))
            val newStart = old.start.plusHours(1)
            val formatted = newStart.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            status("live: sending a request for the unique test event")
            runOnMainSync { vm.send("Sposta solo l'evento di prova «$fixtureTitle», event_id=$id, all'inizio locale $formatted, mantenendo la durata. Usa phone_calendar_update; non modificare altri eventi.") }
            val pending = withTimeout(180_000) { container.consent.pending.filterNotNull().first() }
            check(pending.headline.startsWith("Sposto «$fixtureTitle»") && pending.question.contains(fixtureTitle) &&
                pending.question.contains(newStart.toLocalTime().toString())) { "Live approval was not bound to the expected fixture" }
            withTimeout(10_000) {
                while (!clickApproval()) delay(200)
            }
            withTimeout(30_000) { while (editor.find(id)?.start != newStart) delay(200) }
            check(editor.find(id)?.end == old.end?.plusHours(1)) { "Live move lost duration" }
            withTimeout(180_000) { vm.busy.first { !it } }
            status("live: server tool, WebSocket, visible approval button and provider write: ok")
            requestSync(checkNotNull(syncAccount))
            withTimeout(180_000) {
                while (!synced(id)) delay(1_000)
            }
            status("live: DAVx5 acknowledged the modified fixture (dirty=0 and remote sync id present): ok")
        } finally {
            container.consent.refuseAll()
            container.settingsStore.save(before)
        }
    }

    private fun requestSync(account: Account) {
        uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SYNC_SETTINGS)
        try {
            ContentResolver.requestSync(account, CalendarContract.AUTHORITY, Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            })
        } finally { uiAutomation.dropShellPermissionIdentity() }
    }

    private fun synced(id: Long): Boolean = resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id),
        arrayOf(Events.DIRTY, Events._SYNC_ID), null, null, null)?.use {
        it.moveToFirst() && it.getInt(0) == 0 && !it.getString(1).isNullOrBlank()
    } ?: false

    private fun deletionSynced(id: Long): Boolean = resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id),
        arrayOf(Events.DELETED, Events.DIRTY), null, null, null)?.use {
        !it.moveToFirst() || (it.getInt(0) == 1 && it.getInt(1) == 0)
    } ?: true

    /** Semantic click only in NanoVoice's visible UI; never a coordinate or another app's button. */
    private fun clickApproval(): Boolean {
        val root = uiAutomation.rootInActiveWindow ?: return false
        if (root.packageName?.toString() != targetContext.packageName) return false
        val found = root.findAccessibilityNodeInfosByText("Approva")
            .firstOrNull { it.text?.toString() == "Approva" && it.isVisibleToUser } ?: return false
        var button: AccessibilityNodeInfo? = found
        while (button != null && !button.isClickable) button = button.parent
        return button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }
}
