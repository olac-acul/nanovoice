package dev.nanovoice.dictation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.nanovoice.R

/** Whether "NanoVoice voce" is among the keyboards turned on in Android. */
internal fun voiceKeyboardEnabled(context: Context): Boolean {
    val ours = ComponentName(context, VoiceInputMethod::class.java)
    return context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.component == ours }
}

/** "Dettatura ovunque" in the settings: what it is, whether it is on, and the way to Android's keyboards to turn it on. */
@Composable
fun DictationEverywhere() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(voiceKeyboardEnabled(context)) }
    // Back from Android's keyboard settings: shown as it is now.
    LifecycleResumeEffect(Unit) {
        enabled = voiceKeyboardEnabled(context)
        onPauseOrDispose {}
    }
    Text(stringResource(R.string.dictation_everywhere), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.dictation_everywhere_body), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(if (enabled) R.string.dictation_everywhere_on else R.string.dictation_everywhere_off),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.dictation_open_keyboards)) }
}
