package dev.nanovoice.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class AgentConfig(
    /** `ws://` or `wss://` URL of the NanoClaw voice channel. */
    val url: String,
    val token: String,
    val capabilities: Set<String>,
    val locale: String = "it-IT",
    val initialBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 30_000,
    /** Max silence while waiting for a reply before giving up (the agent container may need to start cold). */
    val replyTimeoutMs: Long = 180_000,
    /**
     * Whether a plain ws:// connection may be opened now. On a phone: only with a VPN up, so the token never goes in
     * clear over a network where a stranger may answer the server's private address.
     */
    val cleartextAllowed: () -> Boolean = { true },
)

/**
 * WebSocket client for NanoClaw. OkHttp over Ktor: it is already the de-facto Android HTTP stack,
 * its WebSocket is built in (with ping/pong) and nothing here needs Ktor's multiplatform reach.
 */
class OkHttpAgentClient(
    private val config: AgentConfig,
    private val scope: CoroutineScope,
    private val actionHandler: ActionHandler,
    httpClient: OkHttpClient = OkHttpClient(),
    /** One line per action of the agent: its name and how it ended, never its arguments or the data it returned. */
    private val log: (String) -> Unit = {},
) : AgentClient {
    private val http = httpClient.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    // Room for everything the server may hand over at once on reconnection (it keeps up to 50).
    private val _notifications = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val notifications: SharedFlow<String> = _notifications

    private val session = UUID.randomUUID().toString()
    private val counter = AtomicLong()
    private val pending = ConcurrentHashMap<String, Channel<AgentEvent>>()

    /** The photo sent with each open request: what the agent's photo tools may use for it, and nothing else. */
    private val images = ConcurrentHashMap<String, AgentImage>()

    /**
     * Replies the user stopped waiting for (interrupted before `done`). The agent may still be working on them: what
     * it sends later is dropped instead of popping up as a notification, and the actions it asks for are refused.
     */
    private val abandoned: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

    @Volatile private var socket: WebSocket? = null
    @Volatile private var pushEndpoint: String? = null
    @Volatile private var pendingReset: CompletableDeferred<Boolean>? = null
    /** Answers to `new_conversation` requests that timed out and may still arrive: they must not answer a later one. */
    private val lateResets = java.util.concurrent.atomic.AtomicInteger(0)

    /** Why the server refused the last session because of the protocol version, in words for the person; null if not. */
    @Volatile private var protocolRefusal: String? = null
    private var loop: Job? = null

    override suspend fun newConversation(): Boolean {
        val ws = socket.takeIf { _state.value == ConnectionState.Connected } ?: return false
        val answer = CompletableDeferred<Boolean>()
        pendingReset = answer
        if (!ws.send(Protocol.newConversation())) return false
        val ok = withTimeoutOrNull(RESET_TIMEOUT_MS) { answer.await() }
        if (ok == null) lateResets.incrementAndGet()
        return ok ?: false
    }

    override fun warmUp() {
        if (_state.value == ConnectionState.Connected) socket?.send(Protocol.warm())
    }

    override fun setPushEndpoint(url: String?) {
        pushEndpoint = url
        if (_state.value == ConnectionState.Connected) socket?.send(Protocol.pushEndpoint(url))
    }

    override fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { connectLoop() }
    }

    override fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(1000, "client stopping")
        socket = null
        failPending("connessione chiusa")
        _state.value = ConnectionState.Disconnected
    }

    override fun ask(text: String, image: AgentImage?): Flow<AgentEvent> = flow {
        val ws = socket.takeIf { _state.value == ConnectionState.Connected }
        if (ws == null) {
            emit(AgentEvent.Error(protocolRefusal ?: "Server non raggiungibile."))
            return@flow
        }
        val id = "m${counter.incrementAndGet()}"
        val events = Channel<AgentEvent>(Channel.UNLIMITED)
        pending[id] = events
        var finished = false
        try {
            if (!ws.send(Protocol.message(session, id, text, image))) {
                finished = true
                emit(AgentEvent.Error("Server non raggiungibile."))
                return@flow
            }
            if (image != null) images[id] = image
            while (true) {
                val event = withTimeoutOrNull(config.replyTimeoutMs) { events.receive() }
                if (event == null) {
                    // Given up on: like an interruption, what comes later for it is dropped, not shown as a notification.
                    emit(AgentEvent.Error("Il server non risponde."))
                    break
                }
                if (event is AgentEvent.Done || event is AgentEvent.Error) finished = true
                emit(event)
                if (finished) break
            }
        } finally {
            // Marked before it leaves `pending`, so a late frame never finds it in neither; the server is told too.
            if (!finished) {
                synchronized(abandoned) {
                    if (abandoned.size >= MAX_ABANDONED) abandoned.remove(abandoned.first()) // the oldest
                    abandoned += id
                }
                socket?.send(Protocol.cancel(session, id))
            }
            pending.remove(id)
            images.remove(id)
        }
    }

    private suspend fun connectLoop() {
        var backoff = config.initialBackoffMs
        while (true) {
            if (ServerUrl.isCleartext(config.url) && !config.cleartextAllowed()) {
                _state.value = ConnectionState.Blocked
                delay(BLOCKED_RECHECK_MS)
                continue
            }
            _state.value = ConnectionState.Connecting
            val closed = CompletableDeferred<Unit>()
            var opened = false
            var openedAt = 0L
            val request = runCatching {
                Request.Builder().url(config.url).header("Authorization", "Bearer ${config.token}").build()
            }.getOrElse {
                // A URL or token the settings check let through: no connection, but no crash at every launch either.
                _state.value = ConnectionState.Disconnected
                delay(config.maxBackoffMs)
                continue
            }
            val ws = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    openedAt = System.nanoTime()
                    protocolRefusal = null
                    lateResets.set(0) // answers of the previous connection no longer come
                    webSocket.send(Protocol.hello(config.capabilities, config.locale))
                    // Always, even when none: a push turned off while offline must reach the server too.
                    webSocket.send(Protocol.pushEndpoint(pushEndpoint))
                    socket = webSocket
                    _state.value = ConnectionState.Connected
                }

                override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed.complete(Unit)
                }
            })
            try {
                closed.await()
            } finally {
                ws.cancel()
                if (socket === ws) socket = null
            }
            _state.value = ConnectionState.Disconnected
            failPending("connessione persa")
            // Back to the short wait only after a connection that lasted: one closed at once (a refused protocol, two
            // phones with the same token replacing each other) keeps backing off instead of reconnecting every second.
            if (opened && System.nanoTime() - openedAt >= STABLE_CONNECTION_NANOS) backoff = config.initialBackoffMs
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(config.maxBackoffMs)
        }
    }

    private fun handle(ws: WebSocket, raw: String) {
        when (val msg = Protocol.parse(raw) ?: return) {
            is Protocol.ServerMessage.Delta -> {
                // The open request first: a reply leaves `abandoned` only after it is added there, so a delta that finds
                // neither is truly for no request (a notification), never one for a request just given up.
                val open = pending[msg.replyTo]
                if (open != null) open.trySend(AgentEvent.Delta(msg.text)) else if (msg.replyTo !in abandoned) _notifications.tryEmit(msg.text)
            }
            is Protocol.ServerMessage.Working -> pending[msg.replyTo]?.trySend(AgentEvent.Working)
            is Protocol.ServerMessage.Done -> if (abandoned.remove(msg.replyTo)) Unit else pending[msg.replyTo]?.trySend(AgentEvent.Done)
            is Protocol.ServerMessage.Error -> {
                if (msg.replyTo != null && abandoned.remove(msg.replyTo)) return
                val target = msg.replyTo?.let(pending::get)
                when {
                    target != null -> target.trySend(AgentEvent.Error(msg.message))
                    // Only a refused session ends every reply; a complaint about another frame (a push endpoint, an
                    // action result) or about an unknown reply concerns none of them.
                    msg.replyTo == null && msg.message.startsWith(UNSUPPORTED_PROTOCOL) -> {
                        // The server closes the session right after: remember why, until the next attempt.
                        val refusal = protocolRefusalText(msg.message)
                        protocolRefusal = refusal
                        failPending(refusal)
                    }
                    msg.replyTo == null && FATAL_ERROR_PREFIXES.any { msg.message.startsWith(it) } -> failPending(msg.message)
                }
            }
            is Protocol.ServerMessage.Notify -> _notifications.tryEmit(msg.text)
            is Protocol.ServerMessage.ConversationReset ->
                if (lateResets.getAndUpdate { (it - 1).coerceAtLeast(0) } == 0) pendingReset?.complete(msg.ok)
            is Protocol.ServerMessage.Action -> {
                // Decided now, in frame order: a `done` right behind the action must not make it look unsolicited.
                val request = msg.replyTo?.takeIf(::isOpen)?.let { ActionRequest(it, images[it]) { isOpen(it) } }
                scope.launch { runAction(ws, msg, request) }
            }
        }
    }

    /**
     * The phone acts only for a request of the user still open: not for one interrupted, finished or unknown, and not
     * for an action sent on the agent's own (no reply_to: a scheduled task, a reply that came after `done`, text
     * injected into what the agent reads).
     */
    private suspend fun runAction(ws: WebSocket, msg: Protocol.ServerMessage.Action, request: ActionRequest?) {
        val outcome = when {
            request == null -> ActionOutcome(false, "nessuna richiesta dell'utente in corso: azione non eseguita")
            msg.name !in config.capabilities -> ActionOutcome(false, "azione non supportata: ${msg.name}")
            else -> runCatching { actionHandler.handle(msg.name, msg.args, request) }
                .getOrElse { ActionOutcome(false, it.message ?: "errore") }
        }
        // Never the detail: it can hold the person's words or the address book ("Non trovo X", "Mario o Maria?").
        log(
            "${msg.name}: " + when {
                outcome.ok -> "ok"
                request == null -> "rifiutata (nessuna richiesta aperta)"
                msg.name !in config.capabilities -> "rifiutata (non dichiarata)"
                else -> "rifiutata"
            },
        )
        ws.send(Protocol.actionResult(session, msg.actionId, outcome))
    }

    private fun isOpen(replyTo: String) = pending.containsKey(replyTo) && replyTo !in abandoned

    private fun failPending(message: String) {
        for (channel in pending.values) channel.trySend(AgentEvent.Error(message))
    }

    private companion object {
        const val RESET_TIMEOUT_MS = 10_000L
        const val MAX_ABANDONED = 50
        /** A connection that stayed up this long resets the reconnect wait to its shortest. */
        const val STABLE_CONNECTION_NANOS = 30_000_000_000L
        const val BLOCKED_RECHECK_MS = 3_000L
        const val UNSUPPORTED_PROTOCOL = "unsupported protocol"
        val FATAL_ERROR_PREFIXES = arrayOf("hello required", UNSUPPORTED_PROTOCOL)
    }
}
