package dev.nanovoice

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A picture of the chat as it goes in the zip: its [name] there, its bytes as kept. */
internal class ExportedPicture(val messageId: Long, val name: String, val bytes: ByteArray)

private val PRETTY = Json { prettyPrint = true }

private fun iso(time: ZonedDateTime) = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time.truncatedTo(ChronoUnit.SECONDS))

/**
 * The whole chat as JSON, for "Esporta i messaggi": every message in order with its time and who said it, the earlier
 * ones (folded under "messaggi precedenti") too, and the app's own lines (a new conversation, an alert) where they came.
 * A message with a picture names its file in the zip ([pictures], by message); without them, it only says it had one.
 */
internal fun chatJson(messages: List<ChatMessage>, now: ZonedDateTime, version: String, pictures: Map<Long, String>): String {
    val export = buildJsonObject {
        put("app", "NanoVoice")
        put("versione", version)
        put("esportato", iso(now))
        put("messaggi", buildJsonArray {
            for (message in messages) add(buildJsonObject {
                put("ora", iso(ZonedDateTime.ofInstant(Instant.ofEpochMilli(message.time), now.zone)))
                when {
                    message.alertId != null -> put("tipo", "avviso")
                    message.notice -> put("tipo", "nuova_conversazione")
                    else -> put("da", if (message.fromUser) "utente" else "assistente")
                }
                put("testo", message.text)
                if (!message.notice) put("precedente", message.earlier)
                message.quote?.let { put("risposta_a", it) }
                message.barcode?.let { put("codice_a_barre", it) }
                if (message.image != null) put("immagine", pictures[message.id] ?: if (message.fromUser) "foto" else "immagine")
                if (message.failed) put("non_riuscita", true)
            })
        })
    }
    return PRETTY.encodeToString(JsonObject.serializer(), export) + "\n"
}

/**
 * The pictures of the chat for the zip, numbered in chat order: the picture itself where the chat still has it (an
 * agent's, a recent photo), otherwise what is kept of it, its [preview] (only a thumbnail for an older photo).
 */
internal fun picturesOf(messages: List<ChatMessage>, preview: (ChatMessage) -> ByteArray?): List<ExportedPicture> {
    var n = 0
    return messages.mapNotNull { message ->
        if (message.image == null) return@mapNotNull null
        val photo = message.photo
        val (bytes, ext) = when {
            photo != null -> photo.bytes to photo.mime.substringAfter('/').replace("jpeg", "jpg")
            else -> (preview(message) ?: return@mapNotNull null) to "jpg"
        }
        ExportedPicture(message.id, "immagini/%03d.%s".format(++n, ext), bytes)
    }
}

/** Whether some photos of the person are only thumbnails now: the export says so before it is made. */
internal fun hasOnlyThumbnails(messages: List<ChatMessage>) = messages.any { it.fromUser && it.image != null && it.photo == null }

/** The zip: the messages as JSON at the top, the pictures in their folder. */
internal fun writeChatZip(out: OutputStream, json: String, pictures: List<ExportedPicture>) {
    ZipOutputStream(out).use { zip ->
        zip.putNextEntry(ZipEntry("messaggi.json"))
        zip.write(json.toByteArray())
        zip.closeEntry()
        for (picture in pictures) {
            zip.putNextEntry(ZipEntry(picture.name))
            zip.write(picture.bytes)
            zip.closeEntry()
        }
    }
}

/** The name of an export made on [now]: one a day, the latest replacing the previous in the share cache. */
internal fun exportName(now: ZonedDateTime, zip: Boolean) = "nanovoice-chat-${now.toLocalDate()}.${if (zip) "zip" else "json"}"

/**
 * Hands the chat to the app the person picks, to save it (Drive, Files) or to send it: as JSON, or as a zip with its
 * pictures too when [withPictures]. False when it could not be done.
 */
suspend fun shareChat(context: Context, messages: List<ChatMessage>, version: String, withPictures: Boolean): Boolean = runCatching {
    val file = withContext(Dispatchers.IO) {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val pictures = if (withPictures) picturesOf(messages, ::previewJpeg) else emptyList()
        val json = chatJson(messages, now, version, pictures.associate { it.messageId to it.name })
        val dir = File(context.cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
        File(dir, exportName(now, zip = withPictures)).apply {
            if (withPictures) outputStream().use { writeChatZip(it, json, pictures) } else writeText(json)
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
    val send = Intent(Intent.ACTION_SEND).setType(if (withPictures) "application/zip" else "application/json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.export_subject))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri(null, uri) }
    // Not to NanoVoice itself: its "Chiedi a NanoVoice" takes text and photos, not an export.
    val notHere = arrayOf(ComponentName(context, ShareActivity::class.java))
    context.startActivity(
        Intent.createChooser(send, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, notHere).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}.isSuccess

private fun previewJpeg(message: ChatMessage): ByteArray? = message.image?.let { image ->
    ByteArrayOutputStream().use { out ->
        image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 92, out)
        out.toByteArray()
    }
}
