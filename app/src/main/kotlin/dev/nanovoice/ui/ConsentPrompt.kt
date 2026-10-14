package dev.nanovoice.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nanovoice.ConsentRequest
import dev.nanovoice.R

/**
 * The agent's question before a change, with Approva and Non approvare. A card, not a dialog: the chat stays usable, so
 * the answer can also be typed or said.
 */
@Composable
fun ConsentCard(request: ConsentRequest, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.consent_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            // Calendar approvals show the actual event and its new values. Keep the buttons reachable on a phone.
            Text(request.headline, style = MaterialTheme.typography.titleMedium)
            request.details?.let { details ->
                key(request.id) {
                    Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()))
                }
            }
            Text(stringResource(R.string.consent_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // A new question appears where the last one was: a double tap must not approve it unread.
            var armed by remember(request.id) { mutableStateOf(false) }
            LaunchedEffect(request.id) {
                delay(ARM_DELAY_MS)
                armed = true
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                OutlinedButton(onClick = { onAnswer(false) }, enabled = armed) { Text(stringResource(R.string.consent_no)) }
                Button(onClick = { onAnswer(true) }, enabled = armed) { Text(stringResource(R.string.consent_yes)) }
            }
        }
    }
}

private const val ARM_DELAY_MS = 700L
