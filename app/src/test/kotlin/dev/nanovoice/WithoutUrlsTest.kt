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
    fun anAddressWithoutHttpOrWwwIsNotReadEither() {
        assertEquals("Fonte:", withoutUrls("Fonte: example.it/2026/11/01/treni-regionali"))
        assertEquals("Lo dice il sito .", withoutUrls("Lo dice il sito News.Example.COM ."))
        // a link whose text is an address
        assertEquals("Articolo:", withoutUrls("Articolo: [example.it/treni](https://example.it/treni)"))
        // not an email address, a file name or a decimal
        assertEquals("Scrivi a info@example.it", withoutUrls("Scrivi a info@example.it")) // privacy-scan: allow (synthetic address on example.it)
        assertEquals("Allego verbale.pdf e costa 3.50 euro.", withoutUrls("Allego verbale.pdf e costa 3.50 euro."))
    }

    @Test
    fun theSourceIsNotReadByItsNameEither() {
        assertEquals("", withoutSources("Fonte: Il Post."))
        assertEquals("", withoutSources("fonti: ISTAT e Banca d'Italia"))
        assertEquals("La disoccupazione è al 6%.", withoutSources("La disoccupazione è al 6% (fonte: ISTAT)."))
        assertEquals("La disoccupazione è al 6%.", withoutSources(withoutUrls("La disoccupazione è al 6% (Fonte: [ISTAT](https://example.it/dati)).")))
        // a word that only starts like it stays
        assertEquals("Fonte di energia rinnovabile.", withoutSources("Fonte di energia rinnovabile."))
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

class QuoteTest {
    @Test fun aQuotedListLosesItsMarkersAndAddresses() {
        val recap = "## Recap di Reddit\n\n- **r/italy**: treni regionali ([Reddit](https://example.com/a))\n- [r/torino](https://example.com/b): ciclabile"
        assertEquals("Recap di Reddit r/italy: treni regionali r/torino: ciclabile", quoteOf(ChatMessage(1, recap, fromUser = false)))
    }
}
