package dev.nanovoice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoDetailTest {
    @Test fun questionsAboutTheWritingAskForTheLargerPhoto() {
        for (q in listOf(
            "traducimi questo", "Traduci il menu", "cosa c'è scritto?", "leggimi le istruzioni", "che ingredienti ha?",
            "è in tedesco, cosa dice?", "riassumi questo documento", "quanto ho speso in questo scontrino", "il menù",
        )) assertTrue(PhotoDetail.asksAboutText(q), q)
    }

    @Test fun otherQuestionsKeepTheNormalSize() {
        for (q in listOf("", "cos'è?", "che pianta è?", "dove lo compro?", "trova da dove viene questa immagine", "carino, vero?")) {
            assertFalse(PhotoDetail.asksAboutText(q), q)
        }
    }
}
