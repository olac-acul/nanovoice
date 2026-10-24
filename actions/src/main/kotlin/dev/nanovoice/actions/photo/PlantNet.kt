package dev.nanovoice.actions.photo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.roundToInt

data class PlantMatch(val scientificName: String, val commonNames: List<String>, val family: String?, val score: Double)

/**
 * Pl@ntNet (my.plantnet.org), the plant identification service of a public research consortium: the photo is kept in
 * memory only while it is identified. A free account key allows 500 identifications a day.
 */
object PlantNet {
    private const val ENDPOINT = "https://my-api.plantnet.org/v2/identify/all"
    val ORGANS = setOf("auto", "leaf", "flower", "fruit", "bark")
    private val json = Json { ignoreUnknownKeys = true }

    /** What the agent gets back: the best species with their confidence, or why there are none. */
    suspend fun identify(key: String, jpeg: ByteArray, organ: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$ENDPOINT?api-key=${URLEncoder.encode(key, "UTF-8")}&lang=it&nb-results=3"
            val boundary = "nanovoice-${UUID.randomUUID()}"
            val body = multipart(boundary, jpeg, organ.takeIf { it in ORGANS } ?: "auto")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setFixedLengthStreamingMode(body.size)
            try {
                conn.outputStream.use { it.write(body) }
                when (val code = conn.responseCode) {
                    200 -> conn.inputStream.use { describe(parse(it.readCapped("Pl@ntNet").decodeToString())) }
                    404 -> "Pl@ntNet non ha riconosciuto nessuna pianta nella foto."
                    401, 403 -> error("la chiave Pl@ntNet non è valida (Impostazioni → Foto)")
                    429 -> error("i riconoscimenti gratuiti di oggi su Pl@ntNet sono finiti")
                    else -> error("Pl@ntNet ha risposto con l'errore $code")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    fun parse(body: String): List<PlantMatch> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        return (root["results"] as? JsonArray).orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val species = o["species"] as? JsonObject ?: return@mapNotNull null
            val name = species.string("scientificNameWithoutAuthor") ?: return@mapNotNull null
            PlantMatch(
                scientificName = name,
                commonNames = (species["commonNames"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                family = (species["family"] as? JsonObject)?.string("scientificNameWithoutAuthor"),
                score = (o["score"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            )
        }
    }

    fun describe(matches: List<PlantMatch>): String {
        if (matches.isEmpty()) return "Pl@ntNet non ha riconosciuto nessuna pianta nella foto."
        return "Risultati di Pl@ntNet: " + matches.take(3).joinToString("; ") { m ->
            val common = m.commonNames.take(3).map { thirdPartyText(it, 60) }.takeIf { it.isNotEmpty() }
                ?.joinToString(", ", prefix = " (nomi comuni: ", postfix = ")").orEmpty()
            val family = m.family?.let { ", famiglia ${thirdPartyText(it, 60)}" }.orEmpty()
            "${thirdPartyText(m.scientificName, 80)}$common$family, sicurezza ${(m.score * 100).roundToInt()}%"
        }
    }

    private fun multipart(boundary: String, jpeg: ByteArray, organ: String): ByteArray = ByteArrayOutputStream().apply {
        fun text(s: String) = write(s.toByteArray())
        text("--$boundary\r\nContent-Disposition: form-data; name=\"images\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
        write(jpeg)
        text("\r\n--$boundary\r\nContent-Disposition: form-data; name=\"organs\"\r\n\r\n$organ\r\n--$boundary--\r\n")
    }.toByteArray()

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
