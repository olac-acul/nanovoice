package dev.nanovoice.ui

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * The confirmation of something that cannot be undone (deleting the messages, every alert): a filled button in the
 * danger colour, not a link like "Annulla" next to it, so the two never look alike whatever the colours.
 */
@Composable
fun DangerConfirmButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) { Text(text) }
}

/** An action that deletes ("Elimina", "Elimina il modello"), in the danger colour; [description] for a screen reader. */
@Composable
fun DangerTextButton(text: String, onClick: () -> Unit, description: String? = null, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = if (description != null) modifier.semantics { contentDescription = description } else modifier,
    ) { Text(text, color = MaterialTheme.colorScheme.error) }
}
