package dev.nanovoice

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.nanovoice.agent.ServerUrl
import dev.nanovoice.agent.ServerUrlCheck
import dev.nanovoice.ui.Icons
import dev.nanovoice.voice.ModelState
import dev.nanovoice.voice.TtsStatus
import dev.nanovoice.voice.VoiceCatalog

private enum class SettingsPage(val title: Int) {
    Appearance(R.string.cat_appearance),
    Voice(R.string.cat_voice),
    Server(R.string.cat_server),
    Music(R.string.cat_music),
    Photos(R.string.cat_photos),
    Notifications(R.string.cat_notifications),
    Permissions(R.string.cat_permissions),
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
    /** Opens Android's notification access page. */
    onOpenNotificationAccess: () -> Unit = {},
    /** Tries the Navidrome account: null when it works, otherwise the error. */
    onTestMusic: suspend (dev.nanovoice.actions.music.MusicAccount) -> String? = { null },
    /** Trades the pairing code of a scanned QR for the token. */
    onPair: suspend (dev.nanovoice.agent.PairingLinkResult.Valid) -> dev.nanovoice.agent.PairingResult = { dev.nanovoice.agent.PairingResult.Unreachable },
    onBack: () -> Unit,
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
    var pageName by rememberSaveable { mutableStateOf<String?>(null) }
    val page = SettingsPage.entries.firstOrNull { it.name == pageName }

    val urlCheck = if (url.isBlank()) null else ServerUrl.check(url)
    val urlError = (urlCheck as? ServerUrlCheck.Invalid)?.reason

