package dev.nanovoice.router

import kotlin.test.Test
import kotlin.test.assertEquals

class TextNormalizerTest {
    private fun n(s: String) = TextNormalizer.normalize(s)

    @Test fun lowercases() = assertEquals("che ore sono", n("Che Ore Sono"))
    @Test fun stripsAccents() = assertEquals("che ora e", n("Che ora è"))
    @Test fun stripsAccentsOnNumbers() = assertEquals("ventitre", n("ventitré"))
    @Test fun apostropheBecomesSpace() = assertEquals("un ora e un quarto", n("Un'ora e un quarto"))
    @Test fun typographicApostrophe() = assertEquals("mezz ora", n("mezz’ora"))
    @Test fun removesPunctuation() = assertEquals("mi dici l ora", n("Mi dici l'ora?!"))
    @Test fun collapsesWhitespace() = assertEquals("timer 30 secondi", n("  timer   30\tsecondi  "))
    @Test fun keepsDigits() = assertEquals("10 minuti", n("10 minuti."))
    @Test fun emptyInput() = assertEquals("", n("?!"))
}
