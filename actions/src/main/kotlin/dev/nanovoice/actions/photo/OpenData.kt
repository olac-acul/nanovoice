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
            200 -> conn.inputStream.use { it.readBytes().decodeToString() }
            404 -> null
            else -> error("$service ha risposto con l'errore $code")
        }
    } finally {
        conn.disconnect()
    }
}

/** The few HTML entities these databases leave in their text ("&gt; 18%"). */
internal fun withoutEntities(text: String): String = text
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
