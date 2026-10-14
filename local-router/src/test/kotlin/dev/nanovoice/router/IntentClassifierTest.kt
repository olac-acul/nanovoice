package dev.nanovoice.router

import dev.nanovoice.router.it.ItalianIntentClassifier
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class IntentClassifierTest {
    private val router = LocalRouter.forLocale(Locale.ITALIAN)!!

    /** The Kotlin features and maths give the same probabilities as the Python trainer. */
    @Test fun matchesThePythonModel() {
        val lines = javaClass.getResourceAsStream("/intent-parity.tsv")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 5)
        for (line in lines) {
            val parts = line.split('\t')
            val expected = parts.drop(1).map { it.toFloat() }
            val got = ItalianIntentClassifier.probabilities(parts[0])
            expected.forEachIndexed { i, e -> assertEquals(e, got[i], 1e-4f, "${parts[0]} [$i]") }
        }
    }

    @Test fun normalisesLikeTheTrainer() {
        assertEquals("un ora e un quarto e gia tardi", ItalianIntentClassifier.normalize("Un'ora e un quarto? È già tardi!"))
    }

    @Test fun freelyWordedLocalRequestsAreRecognised() {
        assertEquals(LocalIntent.SetTimer(3.minutes), router.route("fai partire sul telefono un conto alla rovescia di tre minuti")?.intent)
        assertEquals(LocalIntent.SetAlarm(ClockTime(6, 30), tomorrow = true), router.route("domattina svegliami alle sei e mezza")?.intent)
        assertEquals(LocalIntent.SetTimer(5.minutes), router.route("ehi mi metti un countdown di cinque minuti per favore")?.intent)
    }

    @Test fun negatedCancellingMovingOrAskingNeverSetsAnything() {
        for (text in listOf(
            "non mettere la sveglia alle sette",
            "annulla il timer di cinque minuti",
            "sposta la sveglia delle sette alle otto",
            "quanto manca al timer di dieci minuti",
            "ho una sveglia alle sette?",
            "spegni la sveglia delle sei e mezza",
        )) {
            val intent = router.route(text)?.intent
            assertTrue(intent !is LocalIntent.SetAlarm && intent !is LocalIntent.SetTimer, "$text -> $intent")
        }
    }

    @Test fun theArticleUnaIsNotTheHour() {
        assertEquals(LocalIntent.SetAlarm(ClockTime(7, 0), tomorrow = false), router.route("mi serve una sveglia alle sette")?.intent)
    }

    @Test fun timeAndDateElsewhereGoToTheAgent() {
        for (text in listOf("che ore sono a new york", "che giorno è il concerto", "che ore sono in giappone adesso")) {
            val intent = router.route(text)?.intent
            assertTrue(intent != LocalIntent.TellTime && intent != LocalIntent.TellDate, "$text -> $intent")
        }
        assertEquals(LocalIntent.TellTime, router.route("che ore sono adesso")?.intent)
    }

    @Test fun musicControlsAndAnnotazioniAreNotRequestsToPlayOrNote() {
        assertTrue(router.route("metti la musica in pausa")?.intent !is LocalIntent.PlayMusic)
        assertTrue(router.route("annotazioni del corso di fisica")?.intent !is LocalIntent.Note)
        assertTrue(router.route("annotati questa cosa")?.intent !is LocalIntent.Note)
        assertEquals(LocalIntent.Note("Comprare il latte"), router.route("annota comprare il latte")?.intent)
    }

    @Test fun lookalikesStillGoToTheAgent() {
        assertNull(router.route("che ore sono a tokyo"))
        assertNull(router.route("ricordami di comprare il latte alle cinque"))
        assertNull(router.route("leggimi le email"))
        assertNull(router.route("qual è la capitale del burundi"))
    }

    @Test fun aTimerWithoutADurationIsNotGuessed() {
        assertNull(router.route("fai partire un conto alla rovescia"))
    }

    @Test fun aDurationOrATimeAloneIsNotARequest() {
        assertNull(router.route("cinque minuti"))
        assertNull(router.route("alle sette"))
        assertNull(router.route("ok tra cinque minuti"))
    }

    @Test fun anAlarmOnADayTheClockCannotSetIsNotGuessed() {
        assertNull(router.route("venerdì svegliami alle sette"))
    }
}
