package dev.nanovoice.voice

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PiperFilesTest {
    /** Independent protobuf reader for the appended bytes: sequence of (field 14, {1: key, 2: value}). */
    private fun decode(bytes: ByteArray): Map<String, String> {
        var i = 0
        fun varint(): Int {
            var shift = 0; var result = 0
            while (true) {
                val b = bytes[i++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        val out = linkedMapOf<String, String>()
        while (i < bytes.size) {
            assertEquals(0x72, bytes[i++].toInt() and 0xFF, "ModelProto.metadata_props tag")
            val end = varint() + i
            assertEquals(0x0A, bytes[i++].toInt())
            val kl = varint(); val key = String(bytes, i, kl, Charsets.UTF_8); i += kl
            assertEquals(0x12, bytes[i++].toInt())
            val vl = varint(); val value = String(bytes, i, vl, Charsets.UTF_8); i += vl
            assertEquals(end, i, "entry length")
            out[key] = value
        }
        return out
    }

    @Test fun metadataRoundTrips() {
        val meta = linkedMapOf("model_type" to "vits", "comment" to "piper", "language" to "Italian", "sample_rate" to "22050")
        assertEquals(meta, decode(PiperFiles.metadataBytes(meta)))
    }

    @Test fun longAndNonAsciiValuesUseMultiByteLengths() {
        val meta = mapOf("note" to "è".repeat(200) + "ɪ")
        assertEquals(meta, decode(PiperFiles.metadataBytes(meta)))
    }

    @Test fun metadataIsAppendedAndTheOriginalBytesStayUntouched() {
        val f = Files.createTempFile("model", ".onnx").toFile()
        try {
            val original = ByteArray(1000) { (it * 7).toByte() }
            f.writeBytes(original)
            PiperFiles.appendMetadata(f, mapOf("voice" to "it"))
            val after = f.readBytes()
            assertTrue(after.copyOf(original.size).contentEquals(original))
            assertEquals(mapOf("voice" to "it"), decode(after.copyOfRange(original.size, after.size)))
        } finally { f.delete() }
    }

    private val config = """{"audio":{"sample_rate":22050},"phoneme_id_map":{"_":[0],"^":[1],"${'$'}":[2]," ":[3],"a":[14],"aɪ":[160],"oʊ":[165],"ɪ":[100]}}"""

    @Test fun tokensKeepSingleCharactersAndDropDigraphs() {
        val lines = PiperFiles.tokensFromConfig(config).lines().filter { it.isNotEmpty() }
        assertEquals(listOf("_ 0", "^ 1", "$ 2", "  3", "a 14", "ɪ 100"), lines)
    }

    @Test fun tokensEndWithANewline() = assertTrue(PiperFiles.tokensFromConfig(config).endsWith("\n"))

    @Test fun configWithoutPhonemeMapIsRejected() {
        assertFailsWith<IOException> { PiperFiles.tokensFromConfig("""{"audio":{}}""") }
        assertFailsWith<IOException> { PiperFiles.tokensFromConfig("not json") }
        assertFailsWith<IOException> { PiperFiles.tokensFromConfig("""{"phoneme_id_map":{"aɪ":[1]}}""") }
    }

    @Test fun realSerenaStyleConfigProducesTheExpectedTokenCount() {
        val base = (0 until 161).joinToString(",") { "\"${'Ā' + it}\":[$it]" }
        val json = """{"phoneme_id_map":{$base,"aɪ":[161],"aʊ":[162],"ɔɪ":[163],"eɪ":[164],"oʊ":[165]}}"""
        assertEquals(161, PiperFiles.tokensFromConfig(json).lines().count { it.isNotEmpty() })
    }
}
