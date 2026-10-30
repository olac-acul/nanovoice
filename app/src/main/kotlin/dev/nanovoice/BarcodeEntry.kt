package dev.nanovoice

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType

/**
 * The digits under the bars, typed by hand: spaces and dashes as printed are fine. A product code has 8 to 14 digits;
 * a 12-digit UPC-A becomes the 13 digits the databases file it under. Null when it cannot be one.
 */
internal fun typedBarcode(text: String): String? {
    val digits = text.filterNot { it == ' ' || it == '-' }
    if (digits.length !in 8..14 || !digits.all { it in '0'..'9' }) return null
    return Barcodes.normalised(digits)
}

/** Typing the number of a code the camera cannot read (worn, curved, too small), or fixing a misread one. */
@Composable
internal fun BarcodeNumberDialog(initial: String = "", onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val code = typedBarcode(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.barcode_number_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(20) },
                singleLine = true,
                label = { Text(stringResource(R.string.barcode_number_hint)) },
                isError = text.isNotBlank() && code == null,
                supportingText = if (text.isNotBlank() && code == null) {
                    { Text(stringResource(R.string.barcode_number_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { code?.let(onDone) }, enabled = code != null) { Text(stringResource(R.string.barcode_number_use)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.assistant_close)) } },
    )
}
