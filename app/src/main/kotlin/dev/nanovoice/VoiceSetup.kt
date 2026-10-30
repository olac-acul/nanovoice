package dev.nanovoice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nanovoice.voice.ModelState
import dev.nanovoice.voice.ParakeetModels
import dev.nanovoice.voice.VoiceSpec
import dev.nanovoice.voice.VoskModels

private fun mb(bytes: Long) = (bytes / 1_000_000).toInt()

/**
 * The speech recognition model: its state and the button that fits it. There is no delete button: without the model
 * the app cannot listen and there is no alternative engine.
 */
@Composable
fun VoskModelSection(state: ModelState, onDownload: () -> Unit, showActions: Boolean = true) {
    val size = mb(VoskModels.ITALIAN_SMALL.sizeBytes)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            ModelState.NotInstalled -> {
                Text(stringResource(R.string.vosk_not_installed, size))
                if (showActions) Button(onClick = onDownload) { Text(stringResource(R.string.download)) }
            }
            is ModelState.Downloading -> {
                Text(stringResource(R.string.downloading, (state.progress * 100).toInt()))
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
            is ModelState.Unpacking -> {
                Text(stringResource(R.string.unpacking, (state.progress * 100).toInt()))
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
            ModelState.Installed -> {
                Text(stringResource(R.string.vosk_installed))
            }
            is ModelState.Failed -> {
                Text(stringResource(R.string.download_failed, state.message), color = MaterialTheme.colorScheme.error)
                if (showActions) Button(onClick = onDownload) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

/** The optional accurate recognizer: download it, turn it on or off, delete it to free the space. */
@Composable
fun AccurateSttSection(
    state: ModelState,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val size = mb(ParakeetModels.V3_INT8.sizeBytes)
    var details by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.accurate_stt_label), style = MaterialTheme.typography.titleMedium)
                // What it changes for the person first; the engine, its license and its memory on request.
                Text(stringResource(R.string.accurate_stt_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (details) {
                    Text(stringResource(R.string.accurate_stt_details, size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state == ModelState.Installed) Switch(checked = enabled, onCheckedChange = onEnabled)
        }
        TextButton(onClick = { details = !details }) { Text(stringResource(if (details) R.string.details_hide else R.string.details_show)) }
        when (state) {
            ModelState.NotInstalled -> Button(onClick = onDownload) { Text(stringResource(R.string.download)) }
            is ModelState.Downloading -> {
                Text(stringResource(R.string.downloading, (state.progress * 100).toInt()))
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
            is ModelState.Unpacking -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            ModelState.Installed -> TextButton(onClick = onDelete) { Text(stringResource(R.string.accurate_stt_delete, size)) }
            is ModelState.Failed -> {
                Text(stringResource(R.string.download_failed, state.message), color = MaterialTheme.colorScheme.error)
                Button(onClick = onDownload) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

/**
 * One row per voice: pick it (if installed or, in setup, to be downloaded), see its size and license, and
 * download or delete it. [selectUninstalled] lets the first-run screen choose a voice that is not there yet.
 * With a single voice there is nothing to choose and deleting it would leave the app mute, so the selector and
 * the delete button only appear when the catalog has alternatives.
 */
@Composable
fun VoiceList(
    voices: List<VoiceSpec>,
    states: Map<String, ModelState>,
    selectedId: String,
    selectUninstalled: Boolean,
    onSelect: (String) -> Unit,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    showActions: Boolean = true,
    onPreview: ((String) -> Unit)? = null,
) {
    val hasAlternatives = voices.size > 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (voice in voices) {
            val state = states[voice.id] ?: ModelState.NotInstalled
            val installed = state == ModelState.Installed
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasAlternatives) {
                    RadioButton(
                        selected = voice.id == selectedId,
                        onClick = { onSelect(voice.id) },
                        enabled = installed || selectUninstalled,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("${voice.displayName} · ${voice.quality} · ${mb(voice.sizeBytes)} MB")
                    Text(voice.licenseNote, style = MaterialTheme.typography.bodySmall)
                    if (state is ModelState.Downloading) {
                        Text(stringResource(R.string.downloading, (state.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    if (state is ModelState.Unpacking) {
                        Text(stringResource(R.string.unpacking, (state.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    if (state is ModelState.Failed) {
                        Text(stringResource(R.string.download_failed, state.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (showActions) {
                    when (state) {
                        ModelState.Installed -> Column(horizontalAlignment = Alignment.End) {
                            if (onPreview != null) TextButton(onClick = { onPreview(voice.id) }) { Text(stringResource(R.string.listen)) }
                            if (hasAlternatives) TextButton(onClick = { onDelete(voice.id) }) { Text(stringResource(R.string.delete)) }
                        }
                        is ModelState.Downloading, is ModelState.Unpacking -> Unit
                        else -> TextButton(onClick = { onDownload(voice.id) }) { Text(stringResource(R.string.download)) }
                    }
                }
            }
        }
    }
}

/**
 * First run, in two steps. First the assistant: pairing with the panel's QR code (skipped when the app already knows
 * its server, or for later). Then speaking or writing: the voice to download, with how much that is, or text only.
 */
@Composable
fun SetupScreen(
    voices: List<VoiceSpec>,
    voiceStates: Map<String, ModelState>,
    modelState: ModelState,
    initialVoiceId: String,
    paired: Boolean,
    onPair: suspend (dev.nanovoice.agent.PairingLinkResult.Valid) -> dev.nanovoice.agent.PairingResult,
    onPaired: (server: String, token: String) -> Unit,
    onStart: (voiceId: String) -> Unit,
    onFinish: (voiceId: String) -> Unit,
    onSkip: () -> Unit,
) {
    var pairingStep by rememberSaveable { mutableStateOf(!paired) }
    // Paired here: on to the voice.
    LaunchedEffect(paired) { if (paired) pairingStep = false }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (pairingStep) {
                Text(stringResource(R.string.setup_welcome_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.setup_welcome_intro))
                PairingSection(onPair = onPair, onPaired = onPaired, primary = true)
                TextButton(onClick = { pairingStep = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_pair_later)) }
            } else {
                VoiceStep(voices, voiceStates, modelState, initialVoiceId, onStart, onFinish, onSkip)
            }
        }
    }
}

/** The second step: the voice that reads the replies (the recognition model comes with it), or text only. */
@Composable
private fun VoiceStep(
    voices: List<VoiceSpec>,
    voiceStates: Map<String, ModelState>,
    modelState: ModelState,
    initialVoiceId: String,
    onStart: (voiceId: String) -> Unit,
    onFinish: (voiceId: String) -> Unit,
    onSkip: () -> Unit,
) {
    var selected by remember { mutableStateOf(initialVoiceId) }
    var started by remember { mutableStateOf(false) }
    val voiceState = voiceStates[selected] ?: ModelState.NotInstalled
    val allDone = modelState == ModelState.Installed && voiceState == ModelState.Installed
    val failed = modelState is ModelState.Failed || voiceState is ModelState.Failed
    // What the button will download, said before it starts: the recognition model and the chosen voice, if missing.
    val toDownload = (if (modelState == ModelState.Installed) 0L else VoskModels.ITALIAN_SMALL.sizeBytes) +
        (if (voiceState == ModelState.Installed) 0L else voices.firstOrNull { it.id == selected }?.sizeBytes ?: 0L)

    LaunchedEffect(allDone, started) { if (started && allDone) onFinish(selected) }

    Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.setup_intro))

    Text(stringResource(R.string.setup_voice), style = MaterialTheme.typography.titleMedium)
    VoiceList(
        voices = voices,
        states = voiceStates,
        selectedId = selected,
        selectUninstalled = !started,
        onSelect = { selected = it },
        onDownload = {},
        onDelete = {},
        showActions = false,
    )
    Text(stringResource(R.string.setup_recognition), style = MaterialTheme.typography.titleMedium)
    VoskModelSection(modelState, onDownload = {}, showActions = false)

    Button(
        onClick = { started = true; onStart(selected) },
        enabled = !started || failed,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                failed -> stringResource(R.string.retry)
                toDownload > 0 -> stringResource(R.string.setup_download_size, mb(toDownload))
                else -> stringResource(R.string.setup_download)
            },
        )
    }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_skip)) }
}
