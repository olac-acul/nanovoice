package dev.nanovoice.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.nanovoice.R

/**
 * The text of a message: the agent's as Markdown, the person's as typed (with its links tappable). [reserve] keeps room
 * at the end of the last line for the time the bubble draws there, as messaging apps do.
 */
@Composable
fun MessageText(text: String, markdown: Boolean, modifier: Modifier = Modifier, reserve: String? = null, align: TextAlign = TextAlign.Start) {
    if (markdown) {
        MarkdownView(text, modifier, reserve, align)
    } else {
        val style = rememberMdStyle()
        val shown = remember(text, style, reserve) { styled(listOf(Inline.Plain(text)), style, reserve) }
        Text(shown, style = MaterialTheme.typography.bodyLarge, modifier = modifier, textAlign = align)
    }
}

/** What the drawing needs from the theme, gathered once per composition. */
private data class MdStyle(
    val link: Color,
    val codeText: Color,
    val codeBackground: Color,
    val accent: Color,
    val muted: Color,
    val copyCode: (String) -> Unit,
)

@Composable
private fun rememberMdStyle(): MdStyle {
    val colors = MaterialTheme.colorScheme
    val content = LocalContentColor.current
    val context = LocalContext.current
    // On the person's bubble (primary container) the accent is the text colour; on the agent's, the theme's primary.
    val onAgent = content == colors.onSurface
    val accent = if (onAgent) colors.primary else content
    return remember(colors, content) {
        MdStyle(
            link = accent,
            codeText = if (onAgent) colors.tertiary else content,
            codeBackground = content.copy(alpha = 0.08f),
            accent = accent,
            muted = content.copy(alpha = 0.72f),
            copyCode = { copyText(context, it) },
        )
    }
}

@Composable
fun MarkdownView(text: String, modifier: Modifier = Modifier, reserve: String? = null, align: TextAlign = TextAlign.Start) {
    val style = rememberMdStyle()
    val blocks = remember(text) { parseBlocks(text) }
    val horizontal = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = horizontal) {
        blocks.forEachIndexed { index, block ->
            val last = index == blocks.lastIndex
            BlockView(block, style, if (last) reserve else null, align)
        }
        // The time goes over the end of the last line; a code block or a table has no such line, so it gets its own.
        if (reserve != null && blocks.lastOrNull().let { it !is Block.Para && it !is Block.Title && it !is Block.Items }) {
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun BlockView(block: Block, style: MdStyle, reserve: String?, align: TextAlign) {
    val type = MaterialTheme.typography
    when (block) {
        is Block.Para -> InlineText(block.inlines, style, type.bodyLarge, reserve, align)
        is Block.Title -> InlineText(
            block.inlines,
            style,
            when (block.level) {
                1 -> type.titleLarge
                2 -> type.titleMedium.copy(fontSize = 18.sp)
                else -> type.titleSmall.copy(fontSize = 16.sp)
            }.copy(fontWeight = FontWeight.SemiBold),
            reserve,
            align,
            Modifier.padding(top = 2.dp),
        )
        is Block.Items -> ListView(block, style, reserve)
        is Block.Quote -> Row(
            Modifier
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                .background(style.accent.copy(alpha = 0.08f)),
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(style.accent))
            Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.blocks.forEachIndexed { i, b -> BlockView(b, style, if (i == block.blocks.lastIndex) reserve else null, align) }
            }
        }
        is Block.CodeBlock -> CodeBlockView(block, style)
        is Block.Grid -> GridView(block, style)
        Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = LocalContentColor.current.copy(alpha = 0.18f))
    }
}

@Composable
private fun InlineText(
    inlines: List<Inline>,
    style: MdStyle,
    textStyle: TextStyle,
    reserve: String?,
    align: TextAlign,
    modifier: Modifier = Modifier,
) {
    val shown = remember(inlines, style, reserve) { styled(inlines, style, reserve) }
    Text(shown, style = textStyle, modifier = modifier, textAlign = align)
}

