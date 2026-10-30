package dev.nanovoice.dictation

import kotlin.test.Test
import kotlin.test.assertEquals

class DictatedTextTest {
    @Test fun anEmptyFieldGetsTheWordsAlone() =
        assertEquals("Ci vediamo alle otto", dictatedText(" ci vediamo alle otto ", before = "", capital = true))

    @Test fun aWordBeforeIsKeptApart() =
        assertEquals(" e porta il pane", dictatedText("e porta il pane", before = "sera", capital = false))

    @Test fun aSpaceBeforeIsNotDoubled() =
        assertEquals("porta il pane", dictatedText("porta il pane", before = " ", capital = false))

    @Test fun aNewSentenceStartsCapitalised() =
        assertEquals(" Porta il pane", dictatedText("porta il pane", before = ".", capital = true))

    @Test fun noTextBeforeTheCursorIsLikeAnEmptyField() =
        assertEquals("porta il pane", dictatedText("porta il pane", before = null, capital = false))
}
