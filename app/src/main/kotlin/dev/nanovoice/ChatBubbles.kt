package dev.nanovoice

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nanovoice.agent.AgentImage
import dev.nanovoice.ui.Icons
import dev.nanovoice.ui.LinkPreviewCard
import dev.nanovoice.ui.MessageText
import dev.nanovoice.ui.TypingBubble
import dev.nanovoice.ui.WorkingStatus
import dev.nanovoice.ui.firstUrl
import dev.nanovoice.ui.plainText
import dev.nanovoice.ui.copyText
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A line of the app's own between messages, centred and quieter than a bubble. */
@Composable
private fun NoticeLine(text: String) {
    // The first line is the headline ("Pagina nuova."), the rest says what it means, smaller.
    val headline = text.substringBefore('\n')
    val detail = text.substringAfter('\n', "").ifEmpty { null }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(headline, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** "Ascolta" under the latest reply, or "Riprova" when it is no answer: the long press stays a shortcut. */
@Composable
private fun ReplyActions(onListen: (() -> Unit)?, onRetry: (() -> Unit)?) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = Modifier.padding(start = 4.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (onListen != null) TextButton(onClick = onListen) {
            Icons.Speaker(color, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.reply_listen), color = color)
        }
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.msg_retry), color = color) }
    }
}

/** What the long-press menu of a message can ask of the chat. */
class MessageActions(
    val reply: (ChatMessage) -> Unit,
    val retry: (ChatMessage) -> Unit,
    val readAloud: (ChatMessage) -> Unit,
    val edit: (ChatMessage) -> Unit,
)

/**
 * The conversation, laid out like a messaging app: consecutive messages of one side grouped, a date chip when the day
 * changes, the time in each bubble, a long-press menu, and a button back to the latest message when scrolled up.
 */
@Composable
fun ChatList(
    messages: List<ChatMessage>,
    listState: LazyListState,
    previews: LinkPreviews,
    previewsOn: Boolean,
    showTyping: Boolean,
    workingText: String?,
    actions: MessageActions,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val lastUserId = messages.lastOrNull { it.fromUser }?.id
    // Under the latest reply, once it is complete: "Ascolta", and "Riprova" when it is no answer.
    val lastReply = messages.lastOrNull()?.takeIf { !it.fromUser && !it.notice && !showTyping && workingText == null }
    val lastRequest = messages.lastOrNull { it.fromUser }
    val scope = rememberCoroutineScope()
    // Following the conversation unless the person scrolled up to read: then new text does not pull them down.
    var followLatest by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, more) ->
            if (!more) followLatest = true else if (scrolling) followLatest = false
        }
    }
    LaunchedEffect(messages.size, showTyping, workingText != null) {
        // Every message, one chip per day, and the dots or the status line after them.
        val last = messages.size + dayCount(messages, zone) + (if (showTyping || workingText != null || lastReply != null) 1 else 0) - 1
        if (last >= 0 && (followLatest || messages.lastOrNull()?.fromUser == true)) {
            followLatest = true
            listState.animateScrollToItem(last)
        }
    }
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Room under the header for the first message; it scrolls away with the list.
            contentPadding = PaddingValues(top = 16.dp, bottom = 8.dp),
        ) {
            messages.forEachIndexed { index, message ->
                val previous = messages.getOrNull(index - 1)
                val day = day(message.time, zone)
                if (previous == null || day(previous.time, zone) != day) {
                    item(key = "day-${message.id}") { DayChip(day) }
                }
                if (message.notice) {
                    item(key = message.id) { NoticeLine(message.text) }
                    return@forEachIndexed
                }
                val startsGroup = previous == null || previous.fromUser != message.fromUser || previous.notice ||
                    day(previous.time, zone) != day
                item(key = message.id) {
                    MessageBubble(
                        message,
                        previews,
                        previewsOn,
                        startsGroup = startsGroup,
                        canRetry = message.id == lastUserId,
                        actions = actions,
                        modifier = Modifier.padding(top = if (startsGroup) 10.dp else 3.dp),
                    )
                }
            }
            if (lastReply != null) {
                item(key = "after-${lastReply.id}") {
                    ReplyActions(
                        onListen = if (lastReply.failed) null else { { actions.readAloud(lastReply) } },
                        onRetry = lastRequest?.takeIf { lastReply.failed }?.let { request -> { actions.retry(request) } },
                    )
                }
            }
            if (workingText != null) {
                item(key = "status") { WorkingStatus(workingText, Modifier.padding(horizontal = 4.dp, vertical = 10.dp)) }
            } else if (showTyping) {
                item(key = "typing") { Box(Modifier.padding(top = 10.dp)) { TypingBubble() } }
            }
        }
        val showDown by remember { derivedStateOf { listState.canScrollForward && !followLatest } }
        AnimatedVisibility(
            visible = showDown,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) {
            val label = stringResource(R.string.scroll_to_latest)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                    .semantics { contentDescription = label }
                    .clickable {
                        followLatest = true
                        scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) }
                    },
                contentAlignment = Alignment.Center,
            ) { Icons.Down(MaterialTheme.colorScheme.onSurface) }
        }
    }
}

