package dev.nanovoice.dictation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.nanovoice.NanoVoiceApp
import dev.nanovoice.R
import dev.nanovoice.VoiceController
import dev.nanovoice.ui.NanoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** What is typed for [said] after [before] (the character before the cursor): apart from the word before, capitalised at a sentence start. */
internal fun dictatedText(said: String, before: CharSequence?, capital: Boolean): String {
    val words = said.trim()
    val space = if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " " else ""
    return space + if (capital) words.replaceFirstChar { it.titlecase() } else words
}

/**
 * "NanoVoice voce": a keyboard that only listens. A keyboard's microphone key (HeliBoard's, say) switches to it; it
 * listens at once, types the sentence into the field the person was in, and goes back to the keyboard of before.
 * "Annulla" goes back without typing. Offline, through the same recognizer as the app.
 */
class VoiceInputMethod : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as NanoVoiceApp).container
    private lateinit var voice: VoiceController

    /** The app has no permission for the microphone: the panel says so instead of listening. */
    private var micMissing by mutableStateOf(false)

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        voice = VoiceController(scope, { container.stt }, container.spokenOutput, ::type)
    }

    /** The height of Android's bar under the keyboard (hide and switch keyboard buttons), in pixels. */
    private var navigationBar by mutableIntStateOf(0)

    override fun onCreateInputView(): View {
        // Compose finds its lifecycle on the window's views; a keyboard's window has none of its own.
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
            // The keyboard's window lies under that bar, and its insets do not reach Compose: read them on the window.
            ViewCompat.setOnApplyWindowInsetsListener(it) { view, insets ->
                navigationBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                Log.d(TAG, "navigation bar under the keyboard: $navigationBar px")
                ViewCompat.onApplyWindowInsets(view, insets)
            }
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@VoiceInputMethod)
            setViewTreeSavedStateRegistryOwner(this@VoiceInputMethod)
            setContent {
                val settings by container.settingsStore.settings.collectAsState()
                val state by voice.state.collectAsState()
                val bottom = with(LocalDensity.current) { navigationBar.toDp() }
                NanoTheme(settings.themeMode, settings.dynamicColors) {
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(bottom = bottom).padding(8.dp)) {
                        DictationPanel(
                            state,
                            onFinish = voice::finish,
                            onCancel = ::back,
                            onRetry = voice::listen,
                            problem = if (micMissing) getString(R.string.dictation_no_mic) else null,
                            problemAction = (getString(R.string.dictation_allow_mic) to ::openAppSettings).takeIf { micMissing },
                        )
                    }
                }
            }
        }
    }

    /** A keyboard of its own size, never the whole screen in landscape: the field stays in sight. */
    override fun onEvaluateFullscreenMode() = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        micMissing = checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (micMissing) return
        container.vosk.preload()
        voice.listen()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        voice.cancel()
        // Also called while the keyboard is being destroyed, after which it can move no more.
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        voice.cancel()
        scope.cancel()
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    /** The sentence into the field: a space from the word before, a capital where the field wants one. */
    private fun type(text: String) {
        val connection = currentInputConnection
        if (connection != null && text.isNotBlank()) {
            val capital = connection.getCursorCapsMode(currentInputEditorInfo?.inputType ?: 0) != 0
            connection.commitText(dictatedText(text, connection.getTextBeforeCursor(1, 0), capital), 1)
        }
        back()
    }

    /** Back to the keyboard the person was using. */
    private fun back() {
        voice.cancel()
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            val token = window.window?.attributes?.token
            @Suppress("DEPRECATION")
            token != null && getSystemService(InputMethodManager::class.java).switchToLastInputMethod(token)
        }
        if (!switched) requestHideSelf(0)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        back()
    }
}

private const val TAG = "VoiceInputMethod"
