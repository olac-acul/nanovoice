package dev.nanovoice

import dev.nanovoice.agent.AgentClient
import dev.nanovoice.agent.AgentConfig
import dev.nanovoice.agent.AgentEvent
import dev.nanovoice.agent.AgentImage
import dev.nanovoice.agent.ConnectionState
import dev.nanovoice.agent.ServerUrl
import dev.nanovoice.agent.ServerUrlCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class AgentStatus { NotConfigured, Disconnected, Connecting, Connected, NeedsVpn }

private const val WARM_EVERY_MS = 30_000L

/** What the chat needs from the agent: a streaming reply, or null when no server is configured. */
fun interface AgentGateway {
    fun ask(text: String, image: AgentImage?): Flow<AgentEvent>?
}

/** Keeps one [AgentClient] alive for the current server settings, replacing it when they change. */
class AgentConnection(
    scope: CoroutineScope,
    settings: Flow<AppSettings>,
    /** What to declare to the server for the current settings; a change reconnects with the new list. */
    private val capabilities: (AppSettings) -> Set<String>,
    private val clientFactory: (AgentConfig) -> AgentClient,
    /** A plain ws:// server may be reached now (a VPN is up). */
    private val cleartextAllowed: () -> Boolean = { true },
) : AgentGateway {
    private val _status = MutableStateFlow(AgentStatus.NotConfigured)
    val status: StateFlow<AgentStatus> = _status

    private val _notifications = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val notifications: SharedFlow<String> = _notifications

    @Volatile private var client: AgentClient? = null

    init {
        scope.launch {
            settings.map { Triple(it.serverUrl.trim(), it.token, capabilities(it)) }.distinctUntilChanged().collectLatest { (url, token, caps) ->
                if (url.isEmpty() || !ServerUrl.tokenIsValid(token) || ServerUrl.check(url) !is ServerUrlCheck.Valid) {
                    _status.value = AgentStatus.NotConfigured
                    return@collectLatest
                }
                val c = clientFactory(AgentConfig(url, token, caps, cleartextAllowed = cleartextAllowed))
                client = c
                c.start()
                try {
                    coroutineScope {
                        launch { c.notifications.collect { _notifications.emit(it) } }
                        launch {
                            settings.map { it.pushEndpoint.ifEmpty { null } }.distinctUntilChanged().collect { c.setPushEndpoint(it) }
                        }
                        c.state.collect { _status.value = it.toStatus() }
                    }
                } finally {
                    c.stop()
                    if (client === c) client = null
                }
            }
        }
    }

    override fun ask(text: String, image: AgentImage?): Flow<AgentEvent>? = client?.ask(text, image)

    private var lastWarm = 0L

    /**
     * The person tapped the orb, opened the assistant or the keyboard: the agent's container starts while they speak or
     * type, instead of after. At most once in [WARM_EVERY_MS]; the server ignores repeats too.
     */
    fun warmUp(now: Long = System.currentTimeMillis()) {
        if (now - lastWarm < WARM_EVERY_MS) return
        lastWarm = now
        client?.warmUp()
    }

    /** Starts a new conversation on the server; false when not connected or the server refused. */
    suspend fun newConversation(): Boolean = client?.newConversation() ?: false

    private fun ConnectionState.toStatus() = when (this) {
        ConnectionState.Disconnected -> AgentStatus.Disconnected
        ConnectionState.Connecting -> AgentStatus.Connecting
        ConnectionState.Connected -> AgentStatus.Connected
        ConnectionState.Blocked -> AgentStatus.NeedsVpn
    }
}
