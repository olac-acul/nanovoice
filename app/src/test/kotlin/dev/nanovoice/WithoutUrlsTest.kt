package dev.nanovoice

import kotlin.test.Test
import kotlin.test.assertEquals

class WithoutUrlsTest {
    @Test
    fun bareUrlIsNotRead() {
        assertEquals("Guarda qui", withoutUrls("Guarda qui https://example.com/a?b=1"))
        assertEquals("Il sito", withoutUrls("Il sito www.example.org/x"))
    }

    @Test
    fun markdownLinkKeepsItsText() {
        assertEquals("Leggi la guida per i dettagli.", withoutUrls("Leggi [la guida](https://example.com/guida) per i dettagli."))
    }

    @Test
    fun aSourceInBracketsAtTheEndOfAnItemIsNotReadAtAll() {
        assertEquals(
            "Spiaggia dei Conigli, Lampedusa.",
            withoutUrls("Spiaggia dei Conigli, Lampedusa. ([Corriere](https://example.com/conigli))"),
        )
        // Brackets that only hold words stay.
        assertEquals("Il museo (chiuso il lunedì) apre alle nove.", withoutUrls("Il museo (chiuso il lunedì) apre alle nove."))
    }

    @Test
    fun plainTextIsUntouched() {
        assertEquals("Sono le dodici e quarantanove.", withoutUrls("Sono le dodici e quarantanove."))
    }
}

class VisibleReplyTest {
    @Test fun dropsTheLineSaidBeforeWorking() {
        assertEquals("Ecco il sito.", visibleReply("Cerco sul Web... Ecco il sito.", "Cerco sul Web..."))
    }

    @Test fun keepsTheReplyWhenThereWasNoAck() {
        assertEquals("Oggi hai tre email.", visibleReply("Oggi hai tre email.", ""))
    }

    @Test fun nothingLeftWhileOnlyTheAckIsThere() {
        assertEquals("", visibleReply("Cerco sul Web...", "Cerco sul Web..."))
    }
}
