package dev.nanovoice.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads the small JSON documents the Vosk recognizer returns: {"text": "..."} and {"partial": "..."}. */
internal object VoskJson {
    fun text(raw: String): String = field(raw, "text")

    fun partial(raw: String): String = field(raw, "partial")

    /** Joins recognized segments with single spaces, skipping empty ones. */
    fun join(vararg parts: String): String = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    private fun field(raw: String, key: String): String =
        ((runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject)?.get(key) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim().orEmpty()
}
