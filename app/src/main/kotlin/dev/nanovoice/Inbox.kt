package dev.nanovoice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Something the agent said on its own (a scheduled recap, a reminder), kept apart from the conversation. */
data class Alert(val id: Long, val text: String, val time: Long, val read: Boolean = false) {
    val title: String get() = alertTitle(text)
}

/** The first line of [text] with something to read, without Markdown or addresses, short enough for one line. */
internal fun alertTitle(text: String): String {
    val line = text.lineSequence().map { withoutMarkdown(withoutUrls(it)) }.firstOrNull { l -> l.any { it.isLetterOrDigit() } }.orEmpty()
    return if (line.length <= TITLE_CHARS) line else line.take(TITLE_CHARS).trimEnd() + "…"
}

/** What follows the title, as plain text on one line: the preview under it in the list. */
internal fun alertPreview(text: String): String {
    val lines = text.lineSequence().map { withoutMarkdown(withoutUrls(it)) }.filter { l -> l.any { it.isLetterOrDigit() } }.toList()
    return lines.drop(1).joinToString(" ")
}

/**
 * A message with no request open is the answer to the last request when that one is recent and still has no answer
 * (it failed, was interrupted, or the agent took longer than the reply stays open): it goes to the chat, under it.
 * Anything else is an [Alert].
 */
internal fun isLateAnswer(unansweredSince: Long?, now: Long): Boolean =
    unansweredSince != null && now - unansweredSince in 0 until LATE_ANSWER_MS

/** At most [MAX_ALERTS], none older than [MAX_AGE_MS]: the oldest go first. */
internal fun pruned(alerts: List<Alert>, now: Long): List<Alert> =
    alerts.filter { now - it.time < MAX_AGE_MS }.takeLast(MAX_ALERTS)

/**
 * The agent's messages that come with no request open (frame `notify`), sorted: a late answer goes to the chat on
 * screen, the rest are kept as [alerts] in the app's private files, also when the app is closed and only woken by a
 * push (before, those were shown as a system notification and then lost). Never in a backup, as the chat.
 */
class Inbox(
    private val file: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var unansweredSince: Long? = null
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts

    private val _lateAnswers = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Late answers, for the chat on screen to add under the request. */
    val lateAnswers: SharedFlow<String> = _lateAnswers

    private val _arrivals = MutableSharedFlow<Alert>(extraBufferCapacity = 16)

    /** Alerts as they come, for the chat on screen to add its line "Nuovo avviso". */
    val arrivals: SharedFlow<Alert> = _arrivals

    init {
        load()
    }

    /** A request was sent: until it is answered, a message coming on its own soon after may be its answer. */
    fun requestSent() = changePending(clock())

    /** The request on screen got its answer. */
    fun requestAnswered() = changePending(null)

    /** Sorts a message of the agent's own: null when it went to the chat, the new alert otherwise. */
    fun receive(text: String): Alert? {
        val now = clock()
        synchronized(lock) {
            // A late answer only when a chat is there to show it; with the app closed it is kept as an alert.
            if (isLateAnswer(unansweredSince, now) && _lateAnswers.subscriptionCount.value > 0) {
                unansweredSince = null
                _lateAnswers.tryEmit(text)
                save()
                return null
            }
            val alert = Alert(id = (_alerts.value.maxOfOrNull { it.id } ?: 0) + 1, text = text, time = now)
            _alerts.value = pruned(_alerts.value + alert, now)
            save()
            _arrivals.tryEmit(alert)
            return alert
        }
    }

    fun markRead(id: Long) = change { list -> list.map { if (it.id == id) it.copy(read = true) else it } }

    fun markAllRead() = change { list -> list.map { it.copy(read = true) } }

    fun delete(id: Long) = change { list -> list.filterNot { it.id == id } }

    fun deleteAll() = change { emptyList() }

    private fun change(edit: (List<Alert>) -> List<Alert>) = synchronized(lock) {
        _alerts.update(edit)
        save()
    }

    private fun changePending(since: Long?) = synchronized(lock) {
        if (unansweredSince == since) return@synchronized
        unansweredSince = since
        save()
    }

    private fun load() {
        val stored = try {
            if (file.isFile) decodeInbox(file.readText()) else null
        } catch (_: Exception) {
            null // a damaged file loses the alerts, never crashes the app
        }
        unansweredSince = stored?.unansweredSince
        _alerts.value = pruned(stored?.alerts.orEmpty(), clock())
    }

    private val unsaved = AtomicReference<String?>(null)

    /** Written off the caller's thread; whichever write runs writes the latest state, so none can undo a newer one. */
    private fun save() {
        unsaved.set(encodeInbox(StoredInbox(_alerts.value, unansweredSince)))
        scope.launch(io) {
            synchronized(file) {
                val text = unsaved.getAndSet(null) ?: return@synchronized
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(text)
                if (!temp.renameTo(file)) temp.delete()
            }
        }
    }
}

internal data class StoredInbox(val alerts: List<Alert>, val unansweredSince: Long?)

internal fun encodeInbox(inbox: StoredInbox): String = buildJsonObject {
    put("version", 1)
    inbox.unansweredSince?.let { put("unansweredSince", it) }
    put("alerts", buildJsonArray {
        for (a in inbox.alerts) add(buildJsonObject {
            put("id", a.id)
            put("text", a.text)
            put("time", a.time)
            if (a.read) put("read", true)
        })
    })
}.toString()

/** The stored alerts; one it cannot read is skipped, never the whole file. */
internal fun decodeInbox(text: String): StoredInbox {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return StoredInbox(emptyList(), null)
    val alerts = (root["alerts"] as? JsonArray).orEmpty().mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        Alert(
            id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
            text = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null,
            time = (o["time"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
            read = (o["read"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }
    return StoredInbox(alerts, (root["unansweredSince"] as? JsonPrimitive)?.longOrNull)
}

private const val TITLE_CHARS = 80
private const val LATE_ANSWER_MS = 15 * 60_000L
private const val MAX_ALERTS = 100
private const val MAX_AGE_MS = 30L * 24 * 60 * 60_000
