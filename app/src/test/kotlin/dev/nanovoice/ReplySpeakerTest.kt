package dev.nanovoice

import dev.nanovoice.voice.SpokenOutput
import dev.nanovoice.voice.TtsStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeSpokenOutput : SpokenOutput {
    val said = mutableListOf<String>()
    var stops = 0
    override val status: StateFlow<TtsStatus> = MutableStateFlow(TtsStatus.Ready)
    override val speaking: StateFlow<Boolean> = MutableStateFlow(false)
    override fun speak(text: String) { said += text }
    override fun stop() { stops++ }
    override fun shutdown() {}
}

class ReplySpeakerTest {
    private val out = FakeSpokenOutput()
    private val speaker = ReplySpeaker(out)

    @Test fun speaksCompleteSentencesAsTheReplyGrows() {
        speaker.onSnapshot("Oggi hai ")
        speaker.onSnapshot("Oggi hai tre email. Una è")
        assertEquals(listOf("Oggi hai tre email."), out.said)
        speaker.onSnapshot("Oggi hai tre email. Una è importante.")
        speaker.finish()
        assertEquals(listOf("Oggi hai tre email.", "Una è importante."), out.said)
    }

    @Test fun pauseReadsTheAcknowledgementAtOnceAndTheAnswerLater() {
        speaker.onSnapshot("Cerco sul Web...")
        assertEquals(emptyList(), out.said)
        speaker.pause()
        assertEquals(listOf("Cerco sul Web..."), out.said)
        speaker.onSnapshot("Cerco sul Web... Ecco il sito.")
        speaker.finish()
        assertEquals(listOf("Cerco sul Web...", "Ecco il sito."), out.said)
    }

    @Test fun aShortLocalReplyIsSpokenOnFinish() {
        speaker.onSnapshot("sono le dieci e un quarto")
        assertEquals(emptyList(), out.said)
        speaker.finish()
        assertEquals(listOf("sono le dieci e un quarto"), out.said)
    }

    @Test fun neverRepeatsWhatWasAlreadySaid() {
        speaker.onSnapshot("Uno. Due. ")
        speaker.onSnapshot("Uno. Due. Tre.")
        speaker.finish()
        assertEquals(listOf("Uno.", "Due.", "Tre."), out.said)
    }

    @Test fun finishResetsForTheNextReply() {
        speaker.onSnapshot("Prima.")
        speaker.finish()
        speaker.onSnapshot("Seconda.")
        speaker.finish()
        assertEquals(listOf("Prima.", "Seconda."), out.said)
    }

    @Test fun aWholeMessageIsSpokenAtOnceNotWhenTheReplyIsOver() {
        // A one-message reply has no space after its last full stop: without the flush it would wait for finish(),
        // which comes with the server's "done", about 1.5 s later.
        speaker.onSnapshot("Domani alle 10 hai il dentista.")
        assertEquals(emptyList(), out.said)
        speaker.pause()
        assertEquals(listOf("Domani alle 10 hai il dentista."), out.said)
        speaker.onSnapshot("Domani alle 10 hai il dentista. Vuoi un promemoria?")
        speaker.pause()
        speaker.finish()
        assertEquals(listOf("Domani alle 10 hai il dentista.", "Vuoi un promemoria?"), out.said)
    }

    @Test fun errorTextAppendedToAPartialReplyIsSpokenToo() {
        speaker.onSnapshot("Oggi hai")
        speaker.onSnapshot("Oggi hai\nconnessione persa")
        speaker.finish()
        assertEquals(listOf("Oggi hai", "connessione persa"), out.said)
    }
}
