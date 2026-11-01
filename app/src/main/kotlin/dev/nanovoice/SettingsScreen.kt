package dev.nanovoice

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.nanovoice.agent.ServerUrl
import dev.nanovoice.agent.ServerUrlCheck
import dev.nanovoice.dictation.DictationEverywhere
import dev.nanovoice.ui.Icons
import dev.nanovoice.voice.ModelState
import dev.nanovoice.voice.TtsStatus
import dev.nanovoice.voice.VoiceCatalog

/** Pages by what the person wants done, not by component: the technical parts sit in "Dettagli avanzati". */
enum class SettingsPage(val title: Int) {
    Replies(R.string.cat_replies),
    Listening(R.string.cat_listening),
    Server(R.string.cat_server),
    Permissions(R.string.cat_permissions),
    Photos(R.string.cat_photos),
    Music(R.string.cat_music),
    Notifications(R.string.cat_notifications),
    Appearance(R.string.cat_appearance),
    Updates(R.string.cat_updates),
}

/** Settings as a list of categories; each opens its own page. Changes are saved when a page is left. */
@Composable
fun SettingsScreen(
    initial: AppSettings,
    status: AgentStatus,
    modelState: ModelState,
    voiceStates: Map<String, ModelState>,
    ttsStatus: TtsStatus,
    onSave: (AppSettings) -> Unit,
    onDownloadModel: () -> Unit,
    /** The accurate recognizer (Parakeet): its download state and actions. */
    accurateState: ModelState = ModelState.NotInstalled,
    onDownloadAccurate: () -> Unit = {},
    onDeleteAccurate: () -> Unit = {},
    onInstallVoice: (String) -> Unit,
    onDeleteVoice: (String) -> Unit,
    onPreviewVoice: (String) -> Unit,
    onOpenAssistantSettings: () -> Unit,
    contactsGranted: Boolean,
    onRequestContacts: () -> Unit,
    pushActive: Boolean,
    pushDistributorAvailable: Boolean,
    notificationsAllowed: Boolean,
    onEnablePush: () -> Unit,
    onDisablePush: () -> Unit,
    onRequestNotifications: () -> Unit,
    vosk: dev.nanovoice.voice.VoskSpeechToText? = null,
    micGranted: Boolean = false,
    onRequestMic: () -> Unit = {},
    /** Asks Android for these runtime permissions (the reads' switches). */
    onRequestPermissions: (Array<String>) -> Unit = {},
    /** Not connected: try to connect again now. */
    onRetryConnection: () -> Unit = {},
    /** Opens the VPN app (or Android's VPN settings). */
    onOpenVpn: () -> Unit = {},
    /** Opens Android's notification access page. */
    onOpenNotificationAccess: () -> Unit = {},
    /** Tries the Navidrome account: null when it works, otherwise the error. */
    onTestMusic: suspend (dev.nanovoice.actions.music.MusicAccount) -> String? = { null },
    /** Trades the pairing code of a scanned QR for the token. */
    onPair: suspend (dev.nanovoice.agent.PairingLinkResult.Valid) -> dev.nanovoice.agent.PairingResult = { dev.nanovoice.agent.PairingResult.Unreachable },
    onBack: () -> Unit,
    /** The page to open on ("Controlla il collegamento" opens the connection); null for the list of categories. */
    startPage: SettingsPage? = null,
    /** The app's own updates from GitHub ("Aggiornamenti"); none in previews. */
    updates: AppUpdates? = null,
    /** "Nuova conversazione": the agent starts over, the messages stay; the chat shows it. */
    onNewConversation: () -> Unit = {},
    /** A request from the help to try: it goes in the chat's field. */
    onTryExample: (String) -> Unit = {},
    /** How many messages the chat holds: nothing to export or delete without any. */
    messageCount: Int = 0,
    /** How many pictures the chat holds: the export offers to include them when there are some. */
    pictureCount: Int = 0,
    /** Some photos of the person are only thumbnails now: said where the pictures are offered. */
    onlyThumbnails: Boolean = false,
    /** "Esporta i messaggi", with the pictures (a zip) or not (JSON): false when it could not be done. */
    onExportMessages: suspend (withPictures: Boolean) -> Boolean = { false },
    /** "Cancella i messaggi", once confirmed: the chat empties. */
    onClearMessages: () -> Unit = {},
) {
    var url by remember { mutableStateOf(initial.serverUrl) }
    var token by remember { mutableStateOf(initial.token) }
    var localCommands by remember { mutableStateOf(initial.localCommands) }
    var speakReplies by remember { mutableStateOf(initial.speakReplies) }
    var voiceId by remember { mutableStateOf(initial.voiceId) }
    var theme by remember { mutableStateOf(initial.themeMode) }
    var dynamic by remember { mutableStateOf(initial.dynamicColors) }
    var previews by remember { mutableStateOf(initial.linkPreviews) }
    var wake by remember { mutableStateOf(initial.wakeWord) }
    var accurate by remember { mutableStateOf(initial.accurateStt) }
    var phoneControls by remember { mutableStateOf(initial.phoneControls) }
    var phoneLinks by remember { mutableStateOf(initial.phoneLinks) }
    var phoneDrafts by remember { mutableStateOf(initial.phoneDrafts) }
    var phoneStatus by remember { mutableStateOf(initial.phoneStatus) }
    var readCalendar by remember { mutableStateOf(initial.readCalendar) }
    var editCalendar by remember { mutableStateOf(initial.editCalendar) }
    var readLocation by remember { mutableStateOf(initial.readLocation) }
    var readNotifications by remember { mutableStateOf(initial.readNotifications) }
    var navidromeUrl by remember { mutableStateOf(initial.navidromeUrl) }
    var navidromeUser by remember { mutableStateOf(initial.navidromeUser) }
    var navidromePassword by remember { mutableStateOf(initial.navidromePassword) }
    var musicTest by remember { mutableStateOf<String?>(null) }
    var plantNetKey by remember { mutableStateOf(initial.plantNetKey) }
    var photoServices by remember { mutableStateOf(initial.photoServices) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // What the screen started from: a field counts as changed here only if it differs from this.
    val baseline = remember { initial }
    // Turned off from its notification while this screen is open: show it.
    LaunchedEffect(initial.wakeWord) { if (initial.wakeWord != baseline.wakeWord) wake = initial.wakeWord }
    var pageName by rememberSaveable { mutableStateOf(startPage?.name) }
    val page = SettingsPage.entries.firstOrNull { it.name == pageName }

    val urlCheck = if (url.isBlank()) null else ServerUrl.check(url)
    val urlError = (urlCheck as? ServerUrlCheck.Invalid)?.reason

    // Pairing with the panel's QR code: scan, say which server, trade the one-time code for the token.
    fun commit() {
        // An invalid address is not saved: the previous one stays until it is fixed. Only what was changed here is
        // written, on top of the settings as they are now.
        val edited = baseline.copy(
            serverUrl = if (urlError == null) url.trim() else baseline.serverUrl,
            token = token.trim(),
            localCommands = localCommands,
            speakReplies = speakReplies,
            voiceId = voiceId,
            themeMode = theme,
            dynamicColors = dynamic,
            linkPreviews = previews,
            wakeWord = wake,
            accurateStt = accurate,
            phoneControls = phoneControls,
            phoneLinks = phoneLinks,
            phoneDrafts = phoneDrafts,
            phoneStatus = phoneStatus,
            readCalendar = readCalendar,
            editCalendar = editCalendar,
            readLocation = readLocation,
            readNotifications = readNotifications,
            navidromeUrl = navidromeUrl.trim(),
            navidromeUser = navidromeUser.trim(),
            navidromePassword = navidromePassword,
            plantNetKey = plantNetKey.trim(),
            photoServices = photoServices,
        )
        onSave(initial.withChanges(baseline, edited))
    }

    fun leave() {
        commit()
        if (page == null) onBack() else pageName = null
    }
    BackHandler(onBack = ::leave)
    val backLabel = stringResource(R.string.back)
    var showHelp by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    /** Back to the chat, the settings saved, to see what was just done there. */
    fun toChat(then: () -> Unit) {
        commit()
        onBack()
        then()
    }
    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false }, onTry = { example ->
            showHelp = false
            toChat { onTryExample(example) }
        })
    }
    if (exporting) {
        var withPictures by remember { mutableStateOf(false) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { exporting = false },
            title = { Text(stringResource(R.string.cat_export)) },
            text = {
                // Asked only when there are pictures: they make it a zip.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .toggleable(withPictures, role = Role.Checkbox) { withPictures = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = withPictures, onCheckedChange = null)
                        Text(
                            pluralStringResource(R.plurals.export_pictures, pictureCount, pictureCount),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    val hint = stringResource(R.string.export_pictures_hint) +
                        if (onlyThumbnails) " " + stringResource(R.string.export_thumbnails) else ""
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exporting = false
                    scope.launch { exportFailed = !onExportMessages(withPictures) }
                }) { Text(stringResource(R.string.export_go)) }
            },
            dismissButton = { TextButton(onClick = { exporting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmClear) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_title)) },
            text = { Text(stringResource(R.string.clear_body)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; toChat(onClearMessages) }) {
                    Text(stringResource(R.string.clear_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.safeDrawingPadding().padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        .semantics { contentDescription = backLabel }.clickable(role = Role.Button, onClick = ::leave),
                    contentAlignment = Alignment.Center,
                ) { Icons.Back(MaterialTheme.colorScheme.onSurface) }
                Text(stringResource(page?.title ?: R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
            }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (page) {
                    null -> {
                        // What is done with the chat: help first, to find it; then starting over, which keeps the messages.
                        CategoryGroup {
                            CategoryRow(R.string.cat_help, stringResource(R.string.sum_help), first = true) { showHelp = true }
                            CategoryRow(R.string.cat_new_chat, stringResource(R.string.sum_new_chat), chevron = false) {
                                toChat(onNewConversation)
                            }
                        }
                        CategoryGroup {
                            CategoryRow(R.string.cat_replies, VoiceCatalog.byId(voiceId)?.displayName ?: "-", first = true) { pageName = SettingsPage.Replies.name }
                            CategoryRow(
                                R.string.cat_listening,
                                stringResource(if (modelState == ModelState.Installed) R.string.sum_listening_ready else R.string.sum_listening_missing),
                            ) { pageName = SettingsPage.Listening.name }
                            CategoryRow(R.string.cat_server, statusLabel(status)) { pageName = SettingsPage.Server.name }
                            CategoryRow(
                                R.string.cat_permissions,
                                stringResource(if (contactsGranted) R.string.sum_contacts_granted else R.string.sum_contacts_missing),
                            ) { pageName = SettingsPage.Permissions.name }
                            CategoryRow(
                                R.string.cat_photos,
                                stringResource(if (photoServices) R.string.sum_photos_on else R.string.sum_photos_off),
                            ) { pageName = SettingsPage.Photos.name }
                            CategoryRow(
                                R.string.cat_music,
                                stringResource(if (initial.musicAccount() != null) R.string.music_connected else R.string.music_not_configured),
                            ) { pageName = SettingsPage.Music.name }
                            CategoryRow(
                                R.string.cat_notifications,
                                stringResource(if (pushActive) R.string.sum_on else R.string.sum_off),
                            ) { pageName = SettingsPage.Notifications.name }
                            CategoryRow(R.string.cat_appearance, stringResource(themeLabel(theme))) { pageName = SettingsPage.Appearance.name }
                            if (updates != null) {
                                val latest by updates.latest.collectAsState()
                                val badge by updates.badge.collectAsState()
                                val newer = latest?.takeIf { isNewer(it.version, updates.installed) }
                                CategoryRow(
                                    R.string.cat_updates,
                                    if (newer != null) stringResource(R.string.sum_update_new, newer.version)
                                    else stringResource(R.string.sum_update_ok, updates.installed),
                                    dot = badge,
                                ) { pageName = SettingsPage.Updates.name }
                            }
                        }
                        // The messages themselves, last: the one that deletes away from the rest, and asked first.
                        CategoryGroup {
                            CategoryRow(
                                R.string.cat_export,
                                stringResource(
                                    when {
                                        exportFailed -> R.string.export_failed
                                        messageCount == 0 -> R.string.sum_export_empty
                                        else -> R.string.sum_export
                                    },
                                ),
                                first = true,
                                chevron = false,
                                enabled = messageCount > 0,
                            ) {
                            if (pictureCount > 0) exporting = true
                            else scope.launch { exportFailed = !onExportMessages(false) }
                        }
                            CategoryRow(
                                R.string.cat_clear,
                                stringResource(R.string.sum_clear),
                                chevron = false,
                                enabled = messageCount > 0,
                                danger = true,
                            ) { confirmClear = true }
                        }
                    }
                    SettingsPage.Updates -> if (updates != null) UpdatesPage(updates)
                    SettingsPage.Appearance -> Card {
                        Text(stringResource(R.string.theme_label), style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (mode in ThemeMode.entries) {
                                FilterChip(
                                    selected = theme == mode,
                                    onClick = { theme = mode; commit() },
                                    label = { Text(stringResource(themeLabel(mode))) },
                                )
                            }
                        }
                        Hint(R.string.theme_hint)
                    }.also {
                        Card {
                            SwitchRow(R.string.previews_label, previews, hint = R.string.previews_hint, style = MaterialTheme.typography.titleMedium) { previews = it; commit() }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Card {
                            SwitchRow(R.string.dynamic_label, dynamic, hint = R.string.dynamic_hint, style = MaterialTheme.typography.titleMedium) { dynamic = it; commit() }
                        }
                    }
                    SettingsPage.Replies -> {
                        Card {
                            SwitchRow(R.string.speak_replies, speakReplies) { speakReplies = it; commit() }
                        }
                        Card {
                            Text(stringResource(R.string.voice_choose), style = MaterialTheme.typography.titleMedium)
                            if (ttsStatus == TtsStatus.NeedsVoice || ttsStatus == TtsStatus.Failed) {
                                Text(stringResource(R.string.tts_unavailable), color = MaterialTheme.colorScheme.error)
                            }
                            VoiceList(
                                voices = VoiceCatalog.all,
                                states = voiceStates,
                                selectedId = voiceId,
                                selectUninstalled = false,
                                onSelect = { voiceId = it; commit() },
                                onDownload = onInstallVoice,
                                onPreview = onPreviewVoice,
                                onDelete = { id ->
                                    onDeleteVoice(id)
                                    if (id == voiceId) voiceId = VoiceCatalog.all.firstOrNull { it.id != id && voiceStates[it.id] == ModelState.Installed }?.id ?: voiceId
                                },
                            )
                        }
                    }
                    SettingsPage.Listening -> {
                        Card {
                            Text(stringResource(R.string.stt_engine), style = MaterialTheme.typography.titleMedium)
                            VoskModelSection(modelState, onDownloadModel)
                        }
                        if (modelState == ModelState.Installed) {
                            Card {
                                AccurateSttSection(
                                    state = accurateState,
                                    enabled = accurate,
                                    onEnabled = { accurate = it; commit() },
                                    onDownload = onDownloadAccurate,
                                    onDelete = onDeleteAccurate,
                                )
                            }
                        }
                        if (vosk != null && modelState == ModelState.Installed) {
                            Card {
                                SwitchRow(R.string.wake_label, wake, hint = R.string.wake_hint, style = MaterialTheme.typography.titleMedium) { on ->
                                    if (on && !micGranted) onRequestMic() else { wake = on; commit() }
                                }
                                if (wake && !notificationsAllowed) {
                                    OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.push_allow_notifications))
                                    }
                                }
                            }
                            Advanced { Card { WakeWordTest(vosk, micGranted, onRequestMic) } }
                        }
                        if (modelState == ModelState.Installed) Card { DictationEverywhere() }
                    }
                    SettingsPage.Server -> {
                        Card {
                            Text(statusLabel(status), style = MaterialTheme.typography.titleMedium)
                            // The server set but not answering: what to try first, before the pairing and the address.
                            if (status == AgentStatus.Disconnected || status == AgentStatus.NeedsVpn) {
                                Hint(if (status == AgentStatus.NeedsVpn) R.string.server_needs_vpn_help else R.string.server_unreachable_help)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (status == AgentStatus.NeedsVpn) {
                                        Button(onClick = onOpenVpn) { Text(stringResource(R.string.banner_open_vpn)) }
                                        OutlinedButton(onClick = onRetryConnection) { Text(stringResource(R.string.server_retry)) }
                                    } else {
                                        Button(onClick = onRetryConnection) { Text(stringResource(R.string.server_retry)) }
                                    }
                                }
                            }
                            PairingSection(onPair = onPair, onPaired = { server, newToken -> url = server; token = newToken; commit() })
                        }
                        // Typing the address and the token is for who set the server up: the QR code does it for everyone else.
                        Advanced {
                        Card {
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                label = { Text(stringResource(R.string.server_url)) },
                                supportingText = { Text(urlError ?: stringResource(R.string.server_url_hint)) },
                                isError = urlError != null,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = token,
                                onValueChange = { token = it },
                                label = { Text(stringResource(R.string.token)) },
                                singleLine = true,
                                isError = token.isNotBlank() && !ServerUrl.tokenIsValid(token.trim()),
                                supportingText = if (token.isNotBlank() && !ServerUrl.tokenIsValid(token.trim())) {
                                    { Text(stringResource(R.string.token_invalid)) }
                                } else null,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        }
                    }
                    SettingsPage.Music -> Card {
                        Text(stringResource(R.string.music_title), style = MaterialTheme.typography.titleMedium)
                        Hint(R.string.music_hint)
                        OutlinedTextField(
                            value = navidromeUrl,
                            onValueChange = { navidromeUrl = it; musicTest = null },
                            label = { Text(stringResource(R.string.music_url)) },
                            singleLine = true,
                            isError = navidromeUrl.isNotBlank() && !navidromeUrl.trim().startsWith("https://"),
                            supportingText = { Text(stringResource(R.string.music_url_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = navidromeUser,
                            onValueChange = { navidromeUser = it; musicTest = null },
                            label = { Text(stringResource(R.string.music_user)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = navidromePassword,
                            onValueChange = { navidromePassword = it; musicTest = null },
                            label = { Text(stringResource(R.string.music_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val testing = stringResource(R.string.music_testing)
                        val ok = stringResource(R.string.music_test_ok)
                        val incomplete = stringResource(R.string.music_incomplete)
                        OutlinedButton(
                            onClick = {
                                val account = initial.copy(navidromeUrl = navidromeUrl.trim(), navidromeUser = navidromeUser.trim(), navidromePassword = navidromePassword).musicAccount()
                                if (account == null) {
                                    musicTest = incomplete
                                } else {
                                    musicTest = testing
                                    commit()
                                    scope.launch { musicTest = onTestMusic(account)?.let { "✗ $it" } ?: ok }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.music_test)) }
                        musicTest?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    SettingsPage.Photos -> Card {
                        Text(stringResource(R.string.photos_title), style = MaterialTheme.typography.titleMedium)
                        Hint(R.string.photos_hint)
                        SwitchRow(R.string.photos_services_label, photoServices, hint = R.string.photos_services_hint, style = MaterialTheme.typography.titleSmall) { photoServices = it; commit() }
                        Advanced {
                            Text(stringResource(R.string.photos_plantnet_title), style = MaterialTheme.typography.titleSmall)
                            Hint(R.string.photos_plantnet_hint)
                            OutlinedTextField(
                                value = plantNetKey,
                                onValueChange = { plantNetKey = it },
                                label = { Text(stringResource(R.string.photos_plantnet_key)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(stringResource(R.string.photos_off_title), style = MaterialTheme.typography.titleSmall)
                        Hint(R.string.photos_off_hint)
                        Text(stringResource(R.string.photos_search_title), style = MaterialTheme.typography.titleSmall)
                        Hint(R.string.photos_search_hint)
                    }
                    SettingsPage.Notifications -> Card {
                        Hint(R.string.push_hint)
                        if (notificationsAllowed) {
                            Text(stringResource(R.string.push_notifications_allowed))
                        } else {
                            OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.push_allow_notifications))
                            }
                        }
                        when {
                            pushActive -> {
                                Text(stringResource(R.string.push_active))
                                OutlinedButton(onClick = onDisablePush, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.push_disable)) }
                            }
                            !pushDistributorAvailable -> Text(stringResource(R.string.push_no_distributor), color = MaterialTheme.colorScheme.error)
                            else -> {
                                Text(stringResource(R.string.push_inactive))
                                OutlinedButton(onClick = onEnablePush, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.push_enable)) }
                            }
                        }
                    }
                    SettingsPage.Permissions -> {
                        Card {
                            SwitchRow(R.string.local_commands, localCommands, hint = R.string.local_commands_hint, style = MaterialTheme.typography.titleMedium) { localCommands = it; commit() }
                        }
                        Card {
                            Text(stringResource(R.string.agent_phone_title), style = MaterialTheme.typography.titleMedium)
                            Hint(R.string.agent_phone_hint)
                            @Composable
                            fun toggle(label: Int, hint: Int, checked: Boolean, set: (Boolean) -> Unit) =
                                SwitchRow(label, checked, hint = hint) { set(it); commit() }
                            toggle(R.string.agent_phone_controls, R.string.agent_phone_controls_hint, phoneControls) { phoneControls = it }
                            toggle(R.string.agent_phone_links, R.string.agent_phone_links_hint, phoneLinks) { phoneLinks = it }
                            toggle(R.string.agent_phone_drafts, R.string.agent_phone_drafts_hint, phoneDrafts) { phoneDrafts = it }
                            toggle(R.string.agent_phone_status, R.string.agent_phone_status_hint, phoneStatus) { phoneStatus = it }
                        }
                        Card {
                            Text(stringResource(R.string.agent_reads_title), style = MaterialTheme.typography.titleMedium)
                            Hint(R.string.agent_reads_hint)
                            @Composable
                            fun toggle(label: Int, hint: Int, checked: Boolean, set: (Boolean) -> Unit) =
                                SwitchRow(label, checked, hint = hint) { set(it); commit() }
                            toggle(R.string.agent_read_calendar, R.string.agent_read_calendar_hint, readCalendar) { on ->
                                readCalendar = on
                                if (!on) editCalendar = false
                                if (on) onRequestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR))
                            }
                            toggle(R.string.agent_edit_calendar, R.string.agent_edit_calendar_hint, editCalendar) { on ->
                                editCalendar = on
                                if (on) {
                                    readCalendar = true
                                    onRequestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR))
                                }
                            }
                            toggle(R.string.agent_read_location, R.string.agent_read_location_hint, readLocation) { on ->
                                readLocation = on
                                if (on) onRequestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                            }
                            toggle(R.string.agent_read_notifications, R.string.agent_read_notifications_hint, readNotifications) { on ->
                                readNotifications = on
                                if (on) onOpenNotificationAccess()
                            }
                        }
                        Card {
                            Text(stringResource(R.string.contacts_section), style = MaterialTheme.typography.titleMedium)
                            Hint(R.string.contacts_hint)
                            if (contactsGranted) {
                                Text(stringResource(R.string.contacts_granted))
                            } else {
                                OutlinedButton(onClick = onRequestContacts, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.contacts_grant)) }
                            }
                        }
                        Card {
                            Text(stringResource(R.string.assistant_section), style = MaterialTheme.typography.titleMedium)
                            Hint(R.string.assistant_hint)
                            OutlinedButton(onClick = onOpenAssistantSettings, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.assistant_open_settings))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.System -> R.string.theme_system
    ThemeMode.Light -> R.string.theme_light
    ThemeMode.Dark -> R.string.theme_dark
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** "Dettagli avanzati": what only who set things up needs (engines, keys, typed addresses, measurements), folded. */
@Composable
private fun Advanced(content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }) {
            Text(stringResource(if (open) R.string.advanced_hide else R.string.advanced_show))
        }
        if (open) content()
    }
}

@Composable
private fun Hint(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A setting turned on or off: the whole row is the control, so a tap anywhere on it switches, and a screen reader says
 * the label, the hint and the state together.
 */
@Composable
private fun SwitchRow(
    title: Int,
    checked: Boolean,
    hint: Int? = null,
    style: TextStyle = LocalTextStyle.current,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = style)
            hint?.let { Hint(it) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * "Aggiornamenti": the version installed and the newest one published, "Scarica e installa" when it is newer (a copy
 * signed with the development key is told why it cannot), what the download is doing, and the daily check.
 */
@Composable
private fun UpdatesPage(updates: AppUpdates) {
    val latest by updates.latest.collectAsState()
    val checkedAt by updates.checkedAt.collectAsState()
    val work by updates.work.collectAsState()
    val daily by updates.daily.collectAsState()
    val uriHandler = LocalUriHandler.current
    val zone = remember { java.time.ZoneId.systemDefault() }
    val today = remember { java.time.LocalDate.now(zone) }
    // Seen: the dot goes until a newer version comes.
    LaunchedEffect(latest) { updates.markSeen() }
    val newer = latest?.takeIf { isNewer(it.version, updates.installed) }
    val busy = work is UpdateWork.Checking || work is UpdateWork.Downloading
    Card {
        Text(stringResource(R.string.update_installed, updates.installed), style = MaterialTheme.typography.titleMedium)
        when {
            newer != null -> {
                val day = runCatching { whenSaid(java.time.Instant.parse(newer.published).toEpochMilli(), today, zone) }.getOrNull()
                Text(
                    if (day != null) stringResource(R.string.update_available_on, newer.version, day) else stringResource(R.string.update_available, newer.version),
                    color = MaterialTheme.colorScheme.primary,
                )
                if (updates.releaseSigned) {
                    Button(onClick = updates::downloadAndInstall, enabled = !busy) { Text(stringResource(R.string.update_install)) }
                } else {
                    Hint(R.string.update_dev_signed)
                }
            }
            latest != null -> Text(stringResource(R.string.update_latest))
        }
        when (val w = work) {
            UpdateWork.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is UpdateWork.Downloading -> {
                LinearProgressIndicator(progress = { w.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.update_downloading, w.percent), style = MaterialTheme.typography.bodySmall)
            }
            UpdateWork.Installing -> Hint(R.string.update_installing)
            UpdateWork.NeedsPermission -> Hint(R.string.update_needs_permission)
            is UpdateWork.Failed -> Text(w.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            UpdateWork.Idle -> Unit
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (checkedAt > 0) stringResource(R.string.update_last_check, whenSaid(checkedAt, today, zone)) else stringResource(R.string.update_never),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = updates::checkNow, enabled = !busy) { Text(stringResource(R.string.update_check_now)) }
        }
        latest?.page?.takeIf { it.startsWith("https://github.com/") }?.let { page ->
            TextButton(onClick = { runCatching { uriHandler.openUri(page) } }) { Text(stringResource(R.string.update_open_page)) }
        }
    }
    Card {
        SwitchRow(R.string.update_daily, daily, hint = R.string.update_daily_hint, style = MaterialTheme.typography.titleMedium) { updates.setDaily(it) }
    }
}

/** When something happened, as a sentence goes on: "oggi alle 13:21", "ieri", "il 28 ott". */
internal fun whenSaid(time: Long, today: java.time.LocalDate, zone: java.time.ZoneId): String {
    val short = alertWhen(time, today, zone)
    return when {
        short.contains(':') -> "oggi alle $short"
        short == "ieri" -> short
        else -> "il $short"
    }
}

/** The settings by goal as one block, rows split by thin lines: a list to pick from, not eight cards. */
@Composable
private fun CategoryGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(content = content) }
}

/**
 * A row of the settings list: a page to open, with the arrow, or an action done at once ([chevron] false). A [danger]
 * one (deleting) has its title in the error color.
 */
@Composable
private fun CategoryRow(
    title: Int,
    summary: String,
    first: Boolean = false,
    dot: Boolean = false,
    chevron: Boolean = true,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    if (!first) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    !enabled -> colors.onSurface.copy(alpha = 0.38f)
                    danger -> colors.error
                    else -> colors.onSurface
                },
            )
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Something new on that page (a newer version of the app), as the bell marks unread alerts.
        if (dot) {
            val label = stringResource(R.string.update_dot)
            Box(Modifier.padding(end = 8.dp).size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape).semantics { contentDescription = label })
        }
        if (chevron) Icons.Chevron(colors.outline)
    }
}

@Composable
fun statusLabel(status: AgentStatus): String = stringResource(
    when (status) {
        AgentStatus.NotConfigured -> R.string.status_not_configured
        AgentStatus.Disconnected -> R.string.status_disconnected
        AgentStatus.Connecting -> R.string.status_connecting
        AgentStatus.Connected -> R.string.status_connected
        AgentStatus.NeedsVpn -> R.string.status_needs_vpn
    },
)