/** Bullets and numbers in their own column, so wrapped lines align with the text and not under the marker. */
@Composable
private fun ListView(list: Block.Items, style: MdStyle, reserve: String?) {
    val numbered = list.start != null
    // As wide as the longest number ("10."), so the texts of a long list start at one place.
    val markerWidth = if (numbered) (10 + 8 * (list.start!! + list.items.size - 1).toString().length).dp else 14.dp
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        list.items.forEachIndexed { n, item ->
            Row {
                Box(Modifier.width(markerWidth).padding(end = 4.dp), contentAlignment = Alignment.TopEnd) {
                    when {
                        item.checked != null -> CheckMark(item.checked, style)
                        numbered -> Text("${list.start!! + n}.", style = MaterialTheme.typography.bodyLarge, color = style.muted)
                        else -> Text("•", style = MaterialTheme.typography.bodyLarge, color = style.accent)
                    }
                }
                Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val last = n == list.items.lastIndex
                    item.blocks.forEachIndexed { i, b ->
                        BlockView(b, style, if (last && i == item.blocks.lastIndex) reserve else null, TextAlign.Start)
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckMark(checked: Boolean, style: MdStyle) {
    Box(
        Modifier
            .padding(top = 3.dp)
            .size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) style.accent else Color.Transparent)
            .then(if (checked) Modifier else Modifier.background(style.muted.copy(alpha = 0.18f))),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CodeBlockView(block: Block.CodeBlock, style: MdStyle) {
    val copy = stringResource(R.string.msg_copy)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(style.codeBackground)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(block.language.ifEmpty { "codice" }, style = MaterialTheme.typography.labelSmall, color = style.muted, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(32.dp).clip(CircleShape).semantics { contentDescription = copy }.clickable { style.copyCode(block.code) },
                contentAlignment = Alignment.Center,
            ) { Icons.Copy(style.muted, size = 16.dp) }
        }
        Text(
            block.code,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        )
    }
}

/** A light grid that scrolls sideways when it does not fit the bubble. */
@Composable
private fun GridView(grid: Block.Grid, style: MdStyle) {
    val line = LocalContentColor.current.copy(alpha = 0.14f)
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(line.copy(alpha = 0.05f)).horizontalScroll(rememberScrollState())) {
        Column {
            val rows = listOfNotNull(grid.header.takeIf { it.isNotEmpty() }) + grid.rows
            val columns = rows.maxOfOrNull { it.size } ?: 0
            rows.forEachIndexed { r, row ->
                val head = r == 0 && grid.header.isNotEmpty()
                Row(if (head) Modifier.background(style.accent.copy(alpha = 0.10f)) else Modifier) {
                    for (c in 0 until columns) {
                        val cell = row.getOrNull(c).orEmpty()
                        val shown = remember(cell, style) { styled(cell, style, null) }
                        Text(
                            shown,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (head) FontWeight.SemiBold else null),
                            modifier = Modifier.widthIn(min = 64.dp, max = 220.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                if (r < rows.lastIndex) HorizontalDivider(color = line)
            }
        }
    }
}

/** Inline formatting as styled text; links (also bare addresses in the text) tappable, inline code copied on a tap. */
private fun styled(inlines: List<Inline>, style: MdStyle, reserve: String?): AnnotatedString = buildAnnotatedString {
    val links = TextLinkStyles(SpanStyle(color = style.link, fontWeight = FontWeight.Medium))
    fun AnnotatedString.Builder.plainWithLinks(text: String) {
        var pos = 0
        for (range in findUrls(text)) {
            append(text.substring(pos, range.first))
            val shown = text.substring(range)
            val target = if (shown.startsWith("www.", ignoreCase = true)) "https://$shown" else shown
            withLink(LinkAnnotation.Url(target, links)) { append(shown) }
            pos = range.last + 1
        }
        append(text.substring(pos))
    }
    fun AnnotatedString.Builder.add(list: List<Inline>, inLink: Boolean) {
        for (i in list) when (i) {
            is Inline.Plain -> if (inLink) append(i.text) else plainWithLinks(i.text)
            is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { add(i.children, inLink) }
            is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { add(i.children, inLink) }
            is Inline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { add(i.children, inLink) }
            is Inline.CodeSpan -> withLink(LinkAnnotation.Clickable("code", linkInteractionListener = { style.copyCode(i.code) })) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.92.em, color = style.codeText, background = style.codeBackground)) {
                    append(" ${i.code} ")
                }
            }
            is Inline.LinkTo -> if (i.url == null || inLink) add(i.children, inLink) else withLink(LinkAnnotation.Url(i.url, links)) { add(i.children, true) }
            Inline.Break -> append("\n")
        }
    }
    add(inlines, false)
    if (reserve != null) withStyle(SpanStyle(color = Color.Transparent, fontSize = 12.sp)) { append("  $reserve") }
}

internal fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("NanoVoice", text))
    // From Android 13 the system shows its own confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.msg_copied, Toast.LENGTH_SHORT).show()
}
