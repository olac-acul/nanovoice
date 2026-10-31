package dev.nanovoice.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SilenceTest {
    private val rate = 1000 // one sample per millisecond, to read the lengths as times

    @Test fun theSilenceAroundSpeechIsCutToALeadInAndABreath() {
        val speech = FloatArray(300) { 0.5f }
        val samples = FloatArray(250) + speech + FloatArray(400)
        val trimmed = Silence.trim(samples, rate)
        assertEquals(40 + 300 + 120, trimmed.size)
        assertEquals(0f, trimmed.first())
        assertEquals(0.5f, trimmed[40])
    }

    @Test fun shortSilencesAreKeptAsTheyAre() {
        val samples = FloatArray(20) + FloatArray(100) { 0.5f } + FloatArray(50)
        assertSame(samples, Silence.trim(samples, rate))
    }

    @Test fun quietNoiseCountsAsSilence() {
        val samples = FloatArray(200) { 0.005f } + FloatArray(100) { 0.5f } + FloatArray(200) { -0.005f }
        assertEquals(40 + 100 + 120, Silence.trim(samples, rate).size)
    }

    @Test fun allSilenceIsLeftAlone() {
        val samples = FloatArray(500)
        assertSame(samples, Silence.trim(samples, rate))
    }
}
