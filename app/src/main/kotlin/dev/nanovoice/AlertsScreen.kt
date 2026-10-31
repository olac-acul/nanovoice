package dev.nanovoice

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nanovoice.ui.Icons
import dev.nanovoice.ui.MarkdownView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** When an alert came, as the list says it: the time today, "ieri", otherwise the day. */
internal fun alertWhen(time: Long, today: LocalDate, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(time).atZone(zone)
    return when (at.toLocalDate()) {
        today -> at.format(HOUR)
        today.minusDays(1) -> "ieri"
        else -> at.format(DAY)
    }
}

private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN)
private val FULL = DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm", Locale.ITALIAN)

/**
 * "Avvisi": what the agent said on its own (a scheduled recap, a reminder), apart from the conversation. The list,
 * newest first with the unread ones marked, or the alert [openId] in full: to read, listen to, answer in the chat
 * (quoted, so the agent knows what it is about) or delete.
 */
@Composable
fun AlertsScreen(
    alerts: List<Alert>,
    openId: Long?,
    onOpen: (Long) -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onListen: (Alert) -> Unit,
    onReply: (Alert) -> Unit,
    onDelete: (Long) -> Unit,
    onMarkAllRead: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val open = openId?.let { id -> alerts.firstOrNull { it.id == id } }
    // A lambda, not a reference to a local function: Compose kept the reference from the first composition, with the
    // alert open then, so "back" from the list did nothing.
    val leave = { if (openId != null) onClose() else onBack() }
    BackHandler(onBack = leave)
    val colors = MaterialTheme.colorScheme
    val backLabel = stringResource(R.string.back)
    var menu by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
        Column(modifier = Modifier.safeDrawingPadding().padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(44.dp).background(colors.surfaceContainerHigh, CircleShape)
                        .semantics { contentDescription = backLabel }.clickable(role = Role.Button, onClick = leave),
                    contentAlignment = Alignment.Center,
                ) { Icons.Back(colors.onSurface) }
                Text(stringResource(R.string.alerts_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (openId == null && alerts.isNotEmpty()) {
                    val moreLabel = stringResource(R.string.alerts_more)
                    Box {
                        Box(
                            modifier = Modifier.size(44.dp).semantics { contentDescription = moreLabel }
                                .clickable(role = Role.Button) { menu = true },
                            contentAlignment = Alignment.Center,
                        ) { Text("⋮", style = MaterialTheme.typography.titleLarge, color = colors.onSurface) }
                        NanoMenu(expanded = menu, onDismiss = { menu = false }) {
                            NanoMenuItem(stringResource(R.string.alerts_mark_all_read), { Icons.Select(colors.onSurface) }) {
                                menu = false
                                onMarkAllRead()
                            }
                            NanoMenuItem(stringResource(R.string.alerts_delete_all), { Icons.Close(colors.onSurface) }) {
                                menu = false
                                confirmDeleteAll = true
                            }
                        }
                    }
                }
            }
            when {
                openId != null && open == null -> Text(
                    stringResource(R.string.alerts_gone),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
                open != null -> AlertDetail(open, onListen, onReply, onDelete)
                alerts.isEmpty() -> Text(
                    stringResource(R.string.alerts_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
                else -> AlertList(alerts, onOpen)
            }
        }
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.alerts_delete_all_title)) },
            text = { Text(stringResource(R.string.alerts_delete_all_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDeleteAll = false; onDeleteAll() }) { Text(stringResource(R.string.alerts_delete_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AlertList(alerts: List<Alert>, onOpen: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val unreadLabel = stringResource(R.string.alerts_unread_mark)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        LazyColumn {
            items(alerts.asReversed(), key = { it.id }) { alert ->
                if (alert.id != alerts.last().id) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOpen(alert.id) }
                        .semantics { if (!alert.read) contentDescription = "$unreadLabel: ${alert.title}" }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    // The unread mark keeps its place when there is none, so the titles line up.
                    Box(Modifier.padding(top = 7.dp).size(8.dp).background(if (alert.read) colors.surfaceContainer else colors.primary, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            alert.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (alert.read) FontWeight.Normal else FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val preview = remember(alert.text) { alertPreview(alert.text) }
                        if (preview.isNotEmpty()) {
                            Text(preview, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(alertWhen(alert.time, today, zone), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AlertDetail(alert: Alert, onListen: (Alert) -> Unit, onReply: (Alert) -> Unit, onDelete: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val at = remember(alert.time) {
        Instant.ofEpochMilli(alert.time).atZone(ZoneId.systemDefault()).format(FULL).replaceFirstChar { it.uppercase() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(at, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Surface(
            shape = MaterialTheme.shapes.large,
            color = colors.surfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            MarkdownView(alert.text, Modifier.padding(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onListen(alert) }) {
                Icons.Speaker(colors.primary, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.alerts_listen))
            }
            TextButton(onClick = { onReply(alert) }) {
                Icons.Reply(colors.primary)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.alerts_reply))
            }
            TextButton(onClick = { onDelete(alert.id) }) { Text(stringResource(R.string.alerts_delete), color = colors.error) }
        }
    }
}
