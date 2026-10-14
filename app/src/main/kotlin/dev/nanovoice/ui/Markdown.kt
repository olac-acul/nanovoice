package dev.nanovoice.ui

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/*
 * The agent writes Markdown. CommonMark (the reference parser, with GitHub's tables, strikethrough and task lists)
 * reads it; this file turns its tree into a small model of our own, which the chat draws (MarkdownView) and the tests
 * check. Links are kept only to the web and to mail, and a label that looks like an address but points elsewhere shows
 * the real host. Raw HTML is shown as the text it is.
 */

sealed interface Inline {
    data class Plain(val text: String) : Inline
    data class Bold(val children: List<Inline>) : Inline
    data class Italic(val children: List<Inline>) : Inline
    data class Strike(val children: List<Inline>) : Inline
    data class CodeSpan(val code: String) : Inline
    /** [url] null: a target that is not a web or mail address, shown as plain text. */
    data class LinkTo(val url: String?, val children: List<Inline>) : Inline
    data object Break : Inline
}

sealed interface Block {
    data class Para(val inlines: List<Inline>) : Block
    data class Title(val level: Int, val inlines: List<Inline>) : Block
    /** [start] is the first number of an ordered list, null for bullets. */
    data class Items(val start: Int?, val items: List<Item>) : Block
    data class Quote(val blocks: List<Block>) : Block
    data class CodeBlock(val language: String, val code: String) : Block
    data class Grid(val header: List<List<Inline>>, val rows: List<List<List<Inline>>>) : Block
    data object Rule : Block
}

/** [checked] is null for a plain item, true or false for a task list item ("- [x]"). */
data class Item(val checked: Boolean?, val blocks: List<Block>)

private val parser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create()))
    .build()

/** Messages are short, but a runaway reply must not freeze the screen. */
private const val MAX_MARKDOWN = 20_000

fun parseBlocks(text: String): List<Block> = blocksOf(parser.parse(text.take(MAX_MARKDOWN)))

private fun children(node: Node): Sequence<Node> = generateSequence(node.firstChild) { it.next }

private fun blocksOf(parent: Node): List<Block> = children(parent).mapNotNull(::block).toList()

private fun block(node: Node): Block? = when (node) {
    is Paragraph -> Block.Para(inlinesOf(node))
    is Heading -> Block.Title(node.level, inlinesOf(node))
    is BulletList -> Block.Items(null, children(node).filterIsInstance<ListItem>().map(::item).toList())
    is OrderedList -> Block.Items(node.markerStartNumber ?: 1, children(node).filterIsInstance<ListItem>().map(::item).toList())
    is BlockQuote -> Block.Quote(blocksOf(node))
    is FencedCodeBlock -> Block.CodeBlock(node.info.orEmpty().trim().substringBefore(' '), node.literal.orEmpty().trimEnd('\n'))
    is IndentedCodeBlock -> Block.CodeBlock("", node.literal.orEmpty().trimEnd('\n'))
    is ThematicBreak -> Block.Rule
    is HtmlBlock -> Block.Para(listOf(Inline.Plain(node.literal.orEmpty().trimEnd('\n'))))
    is TableBlock -> table(node)
    else -> null
}

private fun item(node: ListItem): Item {
    val marker = (node.firstChild as? TaskListItemMarker) ?: (node.firstChild?.firstChild as? TaskListItemMarker)
    val checked = marker?.isChecked
    // The marker is the item's first child (or its paragraph's): dropped, its box is drawn instead.
    marker?.unlink()
    return Item(checked, blocksOf(node))
}

private fun table(node: TableBlock): Block.Grid {
    fun cells(row: Node) = children(row).filterIsInstance<TableCell>().map { inlinesOf(it) }.toList()
    val head = children(node).filterIsInstance<TableHead>().firstOrNull()
    val header = head?.let { h -> children(h).filterIsInstance<TableRow>().firstOrNull()?.let(::cells) }.orEmpty()
    val rows = children(node).filterIsInstance<TableBody>().flatMap { body -> children(body).filterIsInstance<TableRow>().map(::cells) }.toList()
    return Block.Grid(header, rows)
}

private fun inlinesOf(parent: Node): List<Inline> = merge(children(parent).flatMap(::inline).toList())

