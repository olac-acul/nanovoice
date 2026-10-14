package dev.nanovoice.router.it

import dev.nanovoice.router.ClockTime
import dev.nanovoice.router.TextNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItalianClockTimeParserTest {
    private fun tokens(s: String) = TextNormalizer.normalize(s).split(" ").filter { it.isNotEmpty() }
    private fun at(s: String): ClockTime? = ItalianClockTimeParser.parse(tokens(s), 0)?.time
    private fun end(s: String): Int? = ItalianClockTimeParser.parse(tokens(s), 0)?.endIndex

    @Test fun wholeHourInWords() = assertEquals(ClockTime(7, 0), at("sette"))
    @Test fun wholeHourInDigits() = assertEquals(ClockTime(7, 0), at("7"))
    @Test fun halfPast() = assertEquals(ClockTime(7, 30), at("sette e mezza"))
    @Test fun halfPastMezzo() = assertEquals(ClockTime(7, 30), at("sette e mezzo"))
    @Test fun quarterPast() = assertEquals(ClockTime(7, 15), at("sette e un quarto"))
    @Test fun threeQuartersPast() = assertEquals(ClockTime(7, 45), at("sette e tre quarti"))
    @Test fun minutesInWords() = assertEquals(ClockTime(7, 20), at("sette e venti"))
    @Test fun minutesInDigits() = assertEquals(ClockTime(7, 20), at("7 e 20"))
    @Test fun minutesWithUnitWord() = assertEquals(ClockTime(7, 20), at("sette e venti minuti"))
    @Test fun colonForm() = assertEquals(ClockTime(7, 30), at("7:30"))
    @Test fun colonFormWithLeadingZero() = assertEquals(ClockTime(7, 5), at("7:05"))
    @Test fun dotForm() = assertEquals(ClockTime(18, 45), at("18.45"))
    @Test fun twentyFourHourWords() = assertEquals(ClockTime(19, 30), at("diciannove e trenta"))
    @Test fun oneAsUna() = assertEquals(ClockTime(1, 0), at("una"))
    @Test fun inPunto() = assertEquals(ClockTime(8, 0), at("otto in punto"))
    @Test fun noon() = assertEquals(ClockTime(12, 0), at("mezzogiorno"))
    @Test fun midnight() = assertEquals(ClockTime(0, 0), at("mezzanotte"))
    @Test fun twentyFourIsMidnight() = assertEquals(ClockTime(0, 0), at("24"))

    @Test fun quarterToHour() = assertEquals(ClockTime(7, 45), at("otto meno un quarto"))
    @Test fun minutesToHour() = assertEquals(ClockTime(7, 50), at("otto meno dieci"))
    @Test fun minutesToOne() = assertEquals(ClockTime(0, 55), at("una meno cinque"))
    @Test fun minutesToMidnightHour() = assertEquals(ClockTime(23, 45), at("zero meno un quarto"))

    @Test fun eveningAddsTwelve() = assertEquals(ClockTime(19, 0), at("sette di sera"))
    @Test fun afternoonAddsTwelve() = assertEquals(ClockTime(15, 30), at("tre e mezza del pomeriggio"))
    @Test fun morningKeepsTheHour() = assertEquals(ClockTime(7, 0), at("sette di mattina"))
    @Test fun noonInTheMorningStaysNoon() = assertEquals(ClockTime(0, 0), at("dodici di mattina"))
    @Test fun nightEvening() = assertEquals(ClockTime(22, 0), at("dieci di notte"))
    @Test fun nightSmallHours() = assertEquals(ClockTime(3, 0), at("tre di notte"))
    @Test fun twentyFourHourIgnoresTheQualifier() = assertEquals(ClockTime(19, 0), at("diciannove di sera"))

    @Test fun consumesOnlyTheTime() {
        assertEquals(3, end("sette e mezza domani"))
        assertEquals(1, end("sette domani"))
        assertEquals(5, end("sette e mezza di sera"))
    }
    @Test fun anEAfterTheHourWithoutMinutesIsLeftAlone() = assertEquals(1, end("sette e poi"))

    @Test fun notTimes() {
        assertNull(at("ciao"))
        assertNull(at("trentacinque"))
        assertNull(at("25"))
        assertNull(at(""))
    }
    @Test fun invalidMinutesAreIgnored() = assertEquals(ClockTime(7, 0), at("sette e settanta"))

    @Test fun findLocatesTheTimeInsideASentence() {
        val f = ItalianClockTimeParser.find(tokens("svegliami alle sette e mezza"))
        assertEquals(ClockTime(7, 30), f?.time)
    }
}
