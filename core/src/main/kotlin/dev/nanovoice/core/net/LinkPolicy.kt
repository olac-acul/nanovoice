package dev.nanovoice.core.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A web link as it will be opened: [url] in canonical form and [host], the host that form opens (lower case, an
 * international name as punycode, so letters that only look alike stay apart).
 */
data class WebLink(val url: String, val host: String) {
    /** The host without a leading "www.", to show. */
    val shownHost: String get() = host.removePrefix("www.")

    /** On [domain] itself or one of its subdomains. */
    fun isOn(domain: String): Boolean = host == domain || host.endsWith(".$domain")
}

/**
 * The one rule for web links in the app: links of the agent's actions, of the chat (Markdown or plain addresses), of
 * the previews and of the image search page. A link is read once, by OkHttp's parser, which reads addresses as browsers
 * do, and handed on in that parser's canonical form: whoever opens it next (Android's Uri, the browser) reads the host
 * that was checked and shown, whatever odd characters the agent wrote ("https://evil.example/{x}" is not shown as
 * something else). A link with a user name ("https://bank.example@evil.test", which opens evil.test) is refused: its
 * only use is to deceive.
 */
object LinkPolicy {
    /** [raw] as a web link (https only with [httpsOnly]), or null when it is none or not a safe one. */
    fun webLink(raw: String, httpsOnly: Boolean = false): WebLink? {
        val text = raw.trim()
        val https = text.startsWith("https://", ignoreCase = true)
        if (!https && (httpsOnly || !text.startsWith("http://", ignoreCase = true))) return null
        val url = text.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return WebLink(url.toString(), url.host)
    }

    /** An address as written in a text: "www.example.org" means https. */
    fun webLinkInText(shown: String): WebLink? =
        webLink(if (shown.startsWith("www.", ignoreCase = true)) "https://$shown" else shown)
}
