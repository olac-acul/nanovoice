package dev.nanovoice.dictation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.nanovoice.NanoVoiceApp
import dev.nanovoice.R
import dev.nanovoice.VoiceController
import dev.nanovoice.ui.NanoTheme

/**
 * The microphone button of another app ("parla", search by voice) when it asks Android for speech as text: the same
 * dictation panel over that app, and the sentence handed back to it.
 */
class DictationActivity : ComponentActivity() {
    private val container get() = (application as NanoVoiceApp).container
    private val voice by lazy { VoiceController(lifecycleScope, { container.stt }, container.spokenOutput, ::done) }

    private var micMissing by mutableStateOf(false)

    private val micRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micMissing = !granted
        if (granted) voice.listen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container.vosk.preload()
        setContent {
            val settings by container.settingsStore.settings.collectAsState()
            val state by voice.state.collectAsState()
            NanoTheme(settings.themeMode, settings.dynamicColors) {
                Column(Modifier.fillMaxSize()) {
                    // A tap above the panel drops the dictation, as for any sheet.
                    Spacer(Modifier.fillMaxWidth().weight(1f).clickable(remember { MutableInteractionSource() }, indication = null, onClick = ::cancel))
                    DictationPanel(
                        state,
                        title = stringResource(R.string.dictation_title_keyboard),
                        onFinish = voice::finish,
                        onCancel = ::cancel,
                        onRetry = voice::listen,
                        problem = if (micMissing) stringResource(R.string.dictation_no_mic) else null,
                        problemAction = (stringResource(R.string.dictation_allow_mic) to { micRequest.launch(Manifest.permission.RECORD_AUDIO) })
                            .takeIf { micMissing },
                        modifier = Modifier.navigationBarsPadding().padding(12.dp),
                    )
                }
            }
        }
        if (savedInstanceState == null) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.listen()
            else micRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        super.onStop()
        // Gone from the screen (home, another app): the microphone closes and the asking app gets nothing.
        if (!isChangingConfigurations && !isFinishing) cancel()
    }

    private fun done(text: String) {
        val words = text.trim()
        if (words.isEmpty()) return cancel()
        setResult(
            RESULT_OK,
            Intent()
                .putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(words))
                .putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(1f)),
        )
        finish()
    }

    private fun cancel() {
        voice.cancel()
        setResult(RESULT_CANCELED)
        finish()
    }
}
