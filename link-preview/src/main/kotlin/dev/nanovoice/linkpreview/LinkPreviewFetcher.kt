package dev.nanovoice.linkpreview

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Downloads just enough of a page to build a preview. Conservative on purpose: https only, public hosts only
 * (checked again on every redirect), no cookies, short timeouts, and a hard cap on how much is read.
 */
class LinkPreviewFetcher(
    client: OkHttpClient = OkHttpClient(),
    private val maxHtmlBytes: Long = 256 * 1024,
    private val maxImageBytes: Long = 1_500_000,
    /** Test hook: lets tests use a plain-http local server. Production code keeps the https and public-host rules. */
    strict: Boolean = true,
) {
    private val http = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        // No system proxy: through one, the host it resolves is the proxy's, and the public-only check would be moot.
        .apply { if (strict) dns(PublicOnlyDns).proxy(java.net.Proxy.NO_PROXY) }
        .build()
    private val checkHost = strict

    suspend fun fetch(url: String): LinkMetadata? = withContext(Dispatchers.IO) {
        runCatching {
            open(url, "text/html,application/xhtml+xml")?.use { response ->
                val type = response.header("Content-Type").orEmpty()
                if (type.isNotEmpty() && !type.contains("html", ignoreCase = true)) return@use null
                val source = response.body.source()
                source.request(maxHtmlBytes) // reads up to the cap (or less if the page is shorter)
                val size = minOf(source.buffer.size, maxHtmlBytes)
                val charset = response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                parseLinkMetadata(source.buffer.readString(size, charset), response.request.url.toString())
            }
        }.getOrNull()
    }

    /** The preview image's bytes, or null if it is missing, not an image or too large. */
    suspend fun fetchImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            open(url, "image/*")?.use { response ->
                if (response.header("Content-Type")?.startsWith("image/", ignoreCase = true) != true) return@use null
                val source = response.body.source()
                source.request(maxImageBytes + 1)
                if (source.buffer.size > maxImageBytes) null else source.buffer.readByteArray()
            }
        }.getOrNull()
    }

    /** GET [start], following at most [MAX_REDIRECTS] redirects and refusing any hop that is not a public https URL. */
    private fun open(start: String, accept: String): Response? {
        var url: HttpUrl = start.toHttpUrlOrNull() ?: return null
        repeat(MAX_REDIRECTS + 1) {
            if (checkHost && (!url.isHttps || isPrivateLiteral(url.host))) return null
            val response = http.newCall(
                Request.Builder().url(url).header("Accept", accept).header("User-Agent", USER_AGENT).build(),
            ).execute()
            if (response.code in REDIRECT_CODES) {
                val location = response.header("Location")
                response.close()
                url = location?.let { response.request.url.resolve(it) } ?: return null
            } else {
                return if (response.isSuccessful) response else response.also { it.close() }.let { null }
            }
        }
        return null
    }

    private companion object {
        const val MAX_REDIRECTS = 3
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36 NanoVoice"
    }
}
