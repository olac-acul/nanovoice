package dev.nanovoice.voice

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechGateNoiseTest {
    /** One 128 ms chunk of a tone at about [rms]. */
    private fun chunk(rms: Double, freq: Double = 300.0): ByteArray {
        val samples = 2048
        val amp = rms * kotlin.math.sqrt(2.0)
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (amp * sin(2 * PI * freq * i / 16_000)).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    @Test fun steadyLoudNoiseStopsKeepingTheRecognizerOn() {
        val gate = SpeechGate()
        val noise = chunk(1500.0)
        var openChunks = 0
        repeat(5 * 60 * 8) { // five minutes of a loud TV
            gate.accept(noise, noise.size)
            if (gate.open) openChunks++
        }
        // Open at first, then the noise becomes background: far less than the whole five minutes.
        assertTrue(openChunks < 300, "open for $openChunks chunks")
        assertFalse(gate.open)
    }

    @Test fun speechLouderThanTheNoiseStillOpensIt() {
        val gate = SpeechGate()
        val noise = chunk(1500.0)
        repeat(60 * 8) { gate.accept(noise, noise.size) }
        val voice = chunk(8000.0)
        gate.accept(voice, voice.size)
        assertTrue(gate.open)
    }
}
