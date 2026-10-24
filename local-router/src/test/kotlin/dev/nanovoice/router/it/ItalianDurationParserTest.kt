package dev.nanovoice.router.it

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ItalianDurationParserTest {
    /** The duration [s] says, only if it is all a duration. */
    private fun d(s: String): Duration? {
        val tokens = ItalianDurationParser.tokenize(s)
        return ItalianDurationParser.parse(tokens, 0)?.takeIf { it.endIndex == tokens.size }?.duration
    }

    @Test fun digitsMinutes() = assertEquals(10.minutes, d("10 minuti"))
    @Test fun wordsMinutes() = assertEquals(10.minutes, d("dieci minuti"))
    @Test fun seconds() = assertEquals(30.seconds, d("30 secondi"))
    @Test fun oneHour() = assertEquals(1.hours, d("un'ora"))
    @Test fun twoHours() = assertEquals(2.hours, d("due ore"))
    @Test fun halfHour() { assertEquals(30.minutes, d("mezz'ora")); assertEquals(30.minutes, d("mezza ora")) }
    @Test fun halfMinute() = assertEquals(30.seconds, d("mezzo minuto"))
    @Test fun minuteAndHalf() = assertEquals(90.seconds, d("un minuto e mezzo"))
    @Test fun hourAndHalf() = assertEquals(90.minutes, d("un'ora e mezza"))
    @Test fun hourAndQuarter() = assertEquals(75.minutes, d("un'ora e un quarto"))
    @Test fun hourAndThreeQuarters() = assertEquals(105.minutes, d("un'ora e tre quarti"))
    @Test fun hourAndTwenty() = assertEquals(80.minutes, d("un'ora e venti"))
    @Test fun hoursAndMinutes() = assertEquals(2.hours + 10.minutes, d("due ore e dieci minuti"))
    @Test fun hoursMinutesNoConnector() = assertEquals(1.hours + 20.minutes, d("un'ora venti minuti"))
    @Test fun hourMinutesSeconds() = assertEquals(1.hours + 2.minutes + 3.seconds, d("un ora 2 minuti e 3 secondi"))
    @Test fun quarterOfHour() = assertEquals(15.minutes, d("un quarto d'ora"))
    @Test fun threeQuartersOfHour() = assertEquals(45.minutes, d("tre quarti d'ora"))
    @Test fun minuteAndTen() = assertEquals(70.seconds, d("un minuto e dieci"))
    @Test fun compoundNumber() = assertEquals(23.minutes, d("ventitré minuti"))

    @Test fun noUnit() = assertNull(d("dieci"))
    @Test fun noNumber() = assertNull(d("minuti"))
    @Test fun wrongOrder() = assertNull(d("un minuto e due ore"))
    @Test fun halfSecondUnsupported() = assertNull(d("mezzo secondo"))
    @Test fun unrelatedText() = assertNull(d("ciao come stai"))

    @Test fun findInsideSentence() {
        val tokens = ItalianDurationParser.tokenize("metti un timer di dieci minuti per favore")
        val f = ItalianDurationParser.find(tokens)
        assertEquals(10.minutes, f?.duration)
        assertEquals(4, f?.startIndex)
        assertEquals(6, f?.endIndex)
    }
    @Test fun findNoneInSentence() =
        assertNull(ItalianDurationParser.find(ItalianDurationParser.tokenize("metti un timer")))
    @Test fun parseStopsBeforeUnrelatedE() {
        val t = ItalianDurationParser.tokenize("dieci minuti e poi dimmi l'ora")
        assertEquals(2, ItalianDurationParser.parse(t, 0)?.endIndex)
    }
}
