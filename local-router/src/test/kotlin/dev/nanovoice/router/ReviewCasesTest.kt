package dev.nanovoice.router

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * Sentences found in the October 2026 review, each with what it must give: a part of the day said anywhere sets the
 * evening, and statements or questions about an alarm, a timer, a call, an app or music are the agent's, not a local
 * action. A wrong local match is final (the agent never sees the request), so these stay as tests.
 */
class ReviewCasesTest {
    private val router = LocalRouter.forLocale(Locale.ITALIAN)!!
    private fun intent(s: String) = router.route(s)?.intent
    private fun alarm(h: Int, m: Int = 0, tomorrow: Boolean = false) = LocalIntent.SetAlarm(ClockTime(h, m), tomorrow)

    @Test fun aPartOfTheDayAnywhereInTheSentenceSetsTheEvening() {
        val cases = mapOf(
            "metti la sveglia stasera alle nove" to alarm(21),
            "sveglia per stasera alle 9" to alarm(21),
            "metti la sveglia alle otto stasera" to alarm(20),
            "imposta la sveglia alle nove di stasera" to alarm(21),
            "svegliami alle dieci stasera" to alarm(22),
            "metti una sveglia per domani pomeriggio alle tre" to alarm(15, tomorrow = true),
            "sveglia oggi pomeriggio alle tre" to alarm(15),
            "metti la sveglia alle sette di domani sera" to alarm(19, tomorrow = true),
            "metti la sveglia stanotte alle undici" to alarm(23),
        )
        for ((sentence, expected) in cases) assertEquals(expected, intent(sentence), sentence)
        assertEquals(LocalIntent.StopAlarm(ClockTime(21, 0)), intent("disattiva la sveglia di stasera alle nove"))
    }

    @Test fun whatWorkedBeforeStillWorks() {
        val cases = mapOf(
            "metti la sveglia alle sette" to alarm(7),
            "svegliami alle sette e mezza" to alarm(7, 30),
            "metti la sveglia alle nove di sera" to alarm(21),
            "sveglia domani alle sei" to alarm(6, tomorrow = true),
            "metti una sveglia domattina alle sei e un quarto" to alarm(6, 15, tomorrow = true),
            "metti un timer di dieci minuti" to LocalIntent.SetTimer(10.minutes),
            "timer" to LocalIntent.SetTimer(null),
            "sveglia" to LocalIntent.SetAlarm(null),
            "ho bisogno di una sveglia alle sette" to alarm(7),
            "rimanda la sveglia" to LocalIntent.SnoozeAlarm(null),
            "posticipa la sveglia di dieci minuti" to LocalIntent.SnoozeAlarm(10),
            "posticipa di dieci minuti" to LocalIntent.SnoozeAlarm(10),
            "ferma il timer" to LocalIntent.StopTimer,
            "chiama Mario Rossi" to LocalIntent.Call("mario rossi"),
            "telefona a Mario" to LocalIntent.Call("mario"),
            "chiama il 112" to LocalIntent.Call("112"),
            "fai una chiamata a Mario" to LocalIntent.Call("mario"),
            "telefona alla banca" to LocalIntent.Call("banca"),
            "apri Signal" to LocalIntent.OpenApp("signal"),
            "avvia la calcolatrice" to LocalIntent.OpenApp("calcolatrice"),
            "apri l'app della fotocamera" to LocalIntent.OpenApp("fotocamera"),
            "apri google maps" to LocalIntent.OpenApp("google maps"),
            "riproduci Bohemian Rhapsody dei Queen" to LocalIntent.PlayMusic("bohemian rhapsody queen"),
            "ehi suona un po' di Vasco Rossi per favore" to LocalIntent.PlayMusic("vasco rossi"),
            "fammi sentire una canzone dei Beatles" to LocalIntent.PlayMusic("beatles"),
        )
        for ((sentence, expected) in cases) assertEquals(expected, intent(sentence), sentence)
    }

    @Test fun statementsAndQuestionsAboutAlarmsAndTimersGoToTheAgent() {
        for (s in listOf(
            "hai messo la sveglia alle sette", "mi hai messo la sveglia alle sette", "la sveglia suona alle sette",
            "la sveglia di domani è alle sette", "ho una sveglia alle sette", "ho la sveglia alle sette",
            "ho un timer di dieci minuti", "ho impostato la sveglia alle sette", "il timer", "la sveglia",
            "c'è una sveglia alle sette",
        )) assertEquals(null, intent(s), s)
    }

    @Test fun aBareSnoozeVerbIsNotAnAlarm() {
        for (s in listOf("rimandalo", "rinvialo per favore", "rimandamelo")) assertEquals(null, intent(s), s)
        // the bare verb said to a ringing alarm, and "rimandala" (la sveglia), still snooze it
        for (s in listOf("rimanda", "posticipa", "rimandala")) assertEquals(LocalIntent.SnoozeAlarm(null), intent(s), s)
    }

    @Test fun callsAppsAndMusicThatAreNotThatGoToTheAgent() {
        for (s in listOf(
            "chiama un taxi", "chiama un'ambulanza", "chiama il numero che ti ho dato", "chiamata persa",
            "lancia una moneta", "lancia un dado", "avvia la navigazione verso casa", "apri la mappa di torino",
            "fammi sentire una barzelletta", "voglio ascoltare le notizie", "voglio ascoltare il meteo",
            "fammi sentire il messaggio di mario", "suona bene", "suona la campanella",
        )) assertEquals(null, intent(s), s)
    }
}