private fun day(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

private fun dayCount(messages: List<ChatMessage>, zone: ZoneId) = messages.map { day(it.time, zone) }.distinct().size

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)

@Composable
private fun DayChip(day: LocalDate) {
    val today = LocalDate.now()
    val label = when (day) {
        today -> stringResource(R.string.day_today)
        today.minusDays(1) -> stringResource(R.string.day_yesterday)
        else -> day.format(DAY).replaceFirstChar { it.uppercase() }
    }
    Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    previews: LinkPreviews,
    previewsOn: Boolean,
    startsGroup: Boolean,
    canRetry: Boolean,
    actions: MessageActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val user = message.fromUser
    var menu by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.84f).dp
    val time = remember(message.time) { Instant.ofEpochMilli(message.time).atZone(ZoneId.systemDefault()).format(TIME) }
    // Grouped bubbles: on the sender's side the corners are tight between messages of a group and only the first one
    // starts round, so the group reads as one block ending in a "tail" corner.
    val big = 18.dp
    val small = 5.dp
    val sideTop = if (startsGroup) big else small
    val shape = if (user) RoundedCornerShape(topStart = big, topEnd = sideTop, bottomStart = big, bottomEnd = small)
    else RoundedCornerShape(topStart = sideTop, topEnd = big, bottomStart = small, bottomEnd = big)
    val content = if (user) colors.onPrimaryContainer else colors.onSurface
    val metaColor = content.copy(alpha = 0.6f)
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Box {
            Surface(
                shape = shape,
                color = if (user) colors.primaryContainer else colors.surfaceContainer,
                contentColor = content,
                border = if (user) null else BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .clip(shape)
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Column {
                    message.quote?.let { QuoteLine(it, content, Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp)) }
                    message.image?.let { SentPhoto(it, message.photo, onLongPress = { menu = true }) }
                    // With a link preview the time goes under it, as messaging apps do; otherwise at the end of the text.
                    val link = remember(message.text) { if (previewsOn) firstUrl(message.text) else null }
                    if (message.text.isNotEmpty()) {
                        Box {
                            MessageText(
                                message.text,
                                markdown = !user,
                                reserve = if (link == null) time else null,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = if (link == null) 8.dp else 4.dp),
                            )
                            if (link == null) {
                                Text(
                                    time,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = metaColor,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp),
                                )
                            }
                        }
                    }
                    if (link != null) {
                        LinkPreviewCard(link, previews, autoLoad = message.fromUser, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                        Text(
                            time,
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor,
                            modifier = Modifier.align(Alignment.End).padding(end = 10.dp, bottom = 6.dp, top = 2.dp),
                        )
                    } else if (message.text.isEmpty()) {
                        Text(
                            time,
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor,
                            modifier = Modifier.align(Alignment.End).padding(end = 10.dp, bottom = 6.dp),
                        )
                    }
                }
            }
            MessageMenu(message, expanded = menu, canRetry = canRetry, actions = actions, onSelect = { selecting = true }, onDismiss = { menu = false })
        }
    }
    if (selecting) SelectTextDialog(message, onDismiss = { selecting = false })
}

