package dev.nanovoice.linkpreview

import java.net.URI

/** What a page says about itself, enough for a small preview card. */
data class LinkMetadata(
    val url: String,
    val title: String,
    val description: String?,
    val imageUrl: String?,
    val siteName: String,
)

// Possessive quantifiers: no backtracking, so a page full of odd markup is still read in linear time.
private val metaTag = Regex("""<meta\s[^>]{0,2048}+>""", RegexOption.IGNORE_CASE)
private val attribute = Regex("""([a-zA-Z:_-]++)\s*+=\s*+(?:"([^"]*+)"|'([^']*+)'|([^\s"'>]++))""")
private val titleTag = Regex("""<title[^>]{0,256}+>([^<]{0,1000}+)</title>""", RegexOption.IGNORE_CASE)

/**
 * Reads the preview fields from the start of an HTML page: Open Graph first, then Twitter cards, then the plain
 * `<title>` and `description`. Returns null when the page has no usable title.
 */
fun parseLinkMetadata(html: String, pageUrl: String): LinkMetadata? {
    val meta = HashMap<String, String>()
    for (tag in metaTag.findAll(html)) {
        val attrs = attribute.findAll(tag.value).associate { m ->
            m.groupValues[1].lowercase() to (m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } })
        }
        val key = (attrs["property"] ?: attrs["name"])?.lowercase() ?: continue
        val content = attrs["content"] ?: continue
        meta.putIfAbsent(key, content)
    }
    fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { meta[it]?.let(::cleanText)?.takeIf(String::isNotEmpty) }

    val title = pick("og:title", "twitter:title")
        ?: titleTag.find(html)?.groupValues?.get(1)?.let(::cleanText)?.takeIf { it.isNotEmpty() }
        ?: return null
    val host = runCatching { URI(pageUrl).host }.getOrNull()?.removePrefix("www.").orEmpty()
    val image = pick("og:image", "og:image:url", "twitter:image")?.let { resolve(pageUrl, it) }
    return LinkMetadata(
        url = pageUrl,
        title = title.take(MAX_TITLE),
        description = pick("og:description", "twitter:description", "description")?.take(MAX_DESCRIPTION),
        imageUrl = image,
        siteName = pick("og:site_name")?.take(MAX_TITLE) ?: host,
    )
}

private fun resolve(base: String, ref: String): String? = runCatching {
    val absolute = URI(base).resolve(ref.trim())
    if (absolute.scheme.equals("https", ignoreCase = true)) absolute.toString() else null
}.getOrNull()

private fun cleanText(raw: String): String = decodeEntities(raw).replace(Regex("\\s+"), " ").trim()

private val numericEntity = Regex("""&#(x[0-9a-fA-F]+|[0-9]+);""")
private val namedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "hellip" to "…", "ndash" to "–", "mdash" to "—", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“")

internal fun decodeEntities(text: String): String {
    val numeric = numericEntity.replace(text) { m ->
        val body = m.groupValues[1]
        val code = if (body.startsWith("x", ignoreCase = true)) body.drop(1).toIntOrNull(16) else body.toIntOrNull()
        if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
    }
    return Regex("""&([a-zA-Z]+);""").replace(numeric) { m -> namedEntities[m.groupValues[1]] ?: m.value }
}

private const val MAX_TITLE = 160
private const val MAX_DESCRIPTION = 280
