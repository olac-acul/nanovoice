package dev.nanovoice.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** NanoClaw wire protocol v1; see docs/PROTOCOL.md. */
internal object Protocol {
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun hello(capabilities: Set<String>, locale: String) = buildJsonObject {
        put("type", "hello")
        put("protocol", VERSION)
        putJsonArray("capabilities") { capabilities.sorted().forEach { add(JsonPrimitive(it)) } }
        put("locale", locale)
    }.toString()

    fun message(session: String, id: String, text: String, image: AgentImage? = null) = buildJsonObject {
        put("type", "message")
        put("session", session)
        put("id", id)
        put("text", text)
        if (image != null) {
            putJsonObject("image") {
                put("mime", image.mime)
                put("data", Base64.getEncoder().encodeToString(image.bytes))
            }
        }
    }.toString()

    fun newConversation() = buildJsonObject { put("type", "new_conversation") }.toString()

    /** A request is about to come: the server may start the agent now. No reply. */
    fun warm() = buildJsonObject { put("type", "warm") }.toString()

    /** The user stopped waiting for this reply: the server drops what the agent still sends for it. */
    fun cancel(session: String, replyTo: String) = buildJsonObject {
        put("type", "cancel")
        put("session", session)
        put("reply_to", replyTo)
    }.toString()

    /** Tells the server where it can send a content-less wake-up push (UnifiedPush), or clears it with null. */
    fun pushEndpoint(url: String?) = buildJsonObject {
        put("type", "push_endpoint")
        if (url != null) put("url", url) else put("url", JsonNull)
    }.toString()

    fun actionResult(session: String, actionId: String, outcome: ActionOutcome) = buildJsonObject {
        put("type", "action_result")
        put("session", session)
        put("action_id", actionId)
        put("ok", outcome.ok)
        put("detail", outcome.detail)
    }.toString()

    sealed interface ServerMessage {
        data class Delta(val replyTo: String, val text: String) : ServerMessage
        data class Action(val replyTo: String?, val actionId: String, val name: String, val args: JsonObject) : ServerMessage
        data class Working(val replyTo: String) : ServerMessage
        data class Done(val replyTo: String) : ServerMessage
        data class Error(val replyTo: String?, val message: String) : ServerMessage
        data class Notify(val text: String) : ServerMessage
        data class ConversationReset(val ok: Boolean) : ServerMessage
    }

    /** Returns null for malformed or unknown messages, which the client ignores. */
    fun parse(raw: String): ServerMessage? {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return when (str("type")) {
            "delta" -> ServerMessage.Delta(str("reply_to") ?: return null, str("text") ?: return null)
            "action" -> ServerMessage.Action(
                str("reply_to"),
                str("action_id") ?: return null,
                str("name") ?: return null,
                obj["args"] as? JsonObject ?: JsonObject(emptyMap()),
            )
            "working" -> ServerMessage.Working(str("reply_to") ?: return null)
            "done" -> ServerMessage.Done(str("reply_to") ?: return null)
            "error" -> ServerMessage.Error(str("reply_to"), str("message") ?: "errore sconosciuto")
            "notify" -> ServerMessage.Notify(str("text") ?: return null)
            "conversation_reset" -> ServerMessage.ConversationReset((obj["ok"] as? JsonPrimitive)?.contentOrNull == "true")
            else -> null
        }
    }
}
