package dev.nanovoice.actions.photo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal const val OPEN_DATA_USER_AGENT = "NanoVoice/0.1 (personal voice assistant)"

/**
 * GET a JSON page of an open database (no account, only an application User-Agent): its body, or null when the
 * database answers 404 (it does not know the code). Any other answer is an error naming [service].
 */
internal suspend fun openDataGet(url: String, service: String): String? = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 8_000
    conn.readTimeout = 12_000
    conn.instanceFollowRedirects = false
    conn.setRequestProperty("User-Agent", OPEN_DATA_USER_AGENT)
    try {
        when (val code = conn.responseCode) {
            200 -> conn.inputStream.use { it.readCapped(service).decodeToString() }
            404 -> null
            else -> error("$service ha risposto con l'errore $code")
        }
    } finally {
        conn.disconnect()
    }
}

/** Larger than any answer of these services: a bigger body is refused instead of filling the memory. */
internal const val MAX_BODY_BYTES = 2 * 1024 * 1024

/** The whole body, or an error naming [service] when it is larger than [max]. */
internal fun java.io.InputStream.readCapped(service: String, max: Int = MAX_BODY_BYTES): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        if (out.size() + n > max) error("$service ha mandato una risposta troppo grande")
        out.write(buffer, 0, n)
    }
}

/** The few HTML entities these databases leave in their text ("&gt; 18%"). */
internal fun withoutEntities(text: String): String = text
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
