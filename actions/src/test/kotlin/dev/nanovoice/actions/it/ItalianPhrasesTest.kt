package dev.nanovoice.actions.it

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ItalianPhrasesTest {
    private fun t(h: Int, m: Int) = ItalianPhrases.time(LocalTime.of(h, m))

    @Test fun quarterPast() = assertEquals("sono le dieci e un quarto", t(10, 15))
    @Test fun noon() = assertEquals("è mezzogiorno", t(12, 0))
    @Test fun midnight() = assertEquals("è mezzanotte", t(0, 0))
    @Test fun oneOClock() { assertEquals("è l'una", t(1, 0)); assertEquals("è l'una", t(13, 0)) }
    @Test fun fiveAfterOne() = assertEquals("è l'una e cinque", t(1, 5))
    @Test fun halfPast() = assertEquals("sono le tre e mezza", t(3, 30))
    @Test fun quarterToIsSaidAsMinutes() = assertEquals("sono le dieci e quarantacinque", t(10, 45))
    @Test fun tenToNoonIsSaidAsMinutes() = assertEquals("sono le undici e cinquanta", t(11, 50))
    @Test fun quarterToMidnightIsSaidAsMinutes() = assertEquals("sono le undici e quarantacinque", t(23, 45))
    @Test fun halfPastNoon() = assertEquals("sono le dodici e mezza", t(12, 30))
    @Test fun afternoon() = assertEquals("sono le quattro e venti", t(16, 20))
    @Test fun accentedCompound() = assertEquals("sono le otto e ventitré", t(8, 23))
    @Test fun oneMinute() = assertEquals("sono le nove e un minuto", t(9, 1))
    @Test fun fiftyNineMinutes() = assertEquals("sono le nove e cinquantanove", t(9, 59))

    @Test fun timerMinutes() = assertEquals("Ok, timer impostato per dieci minuti.", ItalianPhrases.timerSet(10.minutes))
    @Test fun timerOneHour() = assertEquals("Ok, timer impostato per un'ora.", ItalianPhrases.timerSet(1.hours))
    @Test fun timerHourTwenty() =
        assertEquals("Ok, timer impostato per un'ora e venti minuti.", ItalianPhrases.timerSet(80.minutes))
    @Test fun timerMinuteThirty() =
        assertEquals("Ok, timer impostato per un minuto e trenta secondi.", ItalianPhrases.timerSet(90.seconds))
    @Test fun timerThreeParts() = assertEquals(
        "Ok, timer impostato per un'ora, due minuti e tre secondi.",
        ItalianPhrases.timerSet(1.hours + 2.minutes + 3.seconds),
    )

    @Test fun theExampleFromTheUser() = assertEquals("sono le dodici e quarantanove", t(12, 49))
    @Test fun oneOClockWithMinutes() = assertEquals("è l'una e quarantanove", t(13, 49))
    @Test fun midnightWithMinutes() = assertEquals("è mezzanotte e trentasei", t(0, 36))
    @Test fun neverUsesTheMenoForm() {
        for (h in 0..23) for (m in 0..59) {
            assertTrue(!ItalianPhrases.time(LocalTime.of(h, m)).contains("meno"), "$h:$m")
        }
    }

    private fun d(y: Int, m: Int, day: Int) = ItalianPhrases.date(java.time.LocalDate.of(y, m, day))

    @Test fun dateInWords() = assertEquals("oggi è lunedì dodici ottobre", d(2026, 10, 12))
    @Test fun firstOfTheMonthIsPrimo() = assertEquals("oggi è giovedì primo gennaio", d(2026, 1, 1))
    @Test fun twentyThreeKeepsItsAccent() = assertEquals("oggi è lunedì ventitré marzo", d(2026, 3, 23))
    @Test fun sundayAndDecember() = assertEquals("oggi è domenica trentuno dicembre", d(2026, 12, 31).replace("giovedì", "domenica"))
    @Test fun everyDayOfTheYearHasAWeekdayAndAMonth() {
        var date = java.time.LocalDate.of(2026, 1, 1)
        repeat(366) {
            val s = ItalianPhrases.date(date)
            assertTrue(Regex("oggi è (lunedì|martedì|mercoledì|giovedì|venerdì|sabato|domenica) \\S+ [a-z]+").matches(s), s)
            date = date.plusDays(1)
        }
    }

    @Test fun alarmHalfPast() = assertEquals("Ok, sveglia alle sette e mezza.", ItalianPhrases.alarmSet(7, 30, false))
    @Test fun alarmWholeHour() = assertEquals("Ok, sveglia alle otto.", ItalianPhrases.alarmSet(8, 0, false))
    @Test fun alarmOneOClock() = assertEquals("Ok, sveglia all'una e dieci.", ItalianPhrases.alarmSet(1, 10, false))
    @Test fun alarmNoon() = assertEquals("Ok, sveglia a mezzogiorno.", ItalianPhrases.alarmSet(12, 0, false))
    @Test fun alarmMidnightWithMinutes() = assertEquals("Ok, sveglia a mezzanotte e cinque.", ItalianPhrases.alarmSet(0, 5, false))
    @Test fun alarmEveningUsesTheTwentyFourHourWord() = assertEquals("Ok, sveglia alle diciannove e un quarto.", ItalianPhrases.alarmSet(19, 15, false))
    @Test fun alarmTomorrowCaveat() {
        val s = ItalianPhrases.alarmSet(7, 0, ringsToday = true)
        assertTrue(s.startsWith("Ok, sveglia alle sette.") && s.contains("oggi"), s)
    }
    @Test fun everyAlarmTimeHasAPhrase() {
        for (h in 0..23) for (m in 0..59) assertTrue(ItalianPhrases.alarmSet(h, m, false).startsWith("Ok, sveglia"), "$h:$m")
    }
}
