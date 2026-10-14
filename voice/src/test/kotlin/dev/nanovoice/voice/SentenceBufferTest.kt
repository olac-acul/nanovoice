package dev.nanovoice.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SentenceBufferTest {
    private val buffer = SentenceBuffer()

    @Test fun waitsForTheSentenceToEnd() {
        assertEquals(emptyList(), buffer.feed("Oggi hai ricevuto "))
        assertEquals(emptyList(), buffer.feed("tre email"))
    }

    @Test fun emitsASentenceOnceWhitespaceFollowsTheTerminator() {
        assertEquals(emptyList(), buffer.feed("Oggi hai tre email."))
        assertEquals(listOf("Oggi hai tre email."), buffer.feed(" Una"))
        assertEquals("Una", buffer.flush())
    }

    @Test fun splitsSeveralSentencesInOneDelta() {
        assertEquals(listOf("Ciao!", "Come stai?"), buffer.feed("Ciao! Come stai? Bene"))
        assertEquals("Bene", buffer.flush())
    }

    @Test fun joinsTokensSplitMidWord() {
        buffer.feed("Con")
        buffer.feed("fer")
        assertEquals(listOf("Confermato."), buffer.feed("mato. Fine"))
    }

    @Test fun newlineEndsASentence() {
        assertEquals(listOf("Prima riga"), buffer.feed("Prima riga\nSeconda"))
        assertEquals("Seconda", buffer.flush())
    }

    @Test fun decimalNumbersDoNotSplit() {
        assertEquals(listOf("Sono 3.5 chilometri."), buffer.feed("Sono 3.5 chilometri. "))
        assertNull(buffer.flush())
    }

    @Test fun abbreviationsDoNotSplit() {
        assertEquals(emptyList(), buffer.feed("Chiama il dott. Rossi domani"))
        assertEquals("Chiama il dott. Rossi domani", buffer.flush())
    }

    @Test fun ellipsisAndQuestionMarkSplit() {
        assertEquals(listOf("Vediamo…", "Davvero?"), buffer.feed("Vediamo… Davvero? Sì"))
    }

    @Test fun flushReturnsNullWhenEmptyAndResets() {
        assertNull(buffer.flush())
        buffer.feed("qualcosa")
        buffer.reset()
        assertNull(buffer.flush())
    }

    @Test fun flushClearsTheBuffer() {
        buffer.feed("Resto")
        assertEquals("Resto", buffer.flush())
        assertNull(buffer.flush())
    }

    @Test fun blankFragmentsAreIgnored() {
        assertEquals(emptyList(), buffer.feed("\n\n  \n"))
        assertNull(buffer.flush())
    }
}
