package dev.nanovoice

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * The conversation on the screen, kept in the app's private files: a restart of the app (Android ends it to free
 * memory, an update) shows it again, with what came before the server started it over (folded on screen), up to
 * [MAX_KEPT] messages in all. "Nuova conversazione" empties it, as it empties the screen. A photo keeps its thumbnail; the photo itself (for TinEye) is not
 * kept. Never in a backup: the app opts out of Android's cloud backup and device transfer.
 */
class ChatStore(private val file: File) {
    fun load(): List<ChatMessage> = try {
        if (!file.isFile) emptyList() else decodeChat(file.readText()).map { it.toMessage() }
    } catch (_: Exception) {
        emptyList() // a damaged file is a lost conversation, never a crash
    }

    fun save(messages: List<ChatMessage>) {
        if (messages.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(encodeChat(messages.takeLast(MAX_KEPT).map { it.toStored() }))
        if (!temp.renameTo(file)) temp.delete()
    }

    private fun ChatMessage.toStored() = StoredMessage(
        id, text, fromUser, time, quote, barcode, failed, notice,
        preview = image?.let(::jpeg),
        earlier = earlier,
    )

    private fun StoredMessage.toMessage() = ChatMessage(
        id, text, fromUser,
        image = preview?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() },
        time = time, quote = quote, barcode = barcode, failed = failed, notice = notice, earlier = earlier,
    )

    private fun jpeg(image: ImageBitmap): ByteArray = ByteArrayOutputStream().use { out ->
        image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 80, out)
        out.toByteArray()
    }

    private companion object {
        /** A long day of messages; older ones go, as they would scroll out of reach anyway. */
        const val MAX_KEPT = 200
    }
}

/** A message as it is written to the file: the thumbnail as JPEG bytes. */
internal data class StoredMessage(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
    val time: Long,
    val quote: String? = null,
    val barcode: String? = null,
    val failed: Boolean = false,
    val notice: Boolean = false,
    val preview: ByteArray? = null,
    val earlier: Boolean = false,
)

internal fun encodeChat(messages: List<StoredMessage>): String = buildJsonObject {
    put("version", 1)
    put("messages", buildJsonArray {
        for (m in messages) add(buildJsonObject {
            put("id", m.id)
            put("text", m.text)
            put("fromUser", m.fromUser)
            put("time", m.time)
            m.quote?.let { put("quote", it) }
            m.barcode?.let { put("barcode", it) }
            if (m.failed) put("failed", true)
            if (m.notice) put("notice", true)
            if (m.earlier) put("earlier", true)
            m.preview?.let { put("preview", Base64.getEncoder().encodeToString(it)) }
        })
    })
}.toString()

/** The messages of a stored conversation; one it cannot read is skipped, never the whole file. */
internal fun decodeChat(text: String): List<StoredMessage> {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
    val list = root["messages"] as? JsonArray ?: return emptyList()
    return list.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        fun str(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun bool(key: String) = (o[key] as? JsonPrimitive)?.booleanOrNull ?: false
        StoredMessage(
            id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
            text = str("text") ?: return@mapNotNull null,
            fromUser = bool("fromUser"),
            time = (o["time"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
            quote = str("quote"),
            barcode = str("barcode"),
            failed = bool("failed"),
            notice = bool("notice"),
            earlier = bool("earlier"),
            preview = str("preview")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() },
        )
    }
}