/** The message answered by this one: a coloured bar and its first words, as messaging apps show a reply. */
@Composable
fun QuoteLine(text: String, color: Color, modifier: Modifier = Modifier, label: String? = null) {
    Row(modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        Column {
            label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            Text(text, style = MaterialTheme.typography.bodySmall, color = color.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Above the input while a reply is being written: what it answers, and a cross to drop it. */
@Composable
fun ReplyBar(message: ChatMessage, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val cancel = stringResource(R.string.reply_cancel)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).background(colors.surfaceContainer, RoundedCornerShape(14.dp)).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            QuoteLine(
                remember(message) { quoteOf(message) },
                colors.onSurface,
                label = stringResource(if (message.fromUser) R.string.reply_to_self else R.string.reply_to_agent),
            )
        }
        Box(
            Modifier.size(36.dp).clip(CircleShape).semantics { contentDescription = cancel }.clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) { Icons.Close(colors.onSurfaceVariant) }
    }
}

@Composable
private fun MessageMenu(
    message: ChatMessage,
    expanded: Boolean,
    canRetry: Boolean,
    actions: MessageActions,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val color = MaterialTheme.colorScheme.onSurface
    val shown = remember(message.text, message.fromUser) { if (message.fromUser) message.text else plainText(message.text) }
    val link = remember(message.text) { firstUrl(message.text) }
    val host = remember(link) { link?.let { runCatching { java.net.URI(it).host }.getOrNull() } }
    NanoMenu(expanded = expanded, onDismiss = onDismiss) {
        NanoMenuItem(stringResource(R.string.msg_reply), { Icons.Reply(color) }) { onDismiss(); actions.reply(message) }
        if (shown.isNotEmpty()) {
            NanoMenuItem(stringResource(R.string.msg_copy), { Icons.Copy(color) }) { onDismiss(); copy(context, shown) }
            NanoMenuItem(stringResource(R.string.msg_select), { Icons.Select(color) }) { onDismiss(); onSelect() }
            if (!message.fromUser) {
                NanoMenuItem(stringResource(R.string.msg_read_aloud), { Icons.Speaker(color) }) { onDismiss(); actions.readAloud(message) }
            }
            NanoMenuItem(stringResource(R.string.msg_share), { Icons.Share(color) }) { onDismiss(); share(context, shown) }
        }
        if (link != null && host != null) {
            NanoMenuItem(stringResource(R.string.msg_open_link, host), { Icons.Link(color) }) { onDismiss(); runCatching { uriHandler.openUri(link) } }
            NanoMenuItem(stringResource(R.string.msg_copy_link), { Icons.Copy(color) }) { onDismiss(); copy(context, link) }
        }
        if (message.fromUser && shown.isNotEmpty()) {
            NanoMenuItem(stringResource(R.string.msg_edit), { Icons.Keyboard(color) }) { onDismiss(); actions.edit(message) }
        }
        if (canRetry) NanoMenuItem(stringResource(R.string.msg_retry), { Icons.Retry(color) }) { onDismiss(); actions.retry(message) }
    }
}

/** "Seleziona testo": the message in a dialog where any part of it can be selected and copied. */
@Composable
private fun SelectTextDialog(message: ChatMessage, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msg_select)) },
        text = {
            Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { MessageText(message.text, markdown = !message.fromUser) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.assistant_close)) } },
    )
}

private fun copy(context: Context, text: String) = copyText(context, text)

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** A photo in the chat; a tap shows it on the whole screen (with the reverse image search there), a long press the menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SentPhoto(preview: ImageBitmap, photo: AgentImage?, onLongPress: () -> Unit) {
    val context = LocalContext.current
    var viewing by remember { mutableStateOf(false) }
    Box {
        Image(
            preview,
            contentDescription = stringResource(R.string.photo_sent),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(4.dp)
                .widthIn(max = 260.dp)
                .heightIn(max = 260.dp)
                .clip(RoundedCornerShape(14.dp))
                .combinedClickable(enabled = true, onClick = { viewing = true }, onLongClick = onLongPress),
        )
    }
    if (viewing) {
        PhotoViewer(
            preview,
            full = photo,
            onSearch = photo?.let { { viewing = false; ImageSearchActivity.open(context, it, ImageSearchActivity.Engine.TinEye) } },
            onDismiss = { viewing = false },
        )
    }
}

/** A popup menu drawn like the app's cards: rounded, its colours and outline, roomy items. */
@Composable
internal fun NanoMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant),
        shadowElevation = 6.dp,
        modifier = Modifier.padding(vertical = 2.dp),
        content = content,
    )
}

@Composable
internal fun NanoMenuItem(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = icon,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
    )
}
