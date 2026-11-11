package dev.nanovoice

import dev.nanovoice.agent.AgentFile
import dev.nanovoice.agent.AgentImage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

/** A file the agent sent on its own, waiting for the app's chat; [id] orders them as they came. */
class PendingFile(val id: Long, val file: AgentFile)

/**
 * Files the agent sends on its own (a chart with a scheduled recap): kept in the app's private files until the app's
 * chat shows them, one file per item, so one that comes while the app is closed (woken by a push) is still there when
 * it is opened, after Android ended the process too. Only the app's chat takes them, never the assistant's sheet.
 */
class FileInbox(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private var nextId = 1L
    private val _pending = MutableStateFlow<List<PendingFile>>(emptyList())

    /** What waits for the chat, oldest first. */
    val pending: StateFlow<List<PendingFile>> = _pending

    init {
        load()
    }

    /** Keeps [file] until the chat shows it; beyond [MAX_PENDING] the oldest goes. */
    suspend fun receive(file: AgentFile): PendingFile = withContext(io) {
        synchronized(lock) {
            val item = PendingFile(nextId++, file)
            runCatching { write(item) } // not kept on disk (no space): still shown if the chat is there
            val all = _pending.value + item
            val dropped = all.dropLast(MAX_PENDING)
            dropped.forEach { fileOf(it.id).delete() }
            _pending.value = all.takeLast(MAX_PENDING)
            item
        }
    }

    /** The chat has shown it: it goes. */
    suspend fun remove(id: Long) = withContext(io) {
        synchronized(lock) {
            fileOf(id).delete()
            _pending.update { list -> list.filterNot { it.id == id } }
        }
    }

    private fun fileOf(id: Long) = File(dir, "$id.json")

    private fun write(item: PendingFile) {
        dir.mkdirs()
        val temp = File(dir, "${item.id}.json.tmp")
        temp.writeText(encodePendingFile(item.file))
        if (!temp.renameTo(fileOf(item.id))) {
            temp.delete()
            error("not kept")
        }
    }

    private fun load() {
        val stored = dir.listFiles().orEmpty()
            .mapNotNull { f -> f.name.removeSuffix(".json").toLongOrNull()?.takeIf { f.name.endsWith(".json") }?.let { it to f } }
            .sortedBy { it.first }
            .mapNotNull { (id, f) ->
                // A damaged one is dropped, never the app.
                runCatching { decodePendingFile(f.readText()) }.getOrNull()?.let { PendingFile(id, it) } ?: run { f.delete(); null }
            }
        dir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        _pending.value = stored.takeLast(MAX_PENDING)
        stored.dropLast(MAX_PENDING).forEach { fileOf(it.id).delete() }
        nextId = (stored.maxOfOrNull { it.id } ?: 0L) + 1
    }

    private companion object {
        /** As many as the server keeps for an app that is offline, and a few more. */
        const val MAX_PENDING = 8
    }
}

internal fun encodePendingFile(file: AgentFile): String = buildJsonObject {
    put("version", 1)
    put("name", file.name)
    if (file.tooBig) put("tooBig", true)
    file.image?.let {
        put("mime", it.mime)
        put("data", Base64.getEncoder().encodeToString(it.bytes))
    }
}.toString()

internal fun decodePendingFile(text: String): AgentFile? {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
    fun str(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val name = str("name") ?: return null
    val mime = str("mime")
    val data = str("data")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    val image = if (mime != null && data != null) AgentImage(mime, data) else null
    return AgentFile(name, image, tooBig = (root["tooBig"] as? JsonPrimitive)?.booleanOrNull ?: false)
}
