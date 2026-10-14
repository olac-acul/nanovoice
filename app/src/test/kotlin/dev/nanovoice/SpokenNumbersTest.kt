package dev.nanovoice

import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenNumbersTest {
    @Test fun slashDatesGetTheMonthName() {
        assertEquals("Il 21 ottobre 2026.", spokenNumbers("Il 21/10/2026."))
        assertEquals("Ci vediamo il 3 marzo.", spokenNumbers("Ci vediamo il 3/3."))
        assertEquals("Scade il 5 gennaio 2027", spokenNumbers("Scade il 5/1/27"))
    }

    @Test fun thingsThatAreNotDatesStay() {
        assertEquals("Il rapporto è 45/60.", spokenNumbers("Il rapporto è 45/60."))
        assertEquals("Sono le 12:49, costa 1.250 euro.", spokenNumbers("Sono le 12:49, costa 1.250 euro."))
    }

    @Test fun ordinalsAfterAnArticleAreWords() {
        assertEquals("Abito al terzo piano.", spokenNumbers("Abito al 3° piano."))
        assertEquals("È la prima volta.", spokenNumbers("È la 1ª volta."))
        assertEquals("Il primo classificato.", spokenNumbers("Il 1° classificato."))
    }

    @Test fun degreesStayDegrees() {
        assertEquals("Fuori ci sono 25°.", spokenNumbers("Fuori ci sono 25°."))
    }
}
