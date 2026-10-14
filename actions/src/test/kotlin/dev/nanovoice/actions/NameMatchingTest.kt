package dev.nanovoice.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NameMatchingTest {
    private fun best(q: String, vararg labels: String) = NameMatching.best(q, labels.toList()) { it }

    @Test fun peopleMatchWholeWordsOnly() {
        assertEquals(emptyList(), NameMatching.best("anna", listOf("Annalisa Verdi"), partialWords = false) { it })
        assertEquals(listOf("Anna Verdi"), NameMatching.best("anna", listOf("Annalisa Verdi", "Anna Verdi"), partialWords = false) { it })
        assertEquals(listOf("Mario Rossi"), NameMatching.best("mario", listOf("Mario Rossi", "Marione"), partialWords = false) { it })
    }

    @Test fun exactMatchWins() = assertEquals(listOf("Signal"), best("signal", "Signal", "Signal Beta"))
    @Test fun ignoresCaseAndAccents() = assertEquals(listOf("Università"), best("universita", "Università", "Universe"))
    @Test fun aSingleNameMatchesTheFullContact() = assertEquals(listOf("Mario Rossi"), best("mario", "Mario Rossi", "Paolo Bianchi"))
    @Test fun twoContactsWithTheSameFirstNameAreBothReturned() =
        assertEquals(listOf("Mario Rossi", "Mario Bianchi"), best("mario", "Mario Rossi", "Mario Bianchi", "Paolo Verdi"))
    @Test fun fullNameDisambiguates() = assertEquals(listOf("Mario Rossi"), best("mario rossi", "Mario Rossi", "Mario Bianchi"))
    @Test fun surnameFirstWorksToo() = assertEquals(listOf("Mario Rossi"), best("rossi", "Mario Rossi", "Paolo Bianchi"))
    @Test fun italianNameForAnEnglishApp() = assertEquals(listOf("Camera"), best("fotocamera", "Camera", "Calendar", "Clock"))
    @Test fun aliasForClock() = assertEquals(listOf("Clock"), best("orologio", "Camera", "Clock"))
    @Test fun prefixMatches() = assertEquals(listOf("Delta Chat"), best("delta", "Delta Chat", "Briar"))
    @Test fun oneWrongLetterInALongWordIsTolerated() = assertEquals(listOf("Alessandro Neri"), best("alesandro", "Alessandro Neri", "Paolo Rossi"))
    @Test fun marioAndMariaAreDifferentPeople() = assertEquals(emptyList(), best("mario", "Maria Rossi"))
    @Test fun francescoAndFrancescaAreDifferentPeople() = assertEquals(emptyList(), best("francesco", "Francesca Neri"))
    @Test fun shortWordsAreNotFuzzy() = assertEquals(emptyList(), best("gino", "Gina Bianchi"))
    @Test fun nothingMatches() = assertEquals(emptyList(), best("zzzz", "Signal", "Briar"))
    @Test fun emptyQueryMatchesNothing() = assertEquals(0, NameMatching.score("", "Signal"))
    @Test fun scoresAreOrdered() {
        assertTrue(NameMatching.score("mario", "Mario") > NameMatching.score("mario", "Mario Rossi"))
        assertTrue(NameMatching.score("mario", "Mario Rossi") > NameMatching.score("mario", "Marione"))
    }
    @Test fun levenshteinBasics() {
        assertEquals(0, NameMatching.levenshtein("ciao", "ciao"))
        assertEquals(1, NameMatching.levenshtein("ciao", "ciau"))
        assertEquals(1, NameMatching.levenshtein("ciao", "cia"))
        assertEquals(2, NameMatching.levenshtein("ciao", "ci"))
    }
}
