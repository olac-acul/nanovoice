package dev.nanovoice.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nanovoice.ChatViewModel
import dev.nanovoice.MainActivity
import dev.nanovoice.NanoVoiceApp
import dev.nanovoice.chatViewModelFactory

/** The compact assistant sheet: opens listening, shows what was understood and the reply, reads the reply aloud. */
class AssistantActivity : ComponentActivity() {
    /** Bumped when the assistant is invoked again while the sheet is already open: listen again. */
    private var invocation by mutableIntStateOf(0)

    /** The phone is locked: the sheet shows over the lock screen and answers only local requests. */
    private var locked by mutableStateOf(false)

    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    /** The fingerprint or PIN prompt is up: the sheet must not close while it is in front. */
    private var dismissing = false

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Over the lock screen only when opened from it (wake word or assistant gesture on a locked phone); opened on an
        // unlocked phone it never shows there, so nothing from an unlocked conversation can.
        locked = keyguard.isKeyguardLocked
        configureLockScreen()
        val container = (application as NanoVoiceApp).container
        setContent {
            val settings by container.settingsStore.settings.collectAsState()
            NanoTheme(settings.themeMode, settings.dynamicColors) {
                val vm: ChatViewModel = viewModel(factory = chatViewModelFactory(container))
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
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !dismissing) finish()
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
