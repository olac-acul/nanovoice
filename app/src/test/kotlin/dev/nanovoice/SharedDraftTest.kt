package dev.nanovoice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedDraftTest {
    @Test fun aSharedPageComesAsItsTitleAndAddressReadyForAQuestion() {
        assertEquals(
            "Notizie del giorno\nhttps://example.test/articolo\n",
            sharedDraft("", "https://example.test/articolo", "Notizie del giorno"),
        )
    }

    @Test fun theTitleIsNotRepeatedWhenTheTextHasIt() {
        assertEquals(
            "Notizie del giorno https://example.test/a\n",
            sharedDraft("", "Notizie del giorno https://example.test/a", "Notizie del giorno"),
        )
    }

    @Test fun whatWasAlreadyWrittenStaysAbove() {
        assertEquals("Riassumi:\nun passaggio\n", sharedDraft("Riassumi:  ", "un passaggio", null))
    }

    @Test fun nothingSharedChangesNothingAndALongTextIsCut() {
        assertNull(sharedDraft("bozza", "   ", "titolo"))
        assertEquals(4_001, sharedDraft("", "x".repeat(10_000), null)!!.length)
    }
}

class FirstQuotedTest {
    @Test fun theFirstQuotedExampleOfAHelpText() {
        assertEquals("Svegliami alle sette e mezza", firstQuoted("“Svegliami alle sette e mezza”, “metti una sveglia domani alle sei”"))
        assertEquals("Chiama Mario", firstQuoted("“Chiama Mario”: cerco il numero in rubrica."))
        assertNull(firstQuoted("Tocca la fotocamera accanto al cerchio."))
    }
}
