package dev.nanovoice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dev.nanovoice.ui.NanoTheme
import dev.nanovoice.wake.WakeWordService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    companion object {
        /** Opens the app as the launcher icon does: the chat already open comes back as it was, not a new one. */
        fun openIntent(context: android.content.Context): Intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasContactsPermission() =
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun openAssistantSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    override fun onStart() {
        super.onStart()
        // Visible now, so Android allows starting the microphone service if the system had stopped it.
        val container = (application as NanoVoiceApp).container
        if (container.settingsStore.settings.value.wakeWord) WakeWordService.apply(this, true)
        // Opened from the assistant sheet: what was said there goes on in this chat.
        container.takeHandOver()?.let { chatViewModel().adopt(it) }
    }

    /** Same instance as the chat screen's: both live in this activity's ViewModel store. */
    private fun chatViewModel(): ChatViewModel =
        ViewModelProvider(this, chatViewModelFactory((application as NanoVoiceApp).container))[ChatViewModel::class.java]

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveSharedPhoto(intent)
    }

    /** "Condividi → Chiedi a NanoVoice" from another app: the photo is attached to the next message. */
    private fun receiveSharedPhoto(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        @Suppress("DEPRECATION")
        val uri = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM))
            ?: return
        val vm = chatViewModel()
        lifecycleScope.launch {
            val photo = withContext(Dispatchers.IO) { Photos.prepare(contentResolver, uri) }
            if (photo == null) Toast.makeText(this@MainActivity, R.string.photo_failed, Toast.LENGTH_SHORT).show()
            else vm.attachPhoto(photo)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as NanoVoiceApp).container
        if (savedInstanceState == null) receiveSharedPhoto(intent)
        setContent {
            val themeMode by container.settingsStore.settings.collectAsState()
            NanoTheme(themeMode.themeMode, themeMode.dynamicColors) {
                val vm: ChatViewModel = viewModel(factory = chatViewModelFactory(container))
                var showSettings by rememberSaveable { mutableStateOf(false) }
                val status by container.connection.status.collectAsState()
                val settings by container.settingsStore.settings.collectAsState()
                val modelState by container.voskModels.state.collectAsState()
                val accurateState by container.parakeetModels.state.collectAsState()
                val voiceStates by container.voices.states.collectAsState(initial = emptyMap())
                val ttsStatus by container.spokenOutput.status.collectAsState()
                var micGranted by remember { mutableStateOf(hasMicPermission()) }
                val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
                val permissionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
                var contactsGranted by remember { mutableStateOf(hasContactsPermission()) }
                var notificationsAllowed by remember { mutableStateOf(container.notifier.canNotify()) }
                val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsAllowed = it }
                val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsGranted = it }

                if (!settings.setupDone) {
                    SetupScreen(
                        voices = container.voices.voices,
                        voiceStates = voiceStates,
                        modelState = modelState,
                        initialVoiceId = settings.voiceId,
                        onStart = { id ->
                            container.settingsStore.save(settings.copy(voiceId = id))
                            container.installVoskModel()
                            container.installVoice(id)
                        },
                        onFinish = { container.finishSetup() },
                        onSkip = container::finishSetup,
                    )
                } else if (showSettings) {
                    SettingsScreen(
                        initial = settings,
                        status = status,
                        modelState = modelState,
                        voiceStates = voiceStates,
                        ttsStatus = ttsStatus,
                        onSave = container.settingsStore::save,
                        onDownloadModel = container::installVoskModel,
                        accurateState = accurateState,
                        onDownloadAccurate = container::installParakeet,
                        onDeleteAccurate = container::deleteParakeet,
                        onInstallVoice = container::installVoice,
                        onDeleteVoice = container::deleteVoice,
                        onPreviewVoice = container::previewVoice,
                        onOpenAssistantSettings = ::openAssistantSettings,
                        contactsGranted = contactsGranted,
                        onRequestContacts = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
                        pushActive = settings.pushEndpoint.isNotEmpty(),
                        pushDistributorAvailable = container.push.distributors().isNotEmpty(),
                        notificationsAllowed = notificationsAllowed,
                        onEnablePush = { container.push.enable(this@MainActivity) },
                        onDisablePush = container.push::disable,
                        onRequestNotifications = { notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        vosk = container.vosk,
                        micGranted = micGranted,
                        onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onRequestPermissions = { permissionsLauncher.launch(it) },
                        onTestMusic = container::testMusic,
                        onOpenNotificationAccess = {
                            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                        },
                        onBack = { showSettings = false },
                    )
                } else {
                    ChatScreen(
                        viewModel = vm,
                        hasMicPermission = ::hasMicPermission,
                        requestMicPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }
}
