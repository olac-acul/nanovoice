package dev.nanovoice.agent

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * Validates the NanoClaw server URL typed in settings. Plain `ws://` is only accepted for hosts that
 * are not on the open internet (loopback, private ranges, Tailscale, `.ts.net`, `.local`, bare hostnames),
 * so the bearer token is never sent in clear text across the internet; everything else needs `wss://`.
 */
sealed interface ServerUrlCheck {
    data class Valid(val url: String) : ServerUrlCheck
    data class Invalid(val reason: String) : ServerUrlCheck
}

object ServerUrl {
    fun check(raw: String): ServerUrlCheck {
        val text = raw.trim()
        val uri = runCatching { URI(text) }.getOrNull()
            ?: return ServerUrlCheck.Invalid("Indirizzo non valido.")
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")
            ?: return ServerUrlCheck.Invalid("Indirizzo non valido.")
        // What the connection will actually be built from: anything OkHttp cannot turn into a URL (a port out of range)
        // is refused here instead of failing at every connection.
        val asHttp = text.replaceFirst(Regex("^(?i)ws"), "http")
        if (uri.port > 65535 || asHttp.toHttpUrlOrNull() == null) return ServerUrlCheck.Invalid("Indirizzo non valido.")
        return when (uri.scheme?.lowercase()) {
            "wss" -> ServerUrlCheck.Valid(text)
            "ws" ->
                if (isPrivateHost(host)) ServerUrlCheck.Valid(text)
                else ServerUrlCheck.Invalid("Per un server su internet usa wss://: ws:// è permesso solo in rete locale o VPN.")
            else -> ServerUrlCheck.Invalid("L'indirizzo deve iniziare con ws:// o wss://.")
        }
    }

    /** The token goes into an HTTP header: only printable ASCII, no spaces. */
    fun tokenIsValid(token: String): Boolean = token.isNotEmpty() && token.all { it in '!'..'~' }

    /** A plain ws:// URL: the token and everything said travel unencrypted unless a VPN carries them. */
    fun isCleartext(url: String): Boolean = url.trim().startsWith("ws://", ignoreCase = true)

    internal fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h == "::1" || h.endsWith(".ts.net") || h.endsWith(".local")) return true
        if (!h.contains('.') && !h.contains(':')) return true // bare hostname, e.g. a MagicDNS short name
        val octets = h.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
    }
}
