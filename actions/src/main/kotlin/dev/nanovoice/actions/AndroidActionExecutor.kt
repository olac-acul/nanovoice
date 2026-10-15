package dev.nanovoice.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.content.ComponentName
import dev.nanovoice.actions.music.MusicAccount
import dev.nanovoice.actions.music.NavidromeClient
import dev.nanovoice.actions.music.PlayPlan
import dev.nanovoice.actions.music.Subsonic
import dev.nanovoice.actions.music.TempoPlayer
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioAttributes
import kotlinx.coroutines.delay
import android.os.BatteryManager
import android.view.KeyEvent
import java.time.LocalDateTime
import dev.nanovoice.core.ActionExecutor
import dev.nanovoice.core.ActionResult
import dev.nanovoice.core.PhoneAction
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import kotlin.time.Duration

/** A shorter name is a fishing query, not a person: refused. */
private const val MIN_NAME_QUERY = 3

/** Single entry point for every phone action, whether requested by the local router or by NanoClaw. */
class AndroidActionExecutor(
    context: Context,
    private val phrases: SpokenPhrases,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** The Navidrome account for "riproduci …", from the settings; null when not configured. */
    private val music: () -> MusicAccount? = { null },
) : ActionExecutor {
    private val context = context.applicationContext

    override suspend fun execute(action: PhoneAction): ActionResult = when (action) {
        PhoneAction.TellTime -> ActionResult(true, phrases.time(LocalTime.now(clock)))
        PhoneAction.TellDate -> ActionResult(true, phrases.date(LocalDate.now(clock)))
        is PhoneAction.SetTimer -> setTimer(action.duration)
        is PhoneAction.SetAlarm -> setAlarm(action)
        is PhoneAction.StopTimer -> stopTimer()
        is PhoneAction.StopAlarm -> stopAlarm(action)
        is PhoneAction.SnoozeAlarm -> snoozeAlarm(action.minutes)
        is PhoneAction.OpenApp -> openApp(action.name)
        is PhoneAction.Call -> call(action.target)
        is PhoneAction.Media -> media(action.command)
        is PhoneAction.PlayMusic -> playMusic(action.query)
        is PhoneAction.SignalDraft -> signalDraft(action)
        is PhoneAction.Note -> note(action)
        is PhoneAction.Volume -> volume(action)
        is PhoneAction.Flashlight -> flashlight(action.on)
        is PhoneAction.Navigate -> view(Uri.parse("geo:0,0?q=" + Uri.encode(action.destination)), phrases.navigating(action.destination), phrases.noMapsApp)
        is PhoneAction.OpenUrl -> openUrl(action.url)
        is PhoneAction.SmsDraft -> smsDraft(action)
        is PhoneAction.CalendarDraft -> calendarDraft(action)
        PhoneAction.Battery -> battery()
        is PhoneAction.CalendarRead -> calendarRead(action.days.coerceIn(1, 14))
        PhoneAction.Location -> location()
        is PhoneAction.Notifications -> notifications(action.limit.coerceIn(1, 10))
    }

    /** Upcoming events as one line each, joined with "; " (the result travels as a single line to the agent). */
    private suspend fun calendarRead(days: Int): ActionResult = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return@withContext ActionResult(false, phrases.calendarPermissionNeeded)
        }
        val zone = clock.zone
        val from = java.time.LocalDate.now(clock).atStartOfDay(zone)
        val to = from.plusDays(days.toLong())
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, from.toInstant().toEpochMilli())
            android.content.ContentUris.appendId(it, to.toInstant().toEpochMilli())
        }.build()
        val columns = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.END, CalendarContract.Instances.RRULE, CalendarContract.Instances.RDATE, CalendarContract.Instances.ORIGINAL_ID, CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        val format = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", java.util.Locale.ITALIAN)
        val dayOnly = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ITALIAN)
        val lines = mutableListOf<String>()
        context.contentResolver.query(uri, columns, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && lines.size < MAX_EVENTS) {
                val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(senza titolo)"
                val begin = java.time.Instant.ofEpochMilli(c.getLong(1)).atZone(if (c.getInt(2) == 1) java.time.ZoneOffset.UTC else zone)
                val whenText = if (c.getInt(2) == 1) begin.format(dayOnly) + " tutto il giorno" else begin.format(format)
                val place = c.getString(3)?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                val finish = java.time.Instant.ofEpochMilli(c.getLong(5)).atZone(if (c.getInt(2) == 1) java.time.ZoneOffset.UTC else zone)
                val recurring = !c.getString(6).isNullOrBlank() || !c.getString(7).isNullOrBlank() || !c.isNull(8)
                val endText = if (c.getInt(2) == 1) finish.format(dayOnly) + " (data esclusa)" else finish.format(format)
                lines += "[event_id=${c.getLong(4)}, calendario=${c.getString(9).orEmpty()}${if (recurring) ", ricorrente: usa l’app Calendario per modificarlo" else ""}] $whenText $title$place; fine: $endText"
            }
        }
        if (lines.isEmpty()) ActionResult(true, phrases.noEvents(days)) else ActionResult(true, lines.joinToString("; "))
    }

    /** The current position from the phone's own providers (no Google services): GPS first, then network. */
    private suspend fun location(): ActionResult {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return ActionResult(false, phrases.locationPermissionNeeded)
        val manager = context.getSystemService(android.location.LocationManager::class.java)
        val providers = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val found = kotlinx.coroutines.withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            for (provider in providers) {
                val here = currentLocation(manager, provider)
                if (here != null) return@withTimeoutOrNull here
            }
            null
        } ?: providers.firstNotNullOfOrNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        found ?: return ActionResult(false, phrases.locationUnavailable)
        return ActionResult(true, "%.5f, %.5f (precisione %d m)".format(java.util.Locale.ROOT, found.latitude, found.longitude, found.accuracy.toInt()))
    }

    @android.annotation.SuppressLint("MissingPermission") // checked by the caller
    private suspend fun currentLocation(manager: android.location.LocationManager, provider: String): android.location.Location? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val cancel = android.os.CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                manager.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) {} }
            } else {
                cont.resume(runCatching { manager.getLastKnownLocation(provider) }.getOrNull()) {}
            }
        }

    private fun notifications(limit: Int): ActionResult {
        if (!NanoNotificationListener.hasAccess(context)) return ActionResult(false, phrases.notificationAccessNeeded)
        val items = NanoNotificationListener.snapshot(context, limit) ?: return ActionResult(false, phrases.notificationAccessNeeded)
        if (items.isEmpty()) return ActionResult(true, phrases.noNotifications)
        val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        return ActionResult(true, items.joinToString("; ") { n ->
            val at = java.time.Instant.ofEpochMilli(n.postedAt).atZone(clock.zone).format(time)
            listOf("$at ${n.app}", n.title, n.text).filter { it.isNotBlank() }.joinToString(": ").replace('\n', ' ')
        })
    }

    private fun media(command: PhoneAction.MediaCommand): ActionResult {
        val code = when (command) {
            PhoneAction.MediaCommand.Play -> KeyEvent.KEYCODE_MEDIA_PLAY
            PhoneAction.MediaCommand.Pause -> KeyEvent.KEYCODE_MEDIA_PAUSE
            PhoneAction.MediaCommand.Toggle -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            PhoneAction.MediaCommand.Next -> KeyEvent.KEYCODE_MEDIA_NEXT
            PhoneAction.MediaCommand.Previous -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        // The same key events headphones send: whatever app is playing (or played last) handles them.
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ActionResult(true, phrases.media(command))
    }

    /**
     * Plays [query] in Tempo. Tempo cannot search when asked from outside, so NanoVoice searches Navidrome itself
     * (the account set in the settings) and hands Tempo the songs: one song, or a whole album or artist when the
     * request is just its name. Then Tempo's screen comes up.
     */
    private suspend fun playMusic(query: String): ActionResult {
        val account = music() ?: return ActionResult(false, phrases.musicNotConfigured)
        val tempo = TempoPlayer(context)
        val pkg = tempo.installed() ?: return ActionResult(false, phrases.noMusicApp)
        if (!tempo.trusted(pkg)) return ActionResult(false, phrases.musicAppUntrusted)
        val client = NavidromeClient(account)
        val result = client.search(query).getOrElse { return ActionResult(false, phrases.musicServerError) }
        val plan = Subsonic.plan(query, result, client::albumSongs, client::artistAlbums)
            ?: return ActionResult(false, phrases.musicNotFound(query))
        val items = plan.songs.map { TempoPlayer.item(it, client.streamUrl(it), client.coverUrl(it)) }
        // Play while NanoVoice is still on screen (Android lets a foreground app start someone else's playback),
        // then show Tempo.
        val outcome = tempo.play(pkg, items)
        if (outcome == TempoPlayer.Outcome.Failed) return ActionResult(false, phrases.noMusicApp)
        context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { runCatching { context.startActivity(it) } }
        val first = plan.songs.first()
        // Only "Metto X" when it is heard: taken but silent is said as it is.
        if (outcome == TempoPlayer.Outcome.Queued) return ActionResult(true, phrases.musicQueued(plan.label))
        return ActionResult(true, when (plan.kind) {
            PlayPlan.Kind.Song -> phrases.playingSong(first.title, first.artist)
            PlayPlan.Kind.Album -> phrases.playingAlbum(plan.label)
            PlayPlan.Kind.Artist -> phrases.playingArtist(plan.label)
        })
    }

    /** Signal accepts shared text but not a recipient: its share screen opens with the text, the user picks the chat. */
    private fun signalDraft(action: PhoneAction.SignalDraft): ActionResult {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .setPackage(SIGNAL)
            .putExtra(Intent.EXTRA_TEXT, action.text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ActionResult(true, phrases.signalDraft(action.to?.trim()?.takeIf { it.isNotEmpty() }))
        } catch (_: ActivityNotFoundException) {
            ActionResult(false, phrases.noSignal)
        }
    }

    /** Nextcloud Notes' editor turns shared text into a new note (subject = title) and syncs it with the server itself. */
    private fun note(action: PhoneAction.Note): ActionResult {
        // Notes writes the subject as the note's first line, then the text: an invented title would repeat the
        // text's opening words, so the subject goes only when the user named a title.
        val title = action.title?.trim()?.takeIf { it.isNotEmpty() }
        // Notes has two share targets: "append to a note" (asks which one) and the editor, which creates a new note.
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .setComponent(ComponentName(NOTES, "$NOTES.edit.EditNoteActivity"))
            .apply { title?.let { putExtra(Intent.EXTRA_SUBJECT, it) } }
            .putExtra(Intent.EXTRA_TEXT, action.text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Without a distinct identity Android sees the same intent as the Notes task left by the previous note,
        // brings that task back and drops this text.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) intent.setIdentifier("note-${System.nanoTime()}")
        return try {
            context.startActivity(intent)
            ActionResult(true, phrases.noteWritten)
        } catch (_: ActivityNotFoundException) {
            ActionResult(false, phrases.noNotesApp)
        }
    }

    private fun volume(action: PhoneAction.Volume): ActionResult {
        val audio = context.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        when {
            action.mute -> {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                return ActionResult(true, phrases.volumeMuted)
            }
            action.percent != null -> {
                val percent = action.percent ?: 0
                audio.setStreamVolume(stream, (percent.coerceIn(0, 100) * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
            }
            else -> audio.adjustStreamVolume(stream, if (action.up == false) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        }
        return ActionResult(true, phrases.volumeSet(audio.getStreamVolume(stream) * 100 / max))
    }

    private fun flashlight(on: Boolean): ActionResult {
        val cameras = context.getSystemService(CameraManager::class.java)
        val id = cameras.cameraIdList.firstOrNull {
            val c = cameras.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return ActionResult(false, phrases.noFlashlight)
        return try {
            cameras.setTorchMode(id, on)
            ActionResult(true, phrases.flashlight(on))
        } catch (_: Exception) {
            ActionResult(false, phrases.noFlashlight)
        }
    }

    private fun openUrl(url: String): ActionResult {
        val uri = Uri.parse(url.trim())
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return ActionResult(false, phrases.badLink)
        return view(uri, phrases.openingLink, phrases.noBrowser)
    }

    private fun view(uri: Uri, done: String, missing: String): ActionResult = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ActionResult(true, done)
    } catch (_: ActivityNotFoundException) {
        ActionResult(false, missing)
    }

    /** Fills in the messaging app; never sends. A name is looked up in the contacts like for calls. */
    private suspend fun smsDraft(action: PhoneAction.SmsDraft): ActionResult = withContext(Dispatchers.IO) {
        var number = ""
        var name: String? = null
        val to = action.to?.trim().orEmpty()
        if (to.isNotEmpty()) {
            if (to.all { it.isDigit() || it == '+' || it == ' ' }) {
                number = to.filter { !it.isWhitespace() }
            } else {
                if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    return@withContext ActionResult(false, phrases.contactsPermissionNeeded)
                }
                if (to.length < MIN_NAME_QUERY) return@withContext ActionResult(false, phrases.contactNotFound(to))
                val matches = NameMatching.best(to, readPhoneEntries(), partialWords = false) { it.name }
                val people = matches.map { it.contactId }.distinct()
                when {
                    people.isEmpty() -> return@withContext ActionResult(false, phrases.contactNotFound(to))
                    people.size > 1 -> return@withContext ActionResult(false, phrases.contactAmbiguous(matches.map { it.name }.distinct()))
                }
                val mine = matches.filter { it.contactId == people.single() }
                number = (mine.firstOrNull { it.type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE } ?: mine.first()).number
                name = mine.first().name
            }
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .putExtra("sms_body", action.text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            ActionResult(true, phrases.smsDraft(name ?: number.takeIf { it.isNotEmpty() }))
        } catch (_: ActivityNotFoundException) {
            ActionResult(false, phrases.noMessagingApp)
        }
    }

    /** Opens the calendar's new-event screen filled in; never saves by itself. */
    private fun calendarDraft(action: PhoneAction.CalendarDraft): ActionResult {
        val zone = clock.zone
        val start = runCatching { LocalDateTime.parse(action.start) }.getOrNull() ?: return ActionResult(false, phrases.badEventTime)
        val end = action.end?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() ?: return ActionResult(false, phrases.badEventTime) }
            ?: start.plusHours(1)
        if (end.isBefore(start)) return ActionResult(false, phrases.badEventTime)
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, action.title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.atZone(zone).toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(zone).toInstant().toEpochMilli())
            .apply { action.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ActionResult(true, phrases.calendarDraft(action.title))
        } catch (_: ActivityNotFoundException) {
            ActionResult(false, phrases.noCalendarApp)
        }
    }

    private fun battery(): ActionResult {
        val manager = context.getSystemService(BatteryManager::class.java)
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return ActionResult(true, phrases.battery(percent, manager.isCharging))
    }

    private data class AppEntry(val label: String, val packageName: String)
    /** [contactId] tells two people apart even when their display names are the same. */
    private data class PhoneEntry(val name: String, val number: String, val type: Int, val contactId: Long)

    private suspend fun openApp(query: String): ActionResult = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
        // several apps may match ("Camera" twice): the shortest label is the closest to what was said
        val app = NameMatching.best(query, apps) { it.label }.minByOrNull { it.label.length }
            ?: return@withContext ActionResult(false, phrases.appNotFound(query))
        val launch = pm.getLaunchIntentForPackage(app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return@withContext ActionResult(false, phrases.appNotFound(query))
        try {
            context.startActivity(launch)
            ActionResult(true, phrases.openingApp(app.label))
        } catch (_: ActivityNotFoundException) {
            ActionResult(false, phrases.appNotFound(query))
        }
    }

    /** Opens the dialer with the number filled in (never places the call itself: a misheard name must not ring anyone). */
    private suspend fun call(target: String): ActionResult = withContext(Dispatchers.IO) {
        // A number as spoken or written: "+39 333 123 4567", "011-123.45.67", "(02) 1234".
        val asNumber = target.filter { it !in " -./()" }
        if (asNumber.matches(Regex("\\+?\\d{3,15}"))) return@withContext dial(asNumber, phrases.callingNumber)
        if (target.trim().length < MIN_NAME_QUERY) return@withContext ActionResult(false, phrases.contactNotFound(target))
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext ActionResult(false, phrases.contactsPermissionNeeded)
        }
        val entries = readPhoneEntries()
        val matches = NameMatching.best(target, entries, partialWords = false) { it.name }
        val people = matches.map { it.contactId }.distinct()
        when {
            people.isEmpty() -> ActionResult(false, phrases.contactNotFound(target))
            people.size > 1 -> ActionResult(false, phrases.contactAmbiguous(matches.map { it.name }.distinct()))
            else -> {
                val mine = matches.filter { it.contactId == people.single() }
                val entry = mine.firstOrNull { it.type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE } ?: mine.first()
                dial(entry.number, phrases.calling(entry.name))
            }
        }
    }

    private fun readPhoneEntries(): List<PhoneEntry> {
        val columns = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
        )
        val out = mutableListOf<PhoneEntry>()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                out += PhoneEntry(name, number, c.getInt(2), c.getLong(3))
            }
        }
        return out
    }

    private fun dial(number: String, spoken: String): ActionResult = try {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        ActionResult(true, spoken)
    } catch (_: ActivityNotFoundException) {
        ActionResult(false, phrases.dialerNotFound)
    }

    private fun setAlarm(action: PhoneAction.SetAlarm): ActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, action.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            // The clock app rings at the next occurrence of the time: "domani" is wrong if it is still ahead today.
            val ringsToday = action.tomorrow && LocalTime.of(action.hour, action.minute).isAfter(LocalTime.now(clock))
            ActionResult(true, phrases.alarmSet(action.hour, action.minute, ringsToday))
        } catch (_: ActivityNotFoundException) {
            ActionResult(false, phrases.alarmNoApp)
        } catch (_: SecurityException) {
            ActionResult(false, phrases.alarmDenied)
        }
    }

    private fun setTimer(duration: Duration): ActionResult {
        val seconds = duration.inWholeSeconds
        if (seconds !in 1..MAX_TIMER_SECONDS) return ActionResult(false, phrases.timerTooLong)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ActionResult(true, phrases.timerSet(duration))
        } catch (_: ActivityNotFoundException) {
            ActionResult(false, phrases.timerNoApp)
        } catch (_: SecurityException) {
            ActionResult(false, phrases.timerDenied)
        }
    }

    /**
     * Something of the clock app is ringing: an alarm or a timer, which play as alarm sound. Which of the two cannot
     * be told from the sound, so the timer is tried first wherever that matters (see [stopAlarm]).
     */
    private fun clockRinging(): Boolean = runCatching {
        context.getSystemService(AudioManager::class.java).activePlaybackConfigurations
            .any { it.audioAttributes.usage == AudioAttributes.USAGE_ALARM }
    }.getOrDefault(false)

    private fun startClock(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** Silences a ringing timer (Android 9 and later); says whether something is still ringing afterwards. */
    private suspend fun dismissRingingTimer(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        startClock(Intent(AlarmClock.ACTION_DISMISS_TIMER))
        delay(RING_CHECK_MS)
        return clockRinging()
    }

    private suspend fun stopTimer(): ActionResult {
        if (clockRinging()) {
            val stillRinging = dismissRingingTimer()
            return if (stillRinging) ActionResult(false, phrases.ringingIsAlarm) else ActionResult(true, phrases.timerStopped)
        }
        // A running timer cannot be stopped from outside the clock app: its timers screen is the closest thing.
        return if (startClock(Intent(AlarmClock.ACTION_SHOW_TIMERS))) ActionResult(true, phrases.timersOpened)
        else ActionResult(false, phrases.timerNoApp)
    }

    private suspend fun stopAlarm(action: PhoneAction.StopAlarm): ActionResult {
        val hour = action.hour
        if (hour != null) {
            if (hour !in 0..23 || (action.minute ?: 0) !in 0..59) return ActionResult(false, phrases.alarmNoApp)
            val skip = Intent(AlarmClock.ACTION_DISMISS_ALARM)
                .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, action.minute ?: 0)
            return if (startClock(skip)) ActionResult(true, phrases.alarmSkipped(hour, action.minute ?: 0))
            else ActionResult(false, phrases.alarmNoApp)
        }
        if (!clockRinging()) return ActionResult(false, phrases.nothingRinging)
        // What rings may be a timer: "dismiss the next alarm" would then skip a real alarm still to come. So the timer
        // goes first, and only if something still rings is it an alarm.
        if (!dismissRingingTimer()) return ActionResult(true, phrases.timerStopped)
        val dismiss = Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        return if (startClock(dismiss)) ActionResult(true, phrases.alarmStopped) else ActionResult(false, phrases.alarmNoApp)
    }

    private fun snoozeAlarm(minutes: Int?): ActionResult {
        if (!clockRinging()) return ActionResult(false, phrases.nothingRinging)
        val snooze = Intent(AlarmClock.ACTION_SNOOZE_ALARM).apply {
            if (minutes != null) putExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, minutes.coerceIn(1, 60))
        }
        return if (startClock(snooze)) ActionResult(true, phrases.alarmSnoozed(minutes?.coerceIn(1, 60)))
        else ActionResult(false, phrases.alarmNoApp)
    }

    private companion object {
        /** How long the clock app takes to silence a timer before the sound is checked again. */
        const val RING_CHECK_MS = 900L
        const val MAX_EVENTS = 15
        const val SIGNAL = "org.thoughtcrime.securesms"
        const val NOTES = "it.niedermann.owncloud.notes"
        const val LOCATION_TIMEOUT_MS = 15_000L
        const val MAX_TIMER_SECONDS = 24 * 60 * 60L // AlarmClock.EXTRA_LENGTH limit
    }
}
