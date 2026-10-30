package dev.nanovoice.actions.it

import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FreshStartTest {
    private fun draws(now: LocalDateTime, last: LocalDateTime? = now.minusMinutes(40)) =
        (0 until 600).map { ItalianPhrases.freshStart(now, last, Random(it)) }.toSet()

    // A Wednesday: no line for the weekday.
    private val wednesdayMorning = LocalDateTime.of(2026, 10, 28, 9, 30)

    @Test fun aPauseFromAnEarlierDaySaysSo() {
        assertEquals(setOf("Quanto tempo! Eccomi."), draws(wednesdayMorning, wednesdayMorning.minusDays(1)))
        assertEquals(setOf("Quanto tempo! Eccomi."), draws(wednesdayMorning, wednesdayMorning.minusDays(5)))
    }

    @Test fun mostlyTheTimeOfDayNowAndThenALighterLine() {
        val morning = draws(wednesdayMorning)
        assertTrue("Buongiorno, si parte." in morning)
        assertTrue("Rieccoci, dopo una pausa." in morning)
        assertTrue("Ricominciamo da tre." in morning)
        assertTrue(morning.none { it.startsWith("Buonasera") || it.startsWith("Lunedì") || it == "Buon venerdì!" })
        val night = draws(wednesdayMorning.withHour(1))
        assertTrue("Ore piccole, eh." in night || "La notte porta consiglio." in night)
    }

    @Test fun theWeekdayOnlyOnItsDay() {
        assertTrue("Buon venerdì!" in draws(LocalDateTime.of(2026, 10, 30, 16, 0)))
        assertTrue("Finalmente weekend." in draws(LocalDateTime.of(2026, 10, 31, 11, 0)))
        assertTrue("Lunedì: si riparte." in draws(LocalDateTime.of(2026, 11, 2, 9, 0)))
    }

    @Test fun neverAQuestionThatInvitesAnAnswer() {
        val all = (0..23).flatMap { draws(LocalDateTime.of(2026, 10, 26, it, 0).plusDays((it % 7).toLong())) }
        assertTrue(all.none { '?' in it }, all.filter { '?' in it }.toString())
    }

    @Test fun shortAndOnOneLine() {
        val all = listOf(0, 6, 9, 13, 16, 20, 23).flatMap { draws(LocalDateTime.of(2026, 10, 30, it, 0)) }
        assertTrue(all.all { it.length <= 40 && '\n' !in it })
    }
}
