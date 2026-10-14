package dev.nanovoice.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nanovoice.LinkPreview
import dev.nanovoice.LinkPreviews
import dev.nanovoice.R

/** The first web address in [text], or null. */
fun firstUrl(text: String): String? = findUrls(text).firstOrNull()?.let { range ->
    val shown = text.substring(range)
    if (shown.startsWith("www.", ignoreCase = true)) "https://$shown" else shown
}

/** A wider picture than this goes full width under the text; a squarer one is a thumbnail beside it. */
private const val WIDE_IMAGE = 1.4f

/**
 * The preview of the first link of a message, drawn inside the bubble as messaging apps do: an accent bar, the site,
 * the title in bold, a few lines of description and the page's picture. Nothing while loading or on failure.
 */
@Composable
fun LinkPreviewCard(url: String, previews: LinkPreviews, modifier: Modifier = Modifier, autoLoad: Boolean = true) {
    // A link the agent wrote is fetched only after a tap: the download itself would tell the site that the phone saw it.
    var wanted by rememberSaveable(url) { mutableStateOf(autoLoad) }
    val host = remember(url) { runCatching { java.net.URI(url).host }.getOrNull().orEmpty().removePrefix("www.") }
    val accent = if (LocalContentColor.current == MaterialTheme.colorScheme.onSurface) MaterialTheme.colorScheme.primary else LocalContentColor.current
    if (!wanted) {
        Row(
            modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { wanted = true }
                .background(accent.copy(alpha = 0.08f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icons.Link(accent, size = 16.dp)
            Text(stringResource(R.string.preview_show, host), style = MaterialTheme.typography.labelLarge, color = accent)
        }
        return
    }
    val preview by produceState<LinkPreview?>(null, url) { value = previews.load(url) }
    val p = preview ?: return
    val uriHandler = LocalUriHandler.current
    val image = p.image
    val wide = image != null && image.width >= image.height * WIDE_IMAGE
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
            .background(accent.copy(alpha = 0.08f))
            .clickable { runCatching { uriHandler.openUri(url) } },
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.weight(1f).padding(start = 9.dp, end = 9.dp, top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // The real host, not the name the page gives itself.
                    Text(
                        if (p.meta.siteName.isBlank() || p.meta.siteName.equals(host, ignoreCase = true)) host else "${p.meta.siteName} · $host",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(p.meta.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    p.meta.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.75f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (image != null && !wide) {
                    Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                }
            }
            if (image != null && wide) {
                Image(
                    image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .aspectRatio(image.width.toFloat() / image.height, matchHeightConstraintsFirst = false)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
        }
    }
}
