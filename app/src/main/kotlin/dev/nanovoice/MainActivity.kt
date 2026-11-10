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
import androidx.compose.runtime.LaunchedEffect
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

        /** From an alert's notification: the app opens on that alert. */
        fun openAlertIntent(context: android.content.Context, id: Long): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ALERT, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private const val EXTRA_ALERT = "dev.nanovoice.alert"
    }

    /** "Avvisi" on screen, and the alert open in it (null: the list). Set by a tap on a notification too. */
    private var showAlerts by mutableStateOf(false)
    private var openAlert by mutableStateOf<Long?>(null)

    private fun openAlertFrom(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_ALERT, -1L)?.takeIf { it >= 0 } ?: return
        intent.removeExtra(EXTRA_ALERT)
        showAlerts = true
        openAlert = id
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

    /** "Apri la VPN": Tailscale when installed (the VPN the server pairing assumes), otherwise Android's VPN settings. */
    private fun openVpn() {
        val tailscale = packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
        try {
            startActivity(tailscale ?: Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    override fun onStart() {
        super.onStart()
        // Visible now, so Android allows starting the microphone service if the system had stopped it.
        val container = (application as NanoVoiceApp).container
        if (container.settingsStore.settings.value.wakeWord) WakeWordService.apply(this, true)
        container.vosk.preload() // the first listening does not wait for the speech model
        // Opened from the assistant sheet: what was said there goes on in this chat.
        container.takeHandOver()?.let { chatViewModel().adopt(it) }
        chatViewModel().showMissedAlerts()
    }

    /** Same instance as the chat screen's: both live in this activity's ViewModel store. */
    private fun chatViewModel(): ChatViewModel =
        ViewModelProvider(this, chatViewModelFactory((application as NanoVoiceApp).container))[ChatViewModel::class.java]

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShared(intent)
        openAlertFrom(intent)
    }

    /** "Condividi → Chiedi a NanoVoice" from another app: the photo is attached to the next message, text goes in the draft. */
    private fun receiveShared(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { chatViewModel().receiveShared(it, intent.getStringExtra(Intent.EXTRA_SUBJECT)) }
            return
        }
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
        if (savedInstanceState == null) {
            receiveShared(intent)
            openAlertFrom(intent)
        }
        setContent {
            val themeMode by container.settingsStore.settings.collectAsState()
            NanoTheme(themeMode.themeMode, themeMode.dynamicColors) {
                val vm: ChatViewModel = viewModel(factory = chatViewModelFactory(container))
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var settingsStart by rememberSaveable { mutableStateOf<String?>(null) }
                val status by container.connection.status.collectAsState()
                val settings by container.settingsStore.settings.collectAsState()
                val updateBadge by container.updates.badge.collectAsState()
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
                        paired = settings.serverUrl.isNotBlank() && dev.nanovoice.agent.ServerUrl.tokenIsValid(settings.token),
                        onPair = container::pair,
                        onPaired = { server, token ->
                            container.settingsStore.save(container.settingsStore.settings.value.copy(serverUrl = server, token = token))
                        },
                        onStart = { id ->
                            container.settingsStore.save(settings.copy(voiceId = id))
                            container.installVoskModel()
                            container.installVoice(id)
                        },
                        onFinish = { container.finishSetup() },
                        onSkip = container::finishSetup,
                    )
                } else if (showAlerts) {
                    val alerts by container.inbox.alerts.collectAsState()
                    // Read once open, however it was opened (the list, the chat's line, a notification).
                    LaunchedEffect(openAlert) {
                        openAlert?.let {
                            container.inbox.markRead(it)
                            container.notifier.cancelAlert(it)
                        }
                    }
                    AlertsScreen(
                        alerts = alerts,
                        openId = openAlert,
                        onOpen = { openAlert = it },
                        onClose = { openAlert = null },
                        onBack = { showAlerts = false },
                        onListen = { vm.readAloud(ChatMessage(-1, it.text, fromUser = false)) },
                        onReply = { alert ->
                            vm.replyTo(ChatMessage(-1, alert.text, fromUser = false))
                            openAlert = null
                            showAlerts = false
                        },
                        onDelete = { id ->
                            container.inbox.delete(id)
                            container.notifier.cancelAlert(id)
                            openAlert = null
                        },
                        onMarkAllRead = container.inbox::markAllRead,
                        onDeleteAll = {
                            // Their system notifications go too: one tapped later would open nothing.
                            container.inbox.alerts.value.forEach { container.notifier.cancelAlert(it.id) }
                            container.inbox.deleteAll()
                        },
                    )
                } else if (showSettings) {
                    val chat by vm.messages.collectAsState()
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
                        onPair = container::pair,
                        onRetryConnection = container.connection::retryNow,
                        onOpenVpn = ::openVpn,
                        onOpenNotificationAccess = {
                            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                        },
                        onBack = { showSettings = false },
                        startPage = SettingsPage.entries.firstOrNull { it.name == settingsStart },
                        updates = container.updates,
                        onNewConversation = vm::newConversation,
                        onTryExample = vm::putInDraft,
                        messageCount = chat.count { !it.notice },
                        pictureCount = chat.count { it.image != null },
                        onlyThumbnails = hasOnlyThumbnails(chat),
                        onExportMessages = { withPictures ->
                            shareChat(this@MainActivity, vm.messages.value, container.updates.installed, withPictures)
                        },
                        onClearMessages = vm::clearMessages,
                    )
                } else {
                    ChatScreen(
                        viewModel = vm,
                        hasMicPermission = ::hasMicPermission,
                        requestMicPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onOpenSettings = { settingsStart = null; showSettings = true },
                        updateBadge = updateBadge,
                        onCheckConnection = { settingsStart = SettingsPage.Server.name; showSettings = true },
                        onOpenVpn = ::openVpn,
                        onOpenAlerts = { id ->
                            showAlerts = true
                            openAlert = id
                        },
                    )
                }
            }
        }
    }
}
