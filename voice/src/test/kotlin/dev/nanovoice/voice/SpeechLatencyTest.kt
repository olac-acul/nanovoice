package dev.nanovoice.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeechLatencyTest {
    @Test fun measuresFromTheEndOfSpeechAndOnlyOnce() {
        SpeechLatency.endOfSpeech(now = 10_000)
        SpeechLatency.textReady(secondPass = true, now = 10_400) // logs, keeps the mark
        assertEquals(900L, SpeechLatency.since(10_900))
        SpeechLatency.firstAudio(now = 11_200) // logs and clears the mark
        assertNull(SpeechLatency.since(11_300))
    }

    @Test fun anOldMarkIsNotAMeasurement() {
        SpeechLatency.endOfSpeech(now = 0 + 1)
        assertNull(SpeechLatency.since(1 + 61_000))
        assertNull(SpeechLatency.since(1 + 61_001)) // and it was dropped
    }
}
