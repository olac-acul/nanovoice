package dev.nanovoice.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

private val urlPattern = Regex("""(?:https?://|www\.)[^\s<>"]+""")

/** Characters that end a sentence, not an address: "see https://x.org/a." links to /a. */
private const val TRAILING = ".,;:!?)]}'”’"

/** Where the web addresses are in [text], without the punctuation that follows them. */
internal fun findUrls(text: String): List<IntRange> = urlPattern.findAll(text).mapNotNull { match ->
    var end = match.range.last
    while (end > match.range.first && text[end] in TRAILING) end--
    val url = text.substring(match.range.first, end + 1)
    if (url.length > "https://".length) match.range.first..end else null
}.toList()

/** [text] with its web addresses turned into tappable links. */
fun linkified(text: String, linkColor: Color): AnnotatedString {
    val urls = findUrls(text)
    if (urls.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var pos = 0
        for (range in urls) {
            append(text.substring(pos, range.first))
            val shown = text.substring(range)
            val target = if (shown.startsWith("www.", ignoreCase = true)) "https://$shown" else shown
            withLink(LinkAnnotation.Url(target, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) { append(shown) }
            pos = range.last + 1
        }
        append(text.substring(pos))
    }
}
