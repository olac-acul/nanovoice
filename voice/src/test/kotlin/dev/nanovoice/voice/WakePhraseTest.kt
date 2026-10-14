package dev.nanovoice.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WakePhraseTest {
    private val phrase = WakePhrase()

    private fun result(vararg words: Pair<String, Double>) =
        words.joinToString(prefix = """{"result":[""", postfix = """],"text":"x"}""") { (w, c) -> """{"conf":$c,"word":"$w","start":0,"end":1}""" }

    @Test fun findsTheWordWhenVoskIsSure() {
        assertEquals(WakeMatch("zanzibar", 0.95), phrase.match(result("zanzibar" to 0.95)))
        assertEquals(WakeMatch("zanzibar", 0.99), phrase.match(result("[unk]" to 0.5, "zanzibar" to 0.99, "[unk]" to 0.4)))
    }

    @Test fun ignoresAnUnsureMatch() {
        assertNull(phrase.match(result("zanzibar" to 0.6))) // a near miss forced onto the grammar
    }

    @Test fun aSoundAlikeIsNotTheWakeWord() {
        assertNull(phrase.match(result("zanzare" to 1.0)))
        assertNull(phrase.match(result("sanzione" to 1.0)))
    }

    @Test fun malformedResultsAreIgnored() {
        assertNull(phrase.match("""{"text":""}"""))
        assertNull(phrase.match("not json"))
    }

    @Test fun grammarListsTheWordTheSoundAlikesAndTheCatchAll() {
        assertEquals("""["zanzibar", "zanzare", "zanzara", "zanzariera", "zanzariere", "sanzione", "[unk]"]""", phrase.grammar())
    }

    @Test fun multiWordPhrasesNeedEveryWordInOrder() {
        val two = WakePhrase(variants = listOf("ehi dario"), soundAlikes = emptyList())
        assertEquals(WakeMatch("ehi dario", 0.95), two.match(result("ehi" to 1.0, "dario" to 0.95)))
        assertNull(two.match(result("dario" to 1.0, "ehi" to 1.0)))
    }
}

class SpeechGateTest {
    private fun tone(level: Int, samples: Int = 2048): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = if (i % 2 == 0) level else -level
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    @Test fun silenceIsHeldBackAndSpeechComesWithWhatPrecededIt() {
        val gate = SpeechGate(preRollChunks = 2, hangoverChunks = 2)
        repeat(10) { assertEquals(0, gate.accept(tone(20), 4096).size) } // quiet room
        val start = gate.accept(tone(5000), 4096)
        assertEquals(3, start.size) // two remembered quiet chunks + the loud one
        assertEquals(1, gate.accept(tone(5000), 4096).size)
        assertEquals(1, gate.accept(tone(20), 4096).size) // hangover: still open
        assertEquals(1, gate.accept(tone(20), 4096).size)
        assertEquals(1, gate.accept(tone(20), 4096).size) // closes after this one
        assertEquals(false, gate.open)
        assertEquals(true, gate.justClosed)
        assertEquals(0, gate.accept(tone(20), 4096).size)
        assertEquals(false, gate.justClosed)
    }

    @Test fun aSteadyNoiseBecomesTheBackground() {
        val gate = SpeechGate()
        repeat(200) { gate.accept(tone(250), 4096) } // below the absolute floor: never opens, background learns it
        assertEquals(false, gate.open)
        assertEquals(true, gate.accept(tone(4000), 4096).isNotEmpty())
    }

    @Test fun rmsOfASquareWave() {
        assertEquals(1000.0, SpeechGate.rms(tone(1000)), 0.001)
    }
}
