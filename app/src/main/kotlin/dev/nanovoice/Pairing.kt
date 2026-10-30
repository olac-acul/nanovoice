package dev.nanovoice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nanovoice.agent.PairingLink
import dev.nanovoice.agent.PairingLinkResult
import dev.nanovoice.agent.PairingResult
import kotlinx.coroutines.launch

/**
 * Pairing with the panel's QR code: the button, the camera permission, the scanner, the server named for a yes before
 * anything is replaced, and what happened. Used by the first run and by the settings; [onPaired] gets the server and
 * the token to save. [primary] draws the button filled, as the main step of a screen.
 */
@Composable
fun PairingSection(
    onPair: suspend (PairingLinkResult.Valid) -> PairingResult,
    onPaired: (server: String, token: String) -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PairingLinkResult.Valid?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(BarcodeScanActivity.ScanQr()) { text ->
        if (text != null) when (val link = PairingLink.parse(text)) {
            is PairingLinkResult.Valid -> { message = null; pending = link }
            is PairingLinkResult.NotPairing -> message = R.string.pair_not_qr
            is PairingLinkResult.UnsupportedVersion -> message = R.string.pair_unsupported
            is PairingLinkResult.BadServer -> message = R.string.pair_bad_server
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanner.launch(Unit) else message = R.string.camera_denied
    }
    fun scan() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanner.launch(Unit)
        else camera.launch(Manifest.permission.CAMERA)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val label = stringResource(if (busy) R.string.pair_working else R.string.pair_scan)
        if (primary) {
            Button(onClick = ::scan, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
        } else {
            OutlinedButton(onClick = ::scan, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }
        val shown = message
        if (shown != null) {
            Text(stringResource(shown), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.pair_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    // Which server the code belongs to is shown before anything is replaced: a QR alone does not confirm.
    pending?.let { link ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.pair_confirm_title)) },
            text = { Text(stringResource(R.string.pair_confirm_body, link.server)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    busy = true
                    message = null
                    scope.launch {
                        val result = onPair(link)
                        busy = false
                        message = when (result) {
                            is PairingResult.Paired -> { onPaired(link.server, result.token); R.string.pair_done }
                            is PairingResult.InvalidCode -> R.string.pair_invalid_code
                            is PairingResult.TooManyAttempts -> R.string.pair_too_many
                            is PairingResult.Unreachable -> R.string.pair_unreachable
                            is PairingResult.NeedsVpn -> R.string.pair_needs_vpn
                        }
                    }
                }) { Text(stringResource(R.string.pair_confirm_yes)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.pair_confirm_no)) } },
        )
    }
}
