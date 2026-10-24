package dev.nanovoice.assistant

import dev.nanovoice.OrbAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.nanovoice.ui.TypingDots
import dev.nanovoice.ui.WorkingStatus
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nanovoice.ChatViewModel
import dev.nanovoice.R
import dev.nanovoice.ui.ConsentCard
import dev.nanovoice.ui.LinkPreviewCard
import dev.nanovoice.ui.MessageText
import dev.nanovoice.ui.OrbState
import dev.nanovoice.ui.Wordmark
import androidx.compose.ui.unit.sp
import dev.nanovoice.ui.firstUrl
import dev.nanovoice.ui.VoiceOrb
import dev.nanovoice.voice.SttError
import androidx.compose.ui.text.style.TextAlign

@Composable
fun AssistantScreen(
    viewModel: ChatViewModel,
    micGranted: Boolean,
    invocation: Int,
    requestMic: () -> Unit,
    locked: Boolean = false,
    onUnlock: () -> Unit = {},
    onOpenApp: () -> Unit = {},
    onClose: () -> Unit,
) {
    val messages by viewModel.messages.collectAsState()
    val voice by viewModel.voice.state.collectAsState()
    val thinking by viewModel.thinking.collectAsState()
    val working by viewModel.working.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val workingText by viewModel.workingText.collectAsState()
    val speaking by viewModel.speaking.collectAsState()

    // Start listening as soon as the sheet opens (and again each time the assistant is invoked while it is open).
    LaunchedEffect(micGranted, invocation) {
        if (micGranted) viewModel.startListening() else requestMic()
    }

    val lastUser = messages.lastOrNull { it.fromUser }
    val reply = messages.lastOrNull { !it.fromUser && it.id > (lastUser?.id ?: -1) }

    // Tapping outside the sheet closes it.
    Box(
        modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().safeDrawingPadding().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    // The name opens the whole app, with this exchange in its chat.
                    Wordmark(
                        size = 22.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenApp).padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    TextButton(onClick = onClose) { Text(stringResource(R.string.assistant_close)) }
                }
                if (locked) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.assistant_locked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onUnlock) { Text(stringResource(R.string.assistant_unlock)) }
                    }
                }

                if (!micGranted) {
                    Text(stringResource(R.string.assistant_need_mic))
                    Button(onClick = requestMic) { Text(stringResource(R.string.assistant_grant_mic)) }
                } else {
                    val orbState = when {
                        voice.listening -> OrbState.Listening
                        thinking || working -> OrbState.Thinking
                        speaking -> OrbState.Speaking
                        else -> OrbState.Idle
                    }
                    // Tapping the orb does what it does in the chat: listen, finish what is being said (and send it),
                    // or cancel the request and the voice. Finishing an answer to a spoken question no longer cancels it.
                    val orbAction by viewModel.orbAction.collectAsState()
                    val orbLabel = stringResource(
                        when (orbAction) {
                            OrbAction.Listen -> R.string.orb_listen
                            OrbAction.Finish -> R.string.orb_finish
                            OrbAction.Cancel -> R.string.interrupt
                        },
                    )
                    Box(
                        modifier = Modifier
                            .semantics { contentDescription = orbLabel }
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { viewModel.orbTap() },
                    ) { VoiceOrb(orbState, size = 112.dp) }
                    var showTyping by remember { mutableStateOf(false) }
                    LaunchedEffect(thinking) {
                        if (thinking) {
                            delay(500)
                            showTyping = true
                        } else {
                            showTyping = false
                        }
                    }
                    if (working && workingText.isNotEmpty() && !voice.listening && voice.error == null) WorkingStatus(workingText) else if ((showTyping || working) && !voice.listening && voice.error == null) TypingDots() else Text(
                        text = when {
                            voice.error != null -> stringResource(errorText(voice.error!!))
                            voice.listening -> voice.partial.ifEmpty { stringResource(R.string.voice_listening) }
                            speaking -> stringResource(R.string.voice_speaking)
                            else -> stringResource(R.string.assistant_tap_to_speak)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (voice.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (lastUser != null) Text(lastUser.text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    val appSettings by viewModel.appSettings.collectAsState()
                    val consentRequest by viewModel.consentRequest.collectAsState()
                    // Never over the lock screen: the question may show calendar details, and an answer approves a change.
                    consentRequest?.takeIf { !locked }?.let { request ->
                        ConsentCard(request, onAnswer = { viewModel.answerConsent(request.id, it) }, onPick = { viewModel.pickChoice(request.id, it) })
                    }
                    if (reply != null) {
                        MessageText(reply.text, markdown = true, align = TextAlign.Center)
                    }
                    if (appSettings.linkPreviews) reply?.let { r -> firstUrl(r.text)?.let { LinkPreviewCard(it, viewModel.linkPreviews, autoLoad = false) } }
                }
            }
        }
    }
}

private fun errorText(error: SttError): Int = when (error) {
    SttError.NotAvailable -> R.string.voice_error_unavailable
    SttError.PermissionDenied -> R.string.voice_error_permission
    SttError.NoSpeech -> R.string.voice_error_nospeech
    SttError.Busy -> R.string.voice_error_busy
    SttError.Other -> R.string.voice_error_other
}
