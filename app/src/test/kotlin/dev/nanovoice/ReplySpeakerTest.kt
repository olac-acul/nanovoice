package dev.nanovoice

import dev.nanovoice.voice.SpokenOutput
import dev.nanovoice.voice.TtsStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test fun theSourceLineIsShownButNotRead() {
        speaker.onSnapshot("Domani a Ostuni sarà sereno, 14°/19°.\n\nFonte: https://www.3bmeteo.com/meteo/ostuni")
        speaker.finish()
        assertEquals(1, out.said.size) // the forecast, and nothing for the source line
        assertTrue(out.said.none { it.contains("Fonte", ignoreCase = true) })
        for (label in listOf("Fonte:", "fonti", "Fonte.", "Sources:")) assertTrue(isSourceLabel(label), label)
        // a sentence that only starts like a source is still read
        assertTrue(!isSourceLabel("Fonte di energia rinnovabile"))
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

    @Test fun aLongSentenceIsSpokenInSlowlyGrowingPieces() {
        val long = "Domani hai tre appuntamenti, il dentista alle dieci, la riunione con il gruppo di lavoro alle quattordici, " +
            "e la cena da tua sorella alle venti, ricordati il regalo."
        val pieces = spokenChunks(long)
        assertEquals(long.replace(",", ""), pieces.joinToString(" ").replace(",", ""))
        assertTrue(pieces.size > 1 && pieces.first().length <= 60, pieces.toString())
        assertGrowSlowly(pieces)
    }

    @Test fun shortSentencesAndNumbersStayWhole() {
        assertEquals(listOf("Sono le dieci, e un quarto."), spokenChunks("Sono le dieci, e un quarto."))
        val decimals = "La temperatura media di domani sarà di 21,5 gradi con umidità al 60,5 per cento e vento debole da nord."
        assertEquals(
            listOf("La temperatura media di domani sarà di 21,5 gradi,", "con umidità al 60,5 per cento e vento debole da nord."),
            spokenChunks(decimals),
        )
    }

    @Test fun aSentenceWithNowhereToBreatheIsCutBetweenWords() {
        val unbroken = "La riunione con il gruppo di lavoro del progetto nuovo è stata spostata a giovedì pomeriggio alle quindici."
        val pieces = spokenChunks(unbroken)
        assertEquals(unbroken.replace(",", ""), pieces.joinToString(" ").replace(",", ""))
        assertTrue(pieces.size > 1, pieces.toString())
        // Before a preposition or an article, never inside a phrase like "21,5 gradi".
        assertTrue(pieces.drop(1).all { it.substringBefore(' ').lowercase() in setOf("di", "del", "a", "alle", "con", "il") }, pieces.toString())
        assertGrowSlowly(pieces)
    }

    @Test fun aLongSentenceAfterAShortPieceStartsShort() {
        // On the phone: 27 characters said, then 141 with no comma took 8 s to make, 5 s of silence.
        val long = "Il gruppo di lavoro ha deciso di spostare la riunione del progetto nuovo a giovedì pomeriggio nella sala grande del secondo piano dell'edificio"
        val pieces = spokenChunks(long, previous = 27)
        assertTrue(pieces.size > 2 && pieces.first().length <= 51, pieces.toString())
        assertGrowSlowly(listOf("x".repeat(27)) + pieces)
    }

    @Test fun theSpeakerCarriesThePieceLengthAcrossSentences() {
        speaker.onSnapshot("Sì, certo. Il gruppo di lavoro ha deciso di spostare la riunione del progetto nuovo a giovedì pomeriggio nella sala grande. ")
        speaker.finish()
        assertEquals("Sì, certo.", out.said.first())
        assertTrue(out.said[1].length <= 51, out.said.toString())
    }

    private fun assertGrowSlowly(pieces: List<String>) {
        // Each piece at most a fifth longer than the one before (50 at least), with the slack before a cut between words.
        assertTrue(pieces.zipWithNext().all { (a, b) -> b.length <= maxOf(50, (a.length * 1.2).toInt()) * 1.3 + 1 }, pieces.toString())
    }

    @Test fun aLongSentenceWithoutCommasIsCutBeforeAConnectingWord() {
        assertEquals(
            listOf("Domani il tempo resta stabile al mattino,", "ma nel pomeriggio arrivano nuvole e qualche pioggia."),
            spokenChunks("Domani il tempo resta stabile al mattino ma nel pomeriggio arrivano nuvole e qualche pioggia."),
        )
    }

    @Test fun noPieceIsCutTooShort() {
        assertEquals(listOf("Ho comprato il pane e il latte"), breathPieces("Ho comprato il pane e il latte"))
        assertEquals(listOf("E poi la sera siamo usciti", "ma faceva troppo freddo"), breathPieces("E poi la sera siamo usciti ma faceva troppo freddo"))
    }

    @Test fun theSpeakerSaysTheLongSentenceInPieces() {
        speaker.onSnapshot("Ho trovato tre ristoranti aperti stasera vicino a te, il primo è in centro e costa poco, gli altri due sono più lontani.")
        speaker.pause()
        assertTrue(out.said.size > 1, out.said.toString())
    }
}
