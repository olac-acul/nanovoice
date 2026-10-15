package dev.nanovoice.router

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClockControlMatcherTest {
    private val router = LocalRouter.forLocale(Locale.ITALIAN)!!
    private fun intent(s: String) = router.route(s)?.intent

    @Test fun stopsTheTimer() {
        for (s in listOf("ferma il timer", "Spegni il timer!", "stop timer", "annulla il timer", "cancella il timer per favore",
            "togli il timer", "ehi, ferma il timer", "puoi fermare il timer", "disattiva il timer", "zittisci il timer")) {
            assertEquals(LocalIntent.StopTimer, intent(s), s)
        }
    }

    @Test fun stopsTheAlarmRingingNow() {
        for (s in listOf("spegni la sveglia", "ferma la sveglia", "stop sveglia", "spegni la sveglia per favore", "puoi spegnere la sveglia")) {
            assertEquals(LocalIntent.StopAlarm(null), intent(s), s)
        }
    }

    @Test fun skipsTheAlarmAtATime() {
        assertEquals(LocalIntent.StopAlarm(ClockTime(7, 0)), intent("disattiva la sveglia delle sette"))
        assertEquals(LocalIntent.StopAlarm(ClockTime(7, 30)), intent("salta la sveglia delle 7:30"))
        assertEquals(LocalIntent.StopAlarm(ClockTime(6, 45)), intent("annulla la sveglia delle sei e quarantacinque"))
        assertEquals(LocalIntent.StopAlarm(ClockTime(8, 0)), intent("cancella la sveglia di domani alle otto"))
    }

    @Test fun snoozesTheAlarm() {
        assertEquals(LocalIntent.SnoozeAlarm(null), intent("posticipa la sveglia"))
        assertEquals(LocalIntent.SnoozeAlarm(null), intent("rimanda"))
        assertEquals(LocalIntent.SnoozeAlarm(10), intent("posticipa la sveglia di dieci minuti"))
        assertEquals(LocalIntent.SnoozeAlarm(5), intent("rimanda di 5 minuti"))
    }

    @Test fun leavesQuestionsNegationsAndEverythingElseToTheAgent() {
        for (s in listOf("quando suona la sveglia?", "non fermare il timer", "ferma il timer?", "quanto manca al timer",
            "come si ferma il timer", "ferma il timer e metti la musica", "salta la sveglia", "ferma", "stop",
            "spegni la luce", "posticipa la riunione", "rimanda il timer", "posticipa la sveglia di tre ore",
            "spegni il timer e la sveglia", "ferma il timer che suona")) {
            assertNull(intent(s), s)
        }
    }

    @Test fun doesNotStealTheSettingRequests() {
        assertEquals(LocalIntent.SetAlarm(ClockTime(7, 0)), intent("metti una sveglia alle sette"))
        assertEquals(true, intent("metti un timer di cinque minuti") is LocalIntent.SetTimer)
    }
}
