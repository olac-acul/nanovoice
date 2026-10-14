package dev.nanovoice

import dev.nanovoice.voice.SpeechToText
import dev.nanovoice.voice.SttError
import dev.nanovoice.voice.SttEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceControllerTest {
    private class FakeStt : SpeechToText {
        val events = Channel<SttEvent>(Channel.UNLIMITED)
        var stops = 0
        var started = 0
        override val id = "fake"
        override fun isAvailable() = true
        var lastEndOnSilence: Boolean? = null
        override fun start(endOnSilence: Boolean): Flow<SttEvent> { started++; lastEndOnSilence = endOnSilence; return events.receiveAsFlow() }
        override fun stopListening() { stops++ }
    }

    private val stt = FakeStt()
    private val spoken = FakeSpokenOutput()
    private val heard = mutableListOf<String>()

    private fun TestScope.controller() = VoiceController(backgroundScope, { stt }, spoken, heard::add)

    @Test fun listenOpensTheMicrophoneAndSilencesTheTts() = runTest {
        val c = controller()
        c.listen(); runCurrent()
        assertTrue(c.state.value.listening)
        assertEquals(1, spoken.stops)
        assertEquals(1, stt.started)
    }

    @Test fun partialsAreShownThenTheFinalTextIsDelivered() = runTest {
        val c = controller()
        c.listen(); runCurrent()
        stt.events.send(SttEvent.Ready)
        stt.events.send(SttEvent.Partial("che ore"))
        runCurrent()
        assertEquals("che ore", c.state.value.partial)
        stt.events.send(SttEvent.Final("che ore sono"))
        runCurrent()
        assertEquals(listOf("che ore sono"), heard)
        assertFalse(c.state.value.listening)
        assertEquals("", c.state.value.partial)
    }

    @Test fun finishAfterReadyAsksTheEngineToFinish() = runTest {
        val c = controller()
        c.listen(); runCurrent()
        stt.events.send(SttEvent.Ready); runCurrent()
        c.finish()
        assertEquals(1, stt.stops)
        assertTrue(c.state.value.listening) // still waiting for the final text
        assertTrue(c.state.value.finishing)
        stt.events.send(SttEvent.Final("stop")); runCurrent()
        assertEquals(listOf("stop"), heard)
        assertFalse(c.state.value.finishing)
    }

    @Test fun theOrbListensThenFinishesThenCancelsUntilTheReplyIsOver() {
        val idle = VoiceUiState()
        val listening = VoiceUiState(listening = true)
        val finishing = VoiceUiState(listening = true, finishing = true)
        assertEquals(OrbAction.Listen, orbActionFor(idle, busy = false, speaking = false))
        assertEquals(OrbAction.Finish, orbActionFor(listening, busy = false, speaking = false))
        assertEquals(OrbAction.Cancel, orbActionFor(finishing, busy = false, speaking = false))
        assertEquals(OrbAction.Cancel, orbActionFor(idle, busy = true, speaking = false))
        assertEquals(OrbAction.Cancel, orbActionFor(idle, busy = false, speaking = true))
        // Talking again while a reply is read out: the new request wins.
        assertEquals(OrbAction.Finish, orbActionFor(listening, busy = true, speaking = false))
        assertEquals(OrbAction.Listen, orbActionFor(VoiceUiState(error = SttError.NoSpeech), busy = false, speaking = false))
    }

    @Test fun finishingBeforeTheEngineIsReadyDiscardsTheAudio() = runTest {
        val c = controller()
        c.listen(); runCurrent()
        c.finish()
        assertEquals(0, stt.stops)
        assertFalse(c.state.value.listening)
        assertEquals(emptyList(), heard)
    }

    @Test fun errorsAreReportedAndClearedByTheNextListen() = runTest {
        val c = controller()
        c.listen(); runCurrent()
        stt.events.send(SttEvent.Error(SttError.NoSpeech)); runCurrent()
        assertEquals(SttError.NoSpeech, c.state.value.error)
        assertFalse(c.state.value.listening)
        c.cancel()
        c.listen(); runCurrent()
        assertEquals(null, c.state.value.error)
    }

    @Test fun aSecondListenWhileListeningIsIgnored() = runTest {
        val c = controller()
        c.listen(); runCurrent()
        c.listen(); runCurrent()
        assertEquals(1, stt.started)
    }

    @Test fun listeningEndsOnAPauseAndShowsTheTextIsBeingWorkedOut() = runTest {
        val c = controller()
        c.listen(); runCurrent()
        assertEquals(true, stt.lastEndOnSilence)
        stt.events.send(SttEvent.Ready)
        stt.events.send(SttEvent.Ended); runCurrent()
        assertTrue(c.state.value.listening)
        assertTrue(c.state.value.finishing) // the orb now cancels instead of finishing
        assertEquals(0, stt.stops)
    }

    @Test fun listenDeliversTheUtteranceWithoutAnyFinish() = runTest {
        val c = controller()
        c.listen(); runCurrent()
        stt.events.send(SttEvent.Ready)
        stt.events.send(SttEvent.Final("che ore sono"))
        runCurrent()
        assertEquals(listOf("che ore sono"), heard)
        assertFalse(c.state.value.listening)
    }
}
