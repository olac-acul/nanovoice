package dev.nanovoice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nanovoice.voice.SttError
import dev.nanovoice.voice.VoskSpeechToText
import dev.nanovoice.voice.WakeEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A trial of the wake phrase with the screen on: counts how often the wake word is heard and how much CPU the listening
 * costs, so we know whether an always-on listener is worth building.
 */
@Composable
fun WakeWordTest(vosk: VoskSpeechToText, micGranted: Boolean, onRequestMic: () -> Unit) {
    var running by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var audioSeconds by remember { mutableStateOf(0.0) }
    var cpuMs by remember { mutableStateOf(0L) }
    var voicedSeconds by remember { mutableStateOf(0.0) }
    var lastUnderstood by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<SttError?>(null) }
    val heard = remember { mutableStateListOf<String>() }
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.ITALIAN) }

    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        listening = false
        error = null
        vosk.listenForWakePhrase().collect { event ->
            when (event) {
                WakeEvent.Listening -> listening = true
                is WakeEvent.Understood -> lastUnderstood = event.text
                is WakeEvent.Heard -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    heard.add(0, "${clock.format(Date())} · ${event.match.phrase} · ${"%.2f".format(Locale.ITALIAN, event.match.confidence)}")
                }
                is WakeEvent.Usage -> { audioSeconds = event.audioSeconds; voicedSeconds = event.voicedSeconds; cpuMs = event.cpuMs }
                is WakeEvent.Failed -> { error = event.error; running = false }
            }
        }
        running = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.wake_test_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.wake_test_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            !micGranted -> OutlinedButton(onClick = onRequestMic, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.assistant_grant_mic)) }
            running -> OutlinedButton(onClick = { running = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.wake_test_stop)) }
            else -> Button(onClick = { heard.clear(); audioSeconds = 0.0; voicedSeconds = 0.0; cpuMs = 0; lastUnderstood = ""; running = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.wake_test_start))
            }
        }
        error?.let { Text(stringResource(R.string.wake_test_failed, it.name), color = MaterialTheme.colorScheme.error) }
        if (running || audioSeconds > 0) {
            val minutes = (audioSeconds / 60).toInt()
            val seconds = (audioSeconds % 60).toInt()
            val cpu = if (audioSeconds > 0) cpuMs / (audioSeconds * 10.0) else 0.0
            val voiced = if (audioSeconds > 0) voicedSeconds * 100 / audioSeconds else 0.0
            Text(
                stringResource(
                    if (running && listening) R.string.wake_test_status_running else R.string.wake_test_status_done,
                    minutes, seconds, heard.size, "%.1f".format(Locale.ITALIAN, cpu),
                ),
            )
            Text(
                stringResource(R.string.wake_test_voiced, "%.0f".format(Locale.ITALIAN, voiced)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lastUnderstood.isNotEmpty()) {
                Text(stringResource(R.string.wake_test_understood, lastUnderstood), style = MaterialTheme.typography.bodySmall)
            }
            for (line in heard.take(20)) Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }
}
