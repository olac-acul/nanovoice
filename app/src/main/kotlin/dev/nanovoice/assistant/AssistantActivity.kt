package dev.nanovoice.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import dev.nanovoice.ui.NanoTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dev.nanovoice.ChatViewModel
import dev.nanovoice.MainActivity
import dev.nanovoice.NanoVoiceApp
import dev.nanovoice.chatViewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The compact assistant sheet: opens listening, shows what was understood and the reply, reads the reply aloud. */
class AssistantActivity : ComponentActivity() {
    /** Bumped when the assistant is invoked again while the sheet is already open: listen again. */
    private var invocation by mutableIntStateOf(0)

    /** The phone is locked: the sheet shows over the lock screen and answers only local requests. */
    private var locked by mutableStateOf(false)

    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    /** The fingerprint or PIN prompt is up: the sheet must not close while it is in front. */
    private var dismissing = false

    /** The conversation of this sheet; it lives as long as the sheet does, also while the sheet is off screen. */
    private val sheetConversation: ChatViewModel by viewModels { chatViewModelFactory((application as NanoVoiceApp).container) }

    /** Closes the sheet once the reply that was still under way has been answered and read aloud. */
    private var closeWhenQuiet: Job? = null

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Over the lock screen only when opened from it (wake word or assistant gesture on a locked phone); opened on an
        // unlocked phone it never shows there, so nothing from an unlocked conversation can.
        locked = keyguard.isKeyguardLocked
        configureLockScreen()
        val container = (application as NanoVoiceApp).container
        container.vosk.preload() // the sheet listens right away: start loading the speech model now
        setContent {
            val settings by container.settingsStore.settings.collectAsState()
            NanoTheme(settings.themeMode, settings.dynamicColors) {
                val vm = sheetConversation
                vm.isLocked = { keyguard.isKeyguardLocked }
                var micGranted by remember { mutableStateOf(hasMic()) }
                val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
                AssistantScreen(
                    viewModel = vm,
                    micGranted = micGranted,
                    invocation = invocation,
                    requestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    locked = locked,
                    onUnlock = ::unlock,
                    onOpenApp = { openApp(vm) },
                    onClose = ::finish,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(locked)
            setTurnScreenOn(locked)
        } else {
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (locked) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    override fun onResume() {
        super.onResume()
        locked = keyguard.isKeyguardLocked
    }

    /**
     * Gone from the screen (screen off, phone locked, another app opened): the sheet closes and its conversation goes
     * with it. Otherwise a sheet left open would come back over the lock screen with what was said while unlocked.
     *
     * A reply still under way is not cut short: the Clock app opening to set a timer, or the screen going off right
     * after a question, would otherwise silence the answer. The sheet waits, off screen, until the reply has arrived and
     * been read aloud, then closes. Nothing of it shows over the lock screen meanwhile: only the voice goes on. If the
     * sheet comes back first (the Clock app closes again), it simply stays open.
     */
    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations || dismissing) return
        if (!sheetConversation.busy.value && !sheetConversation.speaking.value) return finish()
        closeWhenQuiet?.cancel()
        closeWhenQuiet = lifecycleScope.launch {
            awaitQuiet(sheetConversation.busy, sheetConversation.speaking)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) finish()
        }
    }

    override fun onStart() {
        super.onStart()
        closeWhenQuiet?.cancel()
        closeWhenQuiet = null
    }

    /**
     * Opens the whole app with what was said here in its chat. On a locked phone it asks for the fingerprint or PIN
     * first. A reply still being written stays behind: the sheet closes and stops waiting for it.
     */
    private fun openApp(vm: ChatViewModel) {
        val container = (application as NanoVoiceApp).container
        val open = {
            container.handOver(vm.messages.value)
            startActivity(MainActivity.openIntent(this))
            finish()
        }
        if (keyguard.isKeyguardLocked) unlock(then = open) else open()
    }

    /** Asks for the fingerprint or PIN; once unlocked the sheet stays open with everything available. */
    private fun unlock(then: () -> Unit = {}) {
        dismissing = true
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                dismissing = false
                locked = false
                then()
            }

            override fun onDismissCancelled() {
                dismissing = false
            }

            override fun onDismissError() {
                dismissing = false
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        invocation++
    }
}
