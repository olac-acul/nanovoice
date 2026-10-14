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
}