    // Pairing with the panel's QR code: scan, say which server, trade the one-time code for the token.
    val settingsContext = androidx.compose.ui.platform.LocalContext.current
    var pendingPair by remember { mutableStateOf<dev.nanovoice.agent.PairingLinkResult.Valid?>(null) }
    var pairMessage by remember { mutableStateOf<Int?>(null) }
    var pairBusy by remember { mutableStateOf(false) }
    val pairScanner = androidx.activity.compose.rememberLauncherForActivityResult(BarcodeScanActivity.ScanQr()) { text ->
        if (text != null) when (val link = dev.nanovoice.agent.PairingLink.parse(text)) {
            is dev.nanovoice.agent.PairingLinkResult.Valid -> { pairMessage = null; pendingPair = link }
            is dev.nanovoice.agent.PairingLinkResult.NotPairing -> pairMessage = R.string.pair_not_qr
            is dev.nanovoice.agent.PairingLinkResult.UnsupportedVersion -> pairMessage = R.string.pair_unsupported
            is dev.nanovoice.agent.PairingLinkResult.BadServer -> pairMessage = R.string.pair_bad_server
        }
    }
    val cameraPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pairScanner.launch(Unit) else pairMessage = R.string.camera_denied
    }
    fun startPairScan() {
        if (settingsContext.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) pairScanner.launch(Unit)
        else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }

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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.safeDrawingPadding().padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).clickable(onClick = ::leave),
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
                        CategoryRow(R.string.cat_appearance, stringResource(themeLabel(theme))) { pageName = SettingsPage.Appearance.name }
                        CategoryRow(
                            R.string.cat_voice,
                            stringResource(
                                R.string.sum_voice,
                                VoiceCatalog.byId(voiceId)?.displayName ?: "-",
                                stringResource(if (modelState == ModelState.Installed) R.string.sum_installed else R.string.sum_missing),
                            ),
                        ) { pageName = SettingsPage.Voice.name }
                        CategoryRow(R.string.cat_server, statusLabel(status)) { pageName = SettingsPage.Server.name }
                        CategoryRow(
                            R.string.cat_music,
                            stringResource(if (initial.musicAccount() != null) R.string.music_connected else R.string.music_not_configured),
                        ) { pageName = SettingsPage.Music.name }
                        CategoryRow(
                            R.string.cat_photos,
                            stringResource(if (initial.plantNetKey.isNotBlank()) R.string.photos_plantnet_on else R.string.photos_plantnet_off),
                        ) { pageName = SettingsPage.Photos.name }
                        CategoryRow(
                            R.string.cat_notifications,
                            stringResource(if (pushActive) R.string.sum_on else R.string.sum_off),
                        ) { pageName = SettingsPage.Notifications.name }
                        CategoryRow(
                            R.string.cat_permissions,
                            stringResource(if (contactsGranted) R.string.sum_contacts_granted else R.string.sum_contacts_missing),
                        ) { pageName = SettingsPage.Permissions.name }
                    }
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(stringResource(R.string.previews_label), style = MaterialTheme.typography.titleMedium)
                                    Hint(R.string.previews_hint)
                                }
                                Switch(checked = previews, onCheckedChange = { previews = it; commit() })
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Card {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(stringResource(R.string.dynamic_label), style = MaterialTheme.typography.titleMedium)
                                    Hint(R.string.dynamic_hint)
                                }
                                Switch(checked = dynamic, onCheckedChange = { dynamic = it; commit() })
                            }
                        }
                    }
                    SettingsPage.Voice -> {
                        Card {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.speak_replies), modifier = Modifier.weight(1f))
                                Switch(checked = speakReplies, onCheckedChange = { speakReplies = it; commit() })
                            }
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
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(stringResource(R.string.wake_label), style = MaterialTheme.typography.titleMedium)
                                        Hint(R.string.wake_hint)
                                    }
                                    Switch(
                                        checked = wake,
                                        onCheckedChange = { on ->
                                            if (on && !micGranted) onRequestMic() else { wake = on; commit() }
                                        },
                                    )
                                }
                                if (wake && !notificationsAllowed) {
                                    OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.push_allow_notifications))
                                    }
                                }
                            }
                            Card { WakeWordTest(vosk, micGranted, onRequestMic) }
                        }
                    }
                    SettingsPage.Server -> {
                        Card {
                            OutlinedButton(onClick = ::startPairScan, enabled = !pairBusy, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(if (pairBusy) R.string.pair_working else R.string.pair_scan))
                            }
                            pairMessage?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.onSurfaceVariant) } ?: Hint(R.string.pair_hint)
                        }
                        // Which server the code belongs to is shown before anything is replaced: a QR alone does not confirm.
                        pendingPair?.let { link ->
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { pendingPair = null },
                                title = { Text(stringResource(R.string.pair_confirm_title)) },
                                text = { Text(stringResource(R.string.pair_confirm_body, link.server)) },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = {
                                        pendingPair = null
                                        pairBusy = true
                                        pairMessage = null
                                        scope.launch {
                                            val result = onPair(link)
                                            pairBusy = false
                                            when (result) {
                                                is dev.nanovoice.agent.PairingResult.Paired -> { url = link.server; token = result.token; commit(); pairMessage = R.string.pair_done }
                                                is dev.nanovoice.agent.PairingResult.InvalidCode -> pairMessage = R.string.pair_invalid_code
                                                is dev.nanovoice.agent.PairingResult.TooManyAttempts -> pairMessage = R.string.pair_too_many
                                                is dev.nanovoice.agent.PairingResult.Unreachable -> pairMessage = R.string.pair_unreachable
                                                is dev.nanovoice.agent.PairingResult.NeedsVpn -> pairMessage = R.string.pair_needs_vpn
                                            }
                                        }
                                    }) { Text(stringResource(R.string.pair_confirm_yes)) }
                                },
                                dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingPair = null }) { Text(stringResource(R.string.pair_confirm_no)) } },
                            )
                        }
                        Card {
                            Text(statusLabel(status), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Card {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(stringResource(R.string.local_commands), style = MaterialTheme.typography.titleMedium)
                                    Hint(R.string.local_commands_hint)
                                }
                                Switch(checked = localCommands, onCheckedChange = { localCommands = it; commit() })
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(R.string.photos_services_label), style = MaterialTheme.typography.titleSmall)
                                Hint(R.string.photos_services_hint)
                            }
                            Switch(checked = photoServices, onCheckedChange = { photoServices = it; commit() })
                        }
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
                            Text(stringResource(R.string.agent_phone_title), style = MaterialTheme.typography.titleMedium)
                            Hint(R.string.agent_phone_hint)
                            @Composable
                            fun toggle(label: Int, hint: Int, checked: Boolean, set: (Boolean) -> Unit) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(stringResource(label))
                                        Hint(hint)
                                    }
                                    Switch(checked = checked, onCheckedChange = { set(it); commit() })
                                }
                            }
                            toggle(R.string.agent_phone_controls, R.string.agent_phone_controls_hint, phoneControls) { phoneControls = it }
                            toggle(R.string.agent_phone_links, R.string.agent_phone_links_hint, phoneLinks) { phoneLinks = it }
                            toggle(R.string.agent_phone_drafts, R.string.agent_phone_drafts_hint, phoneDrafts) { phoneDrafts = it }
                            toggle(R.string.agent_phone_status, R.string.agent_phone_status_hint, phoneStatus) { phoneStatus = it }
                        }
                        Card {
                            Text(stringResource(R.string.agent_reads_title), style = MaterialTheme.typography.titleMedium)
                            Hint(R.string.agent_reads_hint)
                            @Composable
                            fun toggle(label: Int, hint: Int, checked: Boolean, set: (Boolean) -> Unit) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(stringResource(label))
                                        Hint(hint)
                                    }
                                    Switch(checked = checked, onCheckedChange = { set(it); commit() })
                                }
                            }
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

@Composable
private fun Hint(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CategoryRow(title: Int, summary: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icons.Chevron(MaterialTheme.colorScheme.outline)
        }
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
