package dev.nanovoice.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Turns the files Piper publishes into what sherpa-onnx loads. */
internal object PiperFiles {
    /**
     * Appends ONNX `metadata_props` entries to a model file. ONNX is protobuf, and a repeated field may be appended
     * to the end of an already serialized message, so no ONNX library (and no rewrite of 100 MB) is needed.
     * Field 14 of ModelProto is `metadata_props`; each entry is a StringStringEntryProto {1: key, 2: value}.
     */
    fun appendMetadata(model: File, metadata: Map<String, String>) {
        model.appendBytes(metadataBytes(metadata))
    }

    fun metadataBytes(metadata: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((key, value) in metadata) {
            val k = key.toByteArray(Charsets.UTF_8)
            val v = value.toByteArray(Charsets.UTF_8)
            val entry = ByteArrayOutputStream().apply {
                write(0x0A); writeVarint(k.size); write(k)
                write(0x12); writeVarint(v.size); write(v)
            }.toByteArray()
            out.write(0x72) // field 14, length-delimited
            out.writeVarint(entry.size)
            out.write(entry)
        }
        return out.toByteArray()
    }

    /**
     * tokens.txt ("symbol id" per line) from Piper's `phoneme_id_map`. sherpa-onnx reads one symbol per Unicode
     * code point, so the few multi-character symbols Piper 1.x added (English diphthongs such as "aɪ") are dropped:
     * their characters are then mapped one by one, which is what espeak's Italian output uses anyway.
     */
    fun tokensFromConfig(configJson: String): String {
        val root = try {
            Json.parseToJsonElement(configJson)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw IOException("voice config is not valid JSON", e)
        }
        val map = (root as? JsonObject)?.get("phoneme_id_map") as? JsonObject
            ?: throw IOException("voice config has no phoneme_id_map")
        val lines = map.entries.filter { (symbol, _) -> symbol.codePointCount(0, symbol.length) == 1 }
            .map { (symbol, ids) -> "$symbol ${ids.jsonArray[0].jsonPrimitive.int}" }
        if (lines.isEmpty()) throw IOException("voice config has no usable phoneme symbols")
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun ByteArrayOutputStream.writeVarint(value: Int) {
        var n = value
        while (n >= 0x80) {
            write((n and 0x7F) or 0x80)
            n = n ushr 7
        }
        write(n)
    }
}
