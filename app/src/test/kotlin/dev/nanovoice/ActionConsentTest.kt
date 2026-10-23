package dev.nanovoice

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActionConsentTest {
    @Test fun theAnswerGivenIsReturnedAndTheQuestionGoes() = runBlocking {
        val consent = ActionConsent()
        val answer = async { consent.ask("Sposto «Dentista» a mercoledì?") }
        val shown = consent.pending.filterNotNull().first()
        assertEquals("Sposto «Dentista» a mercoledì?", shown.question)
        consent.answer(shown.id, true)
        assertEquals(Consent.Yes, answer.await())
        assertNull(consent.pending.value)
    }

    @Test fun noAnswerInTimeIsANo() = runBlocking {
        assertEquals(Consent.NoAnswer, ActionConsent(timeoutMs = 50).ask("Cancello la cena?"))
    }

    @Test fun anInterruptedRequestRefusesAndAnOldAnswerIsIgnored() = runBlocking {
        val consent = ActionConsent()
        val first = async { consent.ask("uno") }
        val shown = consent.pending.filterNotNull().first()
        consent.refuseAll()
        assertEquals(Consent.No, first.await())
        val second = async { consent.ask("due") }
        val next = consent.pending.filterNotNull().first { it.id != shown.id }
        consent.answer(shown.id, true) // a tap on the question already gone
        assertEquals(next, consent.pending.value)
        consent.answer(next.id, false)
        assertEquals(Consent.No, second.await())
    }

    @Test fun spokenAnswers() {
        for (yes in listOf("sì", "Si.", "va bene", "ok spostalo", "sì, vai pure", "d'accordo", "certo", "approva", "cancellalo")) assertEquals(true, consentAnswer(yes), yes)
        for (no in listOf("no", "No grazie", "sì, anzi no", "lascia stare", "annulla", "non approvare", "non spostarlo")) assertEquals(false, consentAnswer(no), no)
        for (other in listOf("", "che tempo fa domani a Roma?", "non so che ore sono adesso scusa", "il meteo")) assertNull(consentAnswer(other), other)
    }

    @Test fun aChoiceReturnsTheIndexChosenAndIgnoresOneOutOfRange() = runBlocking {
        val consent = ActionConsent()
        val choice = async { consent.choose("Quale spiaggia?", listOf("Otranto", "Gallipoli", "Polignano")) }
        val shown = consent.pending.filterNotNull().first()
        assertEquals(listOf("Otranto", "Gallipoli", "Polignano"), shown.options)
        assertEquals("Quale spiaggia? Otranto, Gallipoli o Polignano", shown.spoken)
        consent.pick(shown.id, 7) // not an option: still waiting
        assertEquals(shown, consent.pending.value)
        consent.pick(shown.id, 1)
        assertEquals(1, choice.await())
        assertNull(consent.pending.value)
    }

    @Test fun anInterruptedChoiceChoosesNothingAndNoAnswerIsNull() = runBlocking {
        val consent = ActionConsent()
        val choice = async { consent.choose("Quale?", listOf("A", "B")) }
        consent.pending.filterNotNull().first()
        consent.refuseAll()
        assertEquals(ActionConsent.REFUSED, choice.await())
        assertNull(ActionConsent(timeoutMs = 50).choose("Quale?", listOf("A", "B")))
    }

    @Test fun spokenChoices() {
        val beaches = listOf("Baia dei Turchi", "Gallipoli", "Polignano a Mare")
        assertEquals(1, choiceAnswer("la seconda", beaches))
        assertEquals(0, choiceAnswer("La prima.", beaches))
        assertEquals(2, choiceAnswer("l'ultima", beaches))
        assertEquals(2, choiceAnswer("Polignano a mare", beaches))
        assertEquals(0, choiceAnswer("baia dei turchi per favore", beaches))
        assertEquals(1, choiceAnswer("gallipoli", beaches))
        assertEquals(ActionConsent.REFUSED, choiceAnswer("nessuna", beaches))
        assertEquals(ActionConsent.REFUSED, choiceAnswer("annulla", beaches))
        assertNull(choiceAnswer("la quinta", beaches)) // there are three
        assertNull(choiceAnswer("che tempo fa domani a Roma secondo te, dimmi", beaches))
        assertNull(choiceAnswer("", beaches))
        // words that fit two options fit none
        assertNull(choiceAnswer("mare", listOf("Polignano a Mare", "Torre a Mare")))
        assertNull(choiceAnswer("la prima o la seconda", beaches))
        assertNull(choiceAnswer("che ore sono secondo te", beaches)) // "secondo" here is not the second option
        assertEquals(1, choiceAnswer("seconda", beaches))
        assertEquals(1, choiceAnswer("opzione due", beaches))
    }
}
