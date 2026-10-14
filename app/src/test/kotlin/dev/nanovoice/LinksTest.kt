package dev.nanovoice

import dev.nanovoice.ui.findUrls
import kotlin.test.Test
import kotlin.test.assertEquals

class LinksTest {
    private fun urls(text: String) = findUrls(text).map { text.substring(it) }

    @Test fun findsPlainAddresses() {
        assertEquals(listOf("https://example.com/a?b=1"), urls("Guarda https://example.com/a?b=1 e dimmi"))
        assertEquals(listOf("www.example.org"), urls("Il sito www.example.org"))
    }

    @Test fun leavesSentencePunctuationOut() {
        assertEquals(listOf("https://example.com/a"), urls("Vedi https://example.com/a."))
        assertEquals(listOf("https://example.com/a"), urls("(vedi https://example.com/a)"))
        assertEquals(listOf("https://example.com/a", "https://x.org"), urls("https://example.com/a, poi https://x.org!"))
    }

    @Test fun ignoresTextWithoutAddresses() {
        assertEquals(emptyList(), urls("Sono le dodici e quarantanove."))
        assertEquals(emptyList(), urls("solo https:// da solo"))
    }
}
