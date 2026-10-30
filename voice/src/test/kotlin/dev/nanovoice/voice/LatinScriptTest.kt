package dev.nanovoice.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LatinScriptTest {
    @Test fun italianWithAccentsAndPunctuationIsLatin() = assertTrue(inLatinScript("Perché non è già l'una? Sì, 3 caffè."))

    @Test fun cyrillicIsNot() = assertFalse(inLatinScript("Привет, как дела"))

    @Test fun oneCyrillicWordInItalianIsNot() = assertFalse(inLatinScript("ci vediamo да domani"))
}
