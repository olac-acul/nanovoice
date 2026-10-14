package dev.nanovoice.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The wake word as Vosk should look for it. Vosk gets a small grammar, which keeps it light, but with only the word
 * to choose from it forces near misses ("zanzare") onto it. So the grammar also lists the words that sound alike
 * ([soundAlikes]): Vosk can then pick those instead. Only words Vosk is sure of count.
 * "Zanzibar" on synthetic Italian speech: alone 10/10 found and 11 false alarms in 30 sentences; with the sound-alikes
 * 10/10, none. (A two-word phrase starting with "ehi" was dropped: on the phone it missed 2 in 10 and "ehi andiamo" set it off.)
 */
data class WakePhrase(
    val variants: List<String> = listOf("zanzibar"),
    val soundAlikes: List<String> = listOf("zanzare", "zanzara", "zanzariera", "zanzariere", "sanzione"),
    val minConfidence: Double = 0.9,
) {
    /** The grammar for Vosk's recognizer: the variants, the sound-alikes and the catch-all for everything else. */
    fun grammar(): String = (variants + soundAlikes + "[unk]").joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    /**
     * Whether a Vosk result (with word confidences) contains the phrase. Returns what was heard, with the lowest
     * confidence among its words, or null.
     */
    fun match(resultJson: String): WakeMatch? {
        val words = (runCatching { Json.parseToJsonElement(resultJson) }.getOrNull() as? JsonObject)?.get("result") as? JsonArray
            ?: return null
        val heard = words.mapNotNull { w ->
            val o = w as? JsonObject ?: return@mapNotNull null
            val word = (o["word"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val conf = (o["conf"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            word to conf
        }
        for (variant in variants) {
            val target = variant.split(' ')
            for (start in 0..heard.size - target.size) {
                val window = heard.subList(start, start + target.size)
                if (window.map { it.first } == target && window.all { it.second >= minConfidence }) {
                    return WakeMatch(variant, window.minOf { it.second })
                }
            }
        }
        return null
    }
}

data class WakeMatch(val phrase: String, val confidence: Double)

/** What the wake-word listener reports. */
sealed interface WakeEvent {
    data object Listening : WakeEvent
    /** What Vosk understood for a stretch of speech (for the trial screen). */
    data class Understood(val text: String) : WakeEvent
    data class Heard(val match: WakeMatch, val atMs: Long) : WakeEvent
    /** Running totals: seconds of audio heard, how many of them went to the recognizer, and the CPU time spent. */
    data class Usage(val audioSeconds: Double, val voicedSeconds: Double, val cpuMs: Long) : WakeEvent
    data class Failed(val error: SttError, val detail: String? = null) : WakeEvent
}

/**
 * Decides when there is something worth passing to the recognizer, so it can rest in silence. Works on the loudness of
 * each chunk against a background level it keeps learning while nothing is going on; stays open [hangoverChunks]
 * after the last loud chunk so a word is not cut, and remembers [preRollChunks] so the start of "ehi" is not lost.
 */
class SpeechGate(
    private val preRollChunks: Int = 4,
    private val hangoverChunks: Int = 8,
    private val ratio: Double = 2.5,
    private val minLevel: Double = 300.0,
    /** Open this long without a pause (about 15 s of 128 ms chunks): steady noise, not speech. */
    private val maxOpenChunks: Int = 117,
) {
    private var background = minLevel / ratio
    private var quietFor = Int.MAX_VALUE
    private var openFor = 0
    private val preRoll = ArrayDeque<ByteArray>()

    /** True while chunks go to the recognizer. */
    var open = false
        private set

    /** The last [accept] ended a stretch of speech: time to ask the recognizer for its final result. */
    var justClosed = false
        private set

    /**
     * Feeds one chunk of 16-bit little-endian PCM. Returns the chunks to pass on now: none in silence, the remembered
     * ones plus this one when speech starts, just this one while it lasts.
     */
    fun accept(chunk: ByteArray, length: Int): List<ByteArray> {
        val copy = chunk.copyOf(length)
        val level = rms(copy)
        val loud = level > maxOf(minLevel, background * ratio)
        // Learn the room quickly while it is quiet, and very slowly while it is not: a TV or a fan left on must end up
        // as background instead of keeping the recognizer running for hours.
        background = if (!loud && !open) background * 0.95 + level * 0.05 else background * 0.999 + level * 0.001
        quietFor = if (loud) 0 else quietFor + 1
        openFor = if (open) openFor + 1 else 0
        justClosed = false
        if (open && openFor > maxOpenChunks) {
            // Too long for a wake word: it is the room. Close, and take its current level as the new background.
            open = false
            justClosed = true
            background = maxOf(background, level)
            remember(copy)
            return listOf(copy)
        }
        return when {
            loud && !open -> { open = true; (preRoll + copy).also { preRoll.clear() } }
            open && quietFor > hangoverChunks -> { open = false; justClosed = true; remember(copy); listOf(copy) }
            open -> listOf(copy)
            else -> { remember(copy); emptyList() }
        }
    }

    private fun remember(chunk: ByteArray) {
        preRoll.addLast(chunk)
        while (preRoll.size > preRollChunks) preRoll.removeFirst()
    }

    companion object {
        fun rms(pcm: ByteArray): Double {
            var sum = 0.0
            var n = 0
            var i = 0
            while (i + 1 < pcm.size) {
                val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
                sum += s * s
                n++
                i += 2
            }
            return if (n == 0) 0.0 else kotlin.math.sqrt(sum / n)
        }
    }
}
