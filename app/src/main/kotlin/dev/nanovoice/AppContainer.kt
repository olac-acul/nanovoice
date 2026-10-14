package dev.nanovoice

import android.content.Context
import dev.nanovoice.actions.AndroidActionExecutor
import dev.nanovoice.actions.AndroidCalendarEditor
import dev.nanovoice.actions.it.ItalianPhrases
import dev.nanovoice.agent.OkHttpAgentClient
import dev.nanovoice.push.PushController
import dev.nanovoice.router.LocalRouter
import dev.nanovoice.voice.SpeechToText
import dev.nanovoice.voice.SpokenOutput
import dev.nanovoice.voice.SherpaSpokenOutput
import dev.nanovoice.voice.VoiceLibrary
import dev.nanovoice.voice.VoskModels
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.nanovoice.wake.WakeWordService
import dev.nanovoice.voice.FilesInstaller
import dev.nanovoice.voice.ModelManager
import dev.nanovoice.voice.ParakeetModels
import dev.nanovoice.voice.ParakeetTranscriber
import dev.nanovoice.voice.VoskSpeechToText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Manual dependency injection. Italian is the only supported locale for now. */
class AppContainer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val locale = Locale.ITALIAN
    val phrases = ItalianPhrases
    private val executor = AndroidActionExecutor(context, phrases, music = { settingsStore.settings.value.musicAccount() })

    val settingsStore: SettingsStore = SecureSettingsStore(context)

    private val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)

    /** A VPN carries the traffic (Tailscale): a plain ws:// server is then reached through an encrypted tunnel. */
    private fun vpnActive(): Boolean = connectivity?.let { cm ->
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    } == true

    private val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)

    /** The agent's photo tools, on the photo of the request they answer. */
    val photoActions = PhotoActions(context.applicationContext) { settingsStore.settings.value.plantNetKey }

    /** Approvals requested by the agent, and approvals bound to a change of a calendar event. */
    val consent = ActionConsent()

    private val calendarActions = CalendarActions(
        AndroidCalendarEditor(context), consent::ask,
        enabled = { settingsStore.settings.value.let { it.readCalendar && it.editCalendar } },
        locked = { keyguard?.isKeyguardLocked == true },
    )

    val connection = AgentConnection(
        scope = scope,
        settings = settingsStore.settings,
        capabilities = PhoneActionHandler::capabilities,
        cleartextAllowed = ::vpnActive,
        clientFactory = { config ->
            OkHttpAgentClient(
                config,
                scope,
                PhoneActionHandler(
                    executor,
                    { PhoneActionHandler.capabilities(settingsStore.settings.value) },
                    photoActions::handle,
                    locked = { keyguard?.isKeyguardLocked == true },
                    consent = consent::ask,
                    calendar = calendarActions,
                ),
                log = { android.util.Log.i("AgentActions", it) },
            )
        },
    )

    val voices = VoiceLibrary(File(context.filesDir, "tts")).also { it.removeUnknownVoices() }
    private val sherpa = SherpaSpokenOutput(context, voices, settingsStore.settings.map { it.voiceId }, scope, phrases.fixedPhrases)
    val spokenOutput: SpokenOutput = sherpa
    val voskModels = ModelManager(File(context.filesDir, "vosk"), VoskModels.ITALIAN_SMALL)

    /** Optional second pass on each utterance: Parakeet, downloaded on request (670 MB). */
    val parakeetModels = FilesInstaller(File(context.filesDir, "parakeet"), ParakeetModels.V3_INT8)
    private val parakeet = ParakeetTranscriber(parakeetModels)

    /** Speech recognition is always Vosk: the system recognizer is often English-only on GrapheneOS. */
    val vosk = VoskSpeechToText(context, voskModels, parakeet) { settingsStore.settings.value.accurateStt }
    val stt: SpeechToText = vosk

    val linkPreviews = LinkPreviews()

    val notifier = Notifier(context)
    val push = PushController(context, settingsStore)

    init {
        // The wake word listener follows its setting. Started here it may be refused (the process was started in the
        // background); MainActivity tries again when the app is opened.
        val appContext = context.applicationContext
        scope.launch {
            settingsStore.settings.map { it.wakeWord }.distinctUntilChanged().collect { WakeWordService.apply(appContext, it) }
        }
        // Things the agent says on its own: a system notification when the app is not on screen.
        scope.launch { connection.notifications.collect { if (notifier.shouldNotify()) notifier.showAgentMessage(it) } }
    }

    fun installVoskModel() {
        scope.launch { voskModels.install() }
    }

    fun installParakeet() {
        scope.launch { parakeetModels.install() }
    }

    fun deleteParakeet() = parakeetModels.delete()

    fun installVoice(id: String) {
        scope.launch { voices.install(id) }
    }

    fun deleteVoice(id: String) = voices.delete(id)

    /** Checks a Navidrome account from the settings screen: null when it works, otherwise what went wrong. */
    suspend fun testMusic(account: dev.nanovoice.actions.music.MusicAccount): String? =
        dev.nanovoice.actions.music.NavidromeClient(account).ping().exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }

    fun previewVoice(id: String) {
        val name = voices.spec(id)?.displayName ?: return
        sherpa.preview(id, "Ciao, sono $name. Posso leggerti le risposte.")
    }

    fun finishSetup() = settingsStore.save(settingsStore.settings.value.copy(setupDone = true))

    private fun router(): LocalRouter {
        return LocalRouter.forLocale(Locale.getDefault()) ?: LocalRouter.forLocale(Locale.ITALIAN)!!
    }

    fun newChatEngine() = ChatEngine(::router, executor, phrases, connection, localCommands = { settingsStore.settings.value.localCommands })

    /** What the assistant sheet said and heard, waiting for the main chat to take it when the app opens from there. */
    @Volatile private var handedOver: List<ChatMessage>? = null

    fun handOver(messages: List<ChatMessage>) {
        handedOver = messages.takeIf { it.isNotEmpty() }
    }

    fun takeHandOver(): List<ChatMessage>? = handedOver.also { handedOver = null }
}
