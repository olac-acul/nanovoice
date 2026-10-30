package dev.nanovoice.actions.it

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FreshStartTest {
    @Test fun theGreetingFitsTheHour() {
        // Over many draws each hour's greeting turns up, and never another hour's.
        val seen = (0 until 400).map { ItalianPhrases.freshStart(9, Random(it)) }.toSet()
        assertTrue("Buongiorno, si riparte." in seen)
        assertTrue(seen.none { it.startsWith("Buonasera") || it.startsWith("Buon pomeriggio") || it.startsWith("Si fa tardi") })
        val night = (0 until 400).map { ItalianPhrases.freshStart(2, Random(it)) }.toSet()
        assertTrue("Si fa tardi: ripartiamo da qui." in night)
    }

    @Test fun itVariesAndIsShort() {
        val lines = (0 until 400).map { ItalianPhrases.freshStart(15, Random(it)) }.toSet()
        assertEquals(12, lines.size)
        assertTrue(lines.all { it.length <= 40 && '\n' !in it })
    }
}
