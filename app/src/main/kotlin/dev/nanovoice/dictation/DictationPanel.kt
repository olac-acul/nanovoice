package dev.nanovoice.dictation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.nanovoice.R
import dev.nanovoice.VoiceUiState
import dev.nanovoice.sttErrorText
import dev.nanovoice.ui.OrbState
import dev.nanovoice.ui.VoiceOrb

/**
 * The one dictation panel: in the app's text field, in place of the keyboard in any other app (the voice keyboard) and
 * for an app's own microphone button. The orb listening, what is understood so far, and two ways out: "Fine" ends the
 * sentence now (a pause does it too), "Annulla" drops it. After a failure, "Riprova". Its [title] says where the words go.
 */
@Composable
fun DictationPanel(
    voice: VoiceUiState,
    /** Where the words go ("Dettatura nella bozza"): never mistaken for a request to the assistant. */
    title: String,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shown instead of listening when dictation cannot start (no permission for the microphone, say). */
    problem: String? = null,
    /** The way out of [problem]: its label and what it does. */
    problemAction: Pair<String, () -> Unit>? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            val failed = problem != null || voice.error != null
            VoiceOrb(
                when {
                    failed -> OrbState.Idle
                    voice.finishing -> OrbState.Thinking
                    voice.listening -> OrbState.Listening
                    else -> OrbState.Idle
                },
                size = 72.dp,
            )
            val caption = when {
                problem != null -> problem
                voice.error != null -> stringResource(sttErrorText(voice.error))
                voice.finishing -> stringResource(R.string.voice_transcribing)
                else -> stringResource(R.string.voice_listening_tap)
            }
            Text(
                caption,
                style = MaterialTheme.typography.titleMedium,
                color = if (failed) colors.error else colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            // The words as they come: a screen reader is not made to read each one.
            if (voice.partial.isNotEmpty() && !failed) {
                Text(voice.partial, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.dictation_cancel)) }
                when {
                    problem != null -> problemAction?.let { (label, action) -> TextButton(onClick = action) { Text(label) } }
                    voice.error != null -> TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    voice.listening && !voice.finishing -> TextButton(onClick = onFinish) { Text(stringResource(R.string.dictation_finish)) }
                }
            }
        }
    }
}
