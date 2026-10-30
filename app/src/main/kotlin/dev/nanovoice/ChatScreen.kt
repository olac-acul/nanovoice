package dev.nanovoice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import dev.nanovoice.actions.photo.ProductKind
import dev.nanovoice.agent.AgentImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nanovoice.ui.TypingBubble
import dev.nanovoice.ui.WorkingStatus
import dev.nanovoice.ui.ConsentCard
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nanovoice.ui.Icons
import dev.nanovoice.ui.Wordmark
import dev.nanovoice.ui.LinkPreviewCard
import dev.nanovoice.ui.firstUrl
import dev.nanovoice.dictation.DictationPanel
import dev.nanovoice.ui.OrbState
import dev.nanovoice.ui.VoiceOrb
import dev.nanovoice.ui.extra
import dev.nanovoice.voice.TtsStatus

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    hasMicPermission: () -> Boolean,
    requestMicPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val messages by viewModel.messages.collectAsState()
    val status by viewModel.status.collectAsState()
    val voice by viewModel.voice.state.collectAsState()
    val thinking by viewModel.thinking.collectAsState()
    val working by viewModel.working.collectAsState()
    val speaking by viewModel.speaking.collectAsState()
    val ttsStatus by viewModel.ttsStatus.collectAsState()
    val input by viewModel.draft.collectAsState()
    var showHelp by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    // While typing, the keyboard takes most of the screen: the orb gives way so the conversation keeps its room.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val listState = rememberLazyListState()

    // The dots appear only if the reply takes more than half a second, so instant answers do not flash them.
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(thinking) {
        if (thinking) {
            delay(TYPING_DELAY_MS)
            waited = true
        } else {
            waited = false
        }
    }
    // When the agent says it is working, the dots show at once and stay until its answer arrives.
    val workingText by viewModel.workingText.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()
    val newChatLabel = stringResource(R.string.help_new_chat)
    val helpLabel = stringResource(R.string.help_title)
    val settingsLabel = stringResource(R.string.settings)
    val showStatus = working && workingText.isNotEmpty()
    val showTyping = (waited || working) && !showStatus


    val photo by viewModel.photo.collectAsState()
    val barcode by viewModel.barcode.collectAsState()
    val productQuestion = stringResource(if (barcode?.kind == ProductKind.Book) R.string.barcode_question_book else R.string.barcode_question)

    fun submit() {
        // A scanned code alone still needs a question for the agent, and a bubble that says what was asked.
        viewModel.send(if (input.isBlank() && barcode != null && photo == null) productQuestion else input)
        viewModel.setDraft("")
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun load(uri: Uri, cleanup: () -> Unit = {}) = scope.launch {
        val prepared = withContext(Dispatchers.IO) { Photos.prepare(context.contentResolver, uri).also { cleanup() } }
        if (prepared == null) Toast.makeText(context, R.string.photo_failed, Toast.LENGTH_SHORT).show()
        else viewModel.attachPhoto(prepared)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { load(it) } }
    // The camera app writes the shot into our cache through a FileProvider; it is deleted once read.
    val shot = remember { File(context.cacheDir, "camera/shot.jpg") }
    val shotUri = remember { shot.parentFile?.mkdirs(); FileProvider.getUriForFile(context, "${context.packageName}.photos", shot) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) load(shotUri) { shot.delete() } else shot.delete()
    }
    // The scanner needs the camera permission; once the app declares it, Android asks it for the camera app too.
    var afterCameraGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterCameraGrant?.invoke() else Toast.makeText(context, R.string.camera_denied, Toast.LENGTH_SHORT).show()
        afterCameraGrant = null
    }
    fun withCamera(action: () -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            afterCameraGrant = action
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    val scanner = rememberLauncherForActivityResult(BarcodeScanActivity.Scan()) { scanned -> scanned?.let { viewModel.attachBarcode(it.code, it.kind) } }
    val onTakePhoto = {
        withCamera {
            try {
                camera.launch(shotUri)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.photo_no_camera, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val onPickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val onScanBarcode = { kind: ProductKind ->
        viewModel.chooseProductKind(kind)
        withCamera { scanner.launch(kind) }
    }

    // The text field shows only after a tap on the keyboard button, and goes away with the keyboard when nothing was typed.
    // Back from the settings with a draft: the field is there again, with the orb above it and the keyboard closed.
    val draftOnReturn = remember { input.isNotBlank() }
    var typing by rememberSaveable { mutableStateOf(draftOnReturn) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var imeShown by remember { mutableStateOf(false) }
    // The keyboard was closed with something typed: the field stays, and the orb comes back above it.
    var closedWithText by remember { mutableStateOf(draftOnReturn) }
    // Dictating into the field: the dictation panel stands where the keyboard was, under the field, as the voice keyboard
    // does in other apps. The keyboard comes back with the words, or when the dictation is dropped.
    val dictating by viewModel.dictating.collectAsState()
    LaunchedEffect(keyboardOpen) {
        if (keyboardOpen) {
            imeShown = true
            closedWithText = false
            viewModel.endDictation() // the field tapped to type instead
        } else if (imeShown) {
            imeShown = false
            if (dictating) Unit else if (input.isBlank()) typing = false else closedWithText = true
        }
    }
    fun openKeyboard() {
        focus.requestFocus()
        keyboard?.show()
    }
    // A dictation that ended without words, once it had started: the keyboard comes back. With words, the keyboard opens
    // on the draft through composeRequests.
    var dictationStarted by remember { mutableStateOf(false) }
    LaunchedEffect(dictating, voice.listening, voice.error) {
        when {
            !dictating -> dictationStarted = false
            voice.listening || voice.error != null -> dictationStarted = true
            dictationStarted -> {
                viewModel.endDictation()
                openKeyboard()
            }
        }
    }
    LaunchedEffect(typing) {
        if (typing && !(draftOnReturn && closedWithText)) {
            openKeyboard()
            viewModel.startTyping()
        } else if (!typing) {
            closedWithText = false
        }
    }
    // A message to fix, or text shared from another app, is in the draft: the field opens on it. Only for requests made
    // while this screen shows, not again when it comes back from the settings.
    val composeRequests by viewModel.composeRequests.collectAsState()
    var composeSeen by remember { mutableIntStateOf(composeRequests) }
    LaunchedEffect(composeRequests) {
        if (composeRequests == composeSeen) return@LaunchedEffect
        composeSeen = composeRequests
        closedWithText = false
        if (typing) openKeyboard() else typing = true
    }
    // The orb gives way as soon as the keyboard is asked for, not when it has opened: both move together.
    val showOrb = (!typing || closedWithText) && !dictating
    val orbAction by viewModel.orbAction.collectAsState()

    val orbState = when {
        voice.listening && !voice.finishing -> OrbState.Listening
        voice.finishing || thinking || working -> OrbState.Thinking
        speaking -> OrbState.Speaking
        else -> OrbState.Idle
    }

    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false }, onTry = { example ->
            showHelp = false
            viewModel.putInDraft(example)
        })
    }
    val consentRequest by viewModel.consentRequest.collectAsState()
    val replyingTo by viewModel.replyingTo.collectAsState()
    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text(stringResource(R.string.new_chat_title)) },
            text = { Text(stringResource(R.string.new_chat_body)) },
            confirmButton = { TextButton(onClick = { confirmNew = false; viewModel.newConversation() }) { Text(stringResource(R.string.new_chat_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.safeDrawingPadding().padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Wordmark()
                    StatusLine(status, onCheck = onOpenSettings)
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        .semantics { contentDescription = newChatLabel }
                        .clickable { confirmNew = true },
                    contentAlignment = Alignment.Center,
                ) { Icons.Flame(MaterialTheme.colorScheme.onSurface) }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        .semantics { contentDescription = helpLabel }
                        .clickable { showHelp = true },
                    contentAlignment = Alignment.Center,
                ) { Text("?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface) }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        .semantics { contentDescription = settingsLabel }
                        .clickable(role = Role.Button, onClick = onOpenSettings),
                    contentAlignment = Alignment.Center,
                ) { Icons.Settings(MaterialTheme.colorScheme.onSurface) }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty() && !showStatus && !showTyping) {
                    EmptyHint(modifier = Modifier.align(Alignment.Center))
                } else {
                    ChatList(
                        messages = messages,
                        listState = listState,
                        previews = viewModel.linkPreviews,
                        previewsOn = appSettings.linkPreviews,
                        showTyping = showTyping,
                        workingText = workingText.takeIf { showStatus },
                        actions = remember(viewModel) {
                            MessageActions(
                                reply = viewModel::replyTo,
                                retry = viewModel::retry,
                                readAloud = viewModel::readAloud,
                                edit = viewModel::editAndResend,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (ttsStatus == TtsStatus.NeedsVoice || ttsStatus == TtsStatus.Failed) {
                Text(
                    stringResource(R.string.tts_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            // While the text field is closed, the attachment row has its own send button: the photo or the code can go
            // without a word (the agent describes the photo, or is asked "che prodotto è?").
            val sendAttachment = if (typing) null else ::submit
            replyingTo?.let { ReplyBar(it, onCancel = { viewModel.replyTo(null) }) }
            consentRequest?.let { request ->
                ConsentCard(
                    request,
                    onAnswer = { viewModel.answerConsent(request.id, it) },
                    modifier = Modifier.padding(top = 8.dp),
                    onPick = { viewModel.pickChoice(request.id, it) },
                )
            }
            photo?.let { AttachedPhoto(it.preview, it.detail, onRemove = { viewModel.attachPhoto(null) }, onSend = sendAttachment.takeIf { barcode == null }) }
            barcode?.let {
                AttachedBarcode(
                    it,
                    onRemove = { viewModel.attachBarcode(null) },
                    onSend = sendAttachment,
                    onChange = { code, kind -> viewModel.attachBarcode(code, kind) },
                )
            }
            AnimatedVisibility(
                visible = showOrb,
                enter = fadeIn(tween(TRANSITION_MS)) + expandVertically(tween(TRANSITION_MS), expandFrom = Alignment.Top),
                exit = fadeOut(tween(TRANSITION_MS / 2)) + shrinkVertically(tween(TRANSITION_MS), shrinkTowards = Alignment.Top),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = if (typing) 4.dp else 12.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        VoiceCaption(voice, speaking, orbAction)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            AttachButton(onTakePhoto, onPickPhoto, viewModel::productKinds, onScanBarcode, canScan = appSettings.photoServices)
                            Orb(
                                state = orbState,
                                action = orbAction,
                                onTap = {
                                    if (orbAction == OrbAction.Listen && !hasMicPermission()) requestMicPermission() else viewModel.orbTap()
                                },
                            )
                            // With text kept from before, typing is already on: just bring the keyboard back.
                            RoundButton(stringResource(R.string.keyboard_open), onClick = { if (typing) { closedWithText = false; openKeyboard() } else typing = true }) {
                                Icons.Keyboard(MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = typing,
                enter = fadeIn(tween(TRANSITION_MS)) + expandVertically(tween(TRANSITION_MS), expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(TRANSITION_MS / 2)) + shrinkVertically(tween(TRANSITION_MS), shrinkTowards = Alignment.Bottom),
            ) {
                val busy by viewModel.busy.collectAsState()
                Composer(
                    input,
                    busy,
                    hasPhoto = photo != null,
                    hasBarcode = barcode != null,
                    focus = focus,
                    onChange = viewModel::setDraft,
                    onSend = ::submit,
                    onStop = viewModel::interrupt,
                    // Writing does not hide the other ways in: a photo or a code, and the voice into the same field.
                    attach = {
                        AttachButton(onTakePhoto, onPickPhoto, viewModel::productKinds, onScanBarcode, canScan = appSettings.photoServices, size = 44.dp)
                    },
                    onDictate = {
                        if (!hasMicPermission()) {
                            requestMicPermission()
                        } else {
                            keyboard?.hide()
                            viewModel.dictate()
                        }
                    },
                )
            }
            AnimatedVisibility(
                visible = typing && dictating,
                enter = fadeIn(tween(TRANSITION_MS)) + expandVertically(tween(TRANSITION_MS), expandFrom = Alignment.Top),
                exit = fadeOut(tween(TRANSITION_MS / 2)) + shrinkVertically(tween(TRANSITION_MS), shrinkTowards = Alignment.Top),
            ) {
                DictationPanel(
                    voice,
                    onFinish = viewModel.voice::finish,
                    onCancel = { viewModel.endDictation(); openKeyboard() },
                    onRetry = viewModel::dictate,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/** What can be asked: the local requests work offline, everything else goes to NanoClaw. */
@Composable
private fun HelpDialog(onDismiss: () -> Unit, onTry: (String) -> Unit) {
    val examples = listOf(
        R.string.help_time to R.string.help_time_ex,
        R.string.help_date to R.string.help_date_ex,
        R.string.help_timer to R.string.help_timer_ex,
        R.string.help_alarm to R.string.help_alarm_ex,
        R.string.help_clock to R.string.help_clock_ex,
        R.string.help_apps to R.string.help_apps_ex,
        R.string.help_call to R.string.help_call_ex,
        R.string.help_note to R.string.help_note_ex,
        R.string.help_music to R.string.help_music_ex,
    )
    // Sent to the agent, but started from the app's own buttons.
    val withAgent = listOf(
        R.string.help_photo to R.string.help_photo_ex,
        R.string.help_product to R.string.help_product_ex,
        R.string.help_new_chat to R.string.help_new_chat_ex,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.help_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.help_local_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for ((title, example) in examples) {
                    HelpEntry(stringResource(title), stringResource(example), onTry)
                }
                HelpEntry(stringResource(R.string.help_agent_title), stringResource(R.string.help_agent_body), onTry)
                for ((title, body) in withAgent) {
                    Column {
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.assistant_close)) } },
    )
}

/** A help entry; its first quoted example can be tried: it goes into the field, ready to send or change. */
@Composable
private fun HelpEntry(title: String, body: String, onTry: (String) -> Unit) {
    val example = remember(body) { firstQuoted(body) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (example != null) {
                TextButton(onClick = { onTry(example) }) { Text(stringResource(R.string.help_try)) }
            }
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusLine(status: AgentStatus, onCheck: () -> Unit) {
    val color = when (status) {
        AgentStatus.Connected -> MaterialTheme.extra.success
        AgentStatus.NeedsVpn -> MaterialTheme.extra.warning
        AgentStatus.Connecting -> MaterialTheme.extra.warning
        else -> MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        // Said by a screen reader when it changes: losing the server is news.
        Text(
            statusLabel(status),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    // Not connected: the way to the server settings right where the problem shows, not only behind the settings button.
    if (status == AgentStatus.Disconnected || status == AgentStatus.NeedsVpn || status == AgentStatus.NotConfigured) {
        Text(
            stringResource(R.string.status_check),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onCheck).padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VoiceCaption(voice: VoiceUiState, speaking: Boolean, action: OrbAction) {
    // Each step says what is going on: listening, working out the words, waiting for the answer, reading it out.
    val text = when {
        voice.error != null && action == OrbAction.Listen -> stringResource(sttErrorText(voice.error))
        action == OrbAction.Finish -> voice.partial.ifEmpty { stringResource(R.string.voice_listening_tap) }
        voice.listening && voice.finishing -> stringResource(R.string.voice_transcribing)
        action == OrbAction.Cancel && speaking -> stringResource(R.string.voice_speaking_tap)
        action == OrbAction.Cancel -> stringResource(R.string.voice_waiting_tap)
        else -> stringResource(R.string.voice_hold)
    }
    // A screen reader says each new step by itself, but not every word of the live transcript.
    val announce = !(action == OrbAction.Finish && voice.partial.isNotEmpty())
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (voice.error != null && action == OrbAction.Listen) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp).semantics { if (announce) liveRegion = LiveRegionMode.Polite },
    )
}

/** Tap to talk, tap to stop; while the request is answered, a tap cancels it (the stop mark says so). */
@Composable
private fun Orb(state: OrbState, action: OrbAction, onTap: () -> Unit) {
    val label = stringResource(
        when (action) {
            OrbAction.Listen -> R.string.orb_listen
            OrbAction.Finish -> R.string.orb_finish
            OrbAction.Cancel -> R.string.interrupt
        },
    )
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .size(112.dp)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        VoiceOrb(state, size = 112.dp)
        if (action == OrbAction.Cancel) {
            Box(Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.28f), CircleShape), contentAlignment = Alignment.Center) {
                Icons.Stop(Color.White, size = 20.dp)
            }
        }
    }
}

@Composable
private fun RoundButton(label: String, onClick: () -> Unit, size: Dp = 52.dp, icon: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/**
 * Left of the orb: take a photo, pick one or scan a product barcode, for the next message. Scanning first asks what
 * kind of product it is, so the phone asks the one database for that kind (the kind chosen last comes first).
 */
@Composable
private fun AttachButton(
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    productKinds: () -> List<ProductKind>,
    onScanBarcode: (ProductKind) -> Unit,
    /** Off with "Servizi esterni per le foto": no database would get the code, so the agent could not look it up. */
    canScan: Boolean = true,
    size: Dp = 52.dp,
) {
    var menu by remember { mutableStateOf(false) }
    var choosingKind by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.onSurface
    val close = { menu = false; choosingKind = false }
    Box {
        RoundButton(stringResource(R.string.photo_attach), onClick = { menu = true }, size = size) { Icons.Camera(color) }
        NanoMenu(expanded = menu, onDismiss = close) {
            if (choosingKind) {
                Text(
                    stringResource(R.string.product_kind_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
                for (kind in productKinds()) {
                    NanoMenuItem(stringResource(kind.label), { Icons.Barcode(color, size = 20.dp) }) { close(); onScanBarcode(kind) }
                }
            } else {
                NanoMenuItem(stringResource(R.string.photo_take), { Icons.Camera(color, size = 20.dp) }) { close(); onTakePhoto() }
                NanoMenuItem(stringResource(R.string.photo_pick), { Icons.Gallery(color, size = 20.dp) }) { close(); onPickPhoto() }
                if (canScan) NanoMenuItem(stringResource(R.string.photo_scan_barcode), { Icons.Barcode(color, size = 20.dp) }) { choosingKind = true }
            }
        }
    }
}

@Composable
private fun AttachmentSend(onSend: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.send)
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(colors.primary, CircleShape)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onSend),
        contentAlignment = Alignment.Center,
    ) { Icons.Send(colors.onPrimary) }
}

/** The scanned barcode waiting to go with the next message, with the kind of product it will be looked up as. */
@Composable
private fun AttachedBarcode(
    scanned: ScannedProduct,
    onRemove: () -> Unit,
    onSend: (() -> Unit)?,
    /** A misread number or a wrong kind is fixed here, before anything is sent. */
    onChange: (code: String, kind: ProductKind) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val removeLabel = stringResource(R.string.barcode_remove)
    val changeLabel = stringResource(R.string.barcode_change)
    var menu by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    if (typing) {
        BarcodeNumberDialog(initial = scanned.code, onDone = { typing = false; onChange(it, scanned.kind) }, onDismiss = { typing = false })
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = changeLabel, role = Role.Button) { menu = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(56.dp).background(colors.surfaceContainerHigh, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icons.Barcode(colors.onSurface, size = 28.dp)
        }
        val text = when {
            scanned.notIsbn -> stringResource(R.string.barcode_attached_not_isbn, scanned.code)
            scanned.kind == ProductKind.Book -> stringResource(R.string.barcode_attached_book, scanned.code)
            else -> stringResource(R.string.barcode_attached, scanned.code, stringResource(scanned.kind.label))
        }
        Box(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            NanoMenu(expanded = menu, onDismiss = { menu = false }) {
                for (kind in ProductKind.entries) {
                    NanoMenuItem(stringResource(kind.label), { Icons.Barcode(colors.onSurface, size = 20.dp) }) {
                        menu = false
                        onChange(scanned.code, kind)
                    }
                }
                NanoMenuItem(stringResource(R.string.scan_type_number), { Icons.Keyboard(colors.onSurface, size = 20.dp) }) {
                    menu = false
                    typing = true
                }
            }
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(colors.surfaceContainerHigh, CircleShape)
                .semantics { contentDescription = removeLabel }
                .clickable(role = Role.Button, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icons.Close(colors.onSurface) }
        onSend?.let { AttachmentSend(it) }
    }
}

/** The photo waiting to go with the next message. */
@Composable
private fun AttachedPhoto(preview: ImageBitmap, full: AgentImage, onRemove: () -> Unit, onSend: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val removeLabel = stringResource(R.string.photo_remove)
    val viewLabel = stringResource(R.string.photo_view)
    var viewing by remember { mutableStateOf(false) }
    if (viewing) PhotoViewer(preview, full, onSearch = null, onDismiss = { viewing = false })
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            preview,
            contentDescription = viewLabel,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { viewing = true },
        )
        Text(stringResource(R.string.photo_attached), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(colors.surfaceContainerHigh, CircleShape)
                .semantics { contentDescription = removeLabel }
                .clickable(role = Role.Button, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icons.Close(colors.onSurface) }
        onSend?.let { AttachmentSend(it) }
    }
}

@Composable
private fun Composer(
    value: String,
    busy: Boolean,
    hasPhoto: Boolean,
    hasBarcode: Boolean = false,
    focus: FocusRequester,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    attach: @Composable () -> Unit,
    onDictate: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stopLabel = stringResource(R.string.interrupt)
    val sendLabel = stringResource(R.string.send)
    val dictateLabel = stringResource(R.string.dictate)
    // The cursor too: text put in from outside (a shared link, a message to fix) leaves it at the end, ready for more.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attach()
        TextField(
            value = field,
            onValueChange = {
                field = it
                if (it.text != value) onChange(it.text)
            },
            modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text(stringResource(if (hasPhoto) R.string.input_hint_photo else R.string.input_hint)) },
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceContainer,
                unfocusedContainerColor = colors.surfaceContainer,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            maxLines = 4,
        )
        val enabled = value.isNotBlank() || hasPhoto || hasBarcode // a scanned code alone asks "che prodotto è?"
        // While a request is being answered and nothing new is typed, the button interrupts it; with nothing to send it
        // is the microphone, into this field.
        val stop = busy && !enabled
        val dictate = !enabled && !stop
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(if (dictate) colors.surfaceContainerHigh else colors.primary, CircleShape)
                .semantics { contentDescription = if (stop) stopLabel else if (dictate) dictateLabel else sendLabel }
                .clickable(role = Role.Button, onClick = if (stop) onStop else if (dictate) onDictate else onSend),
            contentAlignment = Alignment.Center,
        ) {
            when {
                stop -> Icons.Stop(colors.onPrimary)
                dictate -> Icons.Mic(colors.onSurface)
                else -> Icons.Send(colors.onPrimary)
            }
        }
    }
}

private const val TYPING_DELAY_MS = 500L

/** Orb and text field swap in about the time the keyboard takes to slide. */
private const val TRANSITION_MS = 250