private fun inline(node: Node): List<Inline> = when (node) {
    is Text -> listOf(Inline.Plain(node.literal))
    is SoftLineBreak -> listOf(Inline.Plain(" "))
    is HardLineBreak -> listOf(Inline.Break)
    is StrongEmphasis -> listOf(Inline.Bold(inlinesOf(node)))
    is Emphasis -> listOf(Inline.Italic(inlinesOf(node)))
    is Strikethrough -> listOf(Inline.Strike(inlinesOf(node)))
    is Code -> listOf(Inline.CodeSpan(node.literal))
    is Link -> {
        val label = inlinesOf(node)
        if (safeLink(node.destination)) {
            val plain = label.plainText()
            val shown = shownLabel(plain, node.destination)
            listOf(Inline.LinkTo(node.destination, if (shown == plain) label else label + Inline.Plain(shown.removePrefix(plain))))
        } else {
            label
        }
    }
    // A picture is not shown inline: its description, tappable when the address is a web one.
    is Image -> {
        val alt = inlinesOf(node).plainText().ifBlank { "immagine" }
        listOf(Inline.LinkTo(node.destination.takeIf(::safeLink), listOf(Inline.Plain("[$alt]"))))
    }
    is HtmlInline -> listOf(Inline.Plain(node.literal))
    else -> inlinesOf(node)
}

/** Neighbouring plain texts joined, so "a", "b" read as one run (and the tests stay readable). */
private fun merge(list: List<Inline>): List<Inline> {
    val out = ArrayList<Inline>(list.size)
    for (i in list) {
        val last = out.lastOrNull()
        if (i is Inline.Plain && last is Inline.Plain) out[out.size - 1] = Inline.Plain(last.text + i.text) else out += i
    }
    return out
}

internal fun List<Inline>.plainText(): String = joinToString("") { i ->
    when (i) {
        is Inline.Plain -> i.text
        is Inline.Bold -> i.children.plainText()
        is Inline.Italic -> i.children.plainText()
        is Inline.Strike -> i.children.plainText()
        is Inline.CodeSpan -> i.code
        is Inline.LinkTo -> i.children.plainText()
        Inline.Break -> "\n"
    }
}

/** The message as plain text, for copying and sharing: no formatting symbols, link addresses kept after their label. */
fun plainText(markdown: String): String = parseBlocks(markdown).plain("").trim()

private fun List<Block>.plain(indent: String): String = joinToString("\n") { it.plain(indent) }

private fun List<Inline>.withAddresses(): String = joinToString("") { i ->
    when (i) {
        is Inline.LinkTo -> {
            val label = i.children.plainText()
            if (i.url == null || i.url == label || label.contains(i.url)) label else "$label (${i.url})"
        }
        is Inline.Bold -> i.children.withAddresses()
        is Inline.Italic -> i.children.withAddresses()
        is Inline.Strike -> i.children.withAddresses()
        else -> listOf(i).plainText()
    }
}

private fun Block.plain(indent: String): String = when (this) {
    is Block.Para -> indent + inlines.withAddresses()
    is Block.Title -> indent + inlines.withAddresses()
    is Block.Items -> items.mapIndexed { n, item ->
        val mark = when {
            item.checked == true -> "☑"
            item.checked == false -> "☐"
            start != null -> "${start + n}."
            else -> "•"
        }
        "$indent$mark " + item.blocks.plain("$indent  ").trimStart()
    }.joinToString("\n")
    is Block.Quote -> blocks.plain("$indent> ")
    is Block.CodeBlock -> code.lines().joinToString("\n") { indent + it }
    is Block.Grid -> (listOf(header) + rows).filter { it.isNotEmpty() }.joinToString("\n") { row -> indent + row.joinToString(" | ") { it.withAddresses() } }
    Block.Rule -> "$indent———"
}

/** Only web and mail links: a file:, intent: or javascript: target is shown as plain text. */
internal fun safeLink(url: String): Boolean =
    url.startsWith("https://", true) || url.startsWith("http://", true) || url.startsWith("mailto:", true)

/** A label that looks like an address but points elsewhere gets the real host next to it. */
internal fun shownLabel(label: String, url: String): String {
    val realHost = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: return label
    val labelHost = Regex("""^(?:https?://)?([\w.-]+\.[a-z]{2,})""", RegexOption.IGNORE_CASE).find(label.trim())
        ?.groupValues?.get(1)?.lowercase()?.removePrefix("www.") ?: return label
    return if (labelHost == realHost || realHost.endsWith(".$labelHost")) label else "$label ($realHost)"
}
