package dev.nanovoice

import dev.nanovoice.assistant.awaitQuiet
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuietWaitTest {
    @Test fun returnsAtOnceWhenNothingIsGoingOnOnceTheSpellHasPassed() = runTest {
        val wait = async { awaitQuiet(MutableStateFlow(false), MutableStateFlow(false), settleMs = 1_000) }
        advanceTimeBy(1_001); runCurrent()
        assertTrue(wait.isCompleted)
    }

    @Test fun waitsForTheReplyAndThenForTheVoice() = runTest {
        val busy = MutableStateFlow(true)
        val speaking = MutableStateFlow(false)
        val wait = async { awaitQuiet(busy, speaking, settleMs = 1_000) }
        advanceTimeBy(5_000); runCurrent()
        assertFalse(wait.isCompleted) // still answering
        speaking.value = true
        busy.value = false
        advanceTimeBy(5_000); runCurrent()
        assertFalse(wait.isCompleted) // reading it aloud
        speaking.value = false
        advanceTimeBy(1_001); runCurrent()
        assertTrue(wait.isCompleted)
    }

    @Test fun aShortGapBetweenSentencesDoesNotCount() = runTest {
        val busy = MutableStateFlow(false)
        val speaking = MutableStateFlow(true)
        val wait = async { awaitQuiet(busy, speaking, settleMs = 1_000) }
        runCurrent()
        speaking.value = false
        advanceTimeBy(300); runCurrent()
        speaking.value = true // the next sentence starts
        advanceTimeBy(2_000); runCurrent()
        assertFalse(wait.isCompleted)
        speaking.value = false
        advanceTimeBy(1_001); runCurrent()
        assertTrue(wait.isCompleted)
        delay(0)
    }
}
