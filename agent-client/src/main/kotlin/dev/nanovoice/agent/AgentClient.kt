package dev.nanovoice.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** [Blocked]: a plain ws:// server while no VPN is up; nothing is sent until one is. */
enum class ConnectionState { Disconnected, Connecting, Connected, Blocked }

/** What the agent says in reply to one user message. A flow ends after [Done] or [Error]. */
sealed interface AgentEvent {
    data class Delta(val text: String) : AgentEvent
    /** The agent is still working (a web search, say): more text will follow in this reply. */
    data object Working : AgentEvent
    data object Done : AgentEvent
    data class Error(val message: String) : AgentEvent
}

data class ActionOutcome(val ok: Boolean, val detail: String)

/** A photo sent with a message: [mime] is image/jpeg, image/png or image/webp; the server takes up to 1.5 MB. */
class AgentImage(val mime: String, val bytes: ByteArray)

/**
 * The request of the user an action belongs to: [replyTo] is its message id, [image] the photo sent with it, if any.
 * Actions only ever run for a request still open, so a photo tool gets the photo the user asked about, or none.
 * [isOpen] tells whether it still is: an action that waited (for the person to allow it) checks again before running.
 */
data class ActionRequest(val replyTo: String, val image: AgentImage? = null, val isOpen: () -> Boolean = { true })

/**
 * Executes an `action` requested by the agent. Only called for names the client declared as capabilities, and only for
 * a request of the user that is still open (see [ActionRequest]).
 */
fun interface ActionHandler {
    suspend fun handle(name: String, args: JsonObject, request: ActionRequest): ActionOutcome
}

interface AgentClient {
    val state: StateFlow<ConnectionState>

    /** Text the agent sends on its own (`notify`) or after a reply already ended. */
    val notifications: SharedFlow<String>

    /**
     * The UnifiedPush endpoint the server may wake this phone with while it is not connected; null removes it.
     * Sent right after `hello` on every connection, and immediately if already connected.
     */
    fun setPushEndpoint(url: String?)

    /** Connects and keeps reconnecting with backoff until [stop]. */
    fun start()
    fun stop()

    /** Sends [text], with [image] if given; emits [AgentEvent.Error] right away when not connected. */
    fun ask(text: String, image: AgentImage? = null): Flow<AgentEvent>

    /** Asks the server to start a new conversation (the agent forgets this chat; its memory stays). False if it could not. */
    suspend fun newConversation(): Boolean = false

    /** The person is about to ask: the server may start the agent ahead of the request. Nothing when not connected. */
    fun warmUp() {}
}
