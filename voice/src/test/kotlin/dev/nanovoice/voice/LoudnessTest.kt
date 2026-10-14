package dev.nanovoice.voice

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoudnessTest {
    private fun peak(a: FloatArray) = a.maxOf { abs(it) }

    @Test fun quietSpeechIsRaisedToTheTargetPeak() {
        val s = floatArrayOf(0.1f, -0.3f, 0.2f)
        Loudness.normalize(s)
        assertEquals(0.9f, peak(s), 1e-5f)
    }

    @Test fun veryQuietSpeechGetsAtMostTheMaximumGain() {
        val s = floatArrayOf(0.01f, -0.02f)
        val gain = Loudness.normalize(s)
        assertEquals(4f, gain)
        assertEquals(0.08f, peak(s), 1e-5f)
    }

    @Test fun loudSpeechIsLeftAlone() {
        val s = floatArrayOf(0.95f, -0.5f)
        assertEquals(1f, Loudness.normalize(s))
        assertEquals(0.95f, peak(s))
    }

    @Test fun silenceStaysSilent() {
        val s = FloatArray(8)
        assertEquals(1f, Loudness.normalize(s))
        assertTrue(s.all { it == 0f })
    }

    @Test fun neverClips() {
        val s = floatArrayOf(0.5f, -0.45f, 0.1f)
        Loudness.normalize(s)
        assertTrue(peak(s) <= 1f)
    }
}
