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

    @Test fun aYesDoesNotPickAnOptionOfAChoice() = runBlocking {
        val consent = ActionConsent()
        val choice = async { consent.choose("Quale spiaggia?", listOf("Otranto", "Gallipoli", "Porto Cesareo")) }
        val shown = consent.pending.filterNotNull().first()
        consent.answer(shown.id, true)
        assertEquals(shown, consent.pending.value) // still waiting
        consent.pick(shown.id, 2)
        assertEquals(2, choice.await())
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

    @Test fun aYesNoChoiceIsAnsweredLikeAnApproval() {
        assertEquals(0 to 1, yesNoIndexes(listOf("Sì", "No")))
        assertEquals(1 to 0, yesNoIndexes(listOf("No", "Sì")))
        assertNull(yesNoIndexes(listOf("Sì", "No", "Forse")))
        assertNull(yesNoIndexes(listOf("Sabaudia", "Gaeta")))
        // heard on the Pixel for "sì, eliminalo": the question's verb, misheard, still counts as a yes
        assertEquals(true, consentAnswer("Sì, eliminano.", "Elimino l'evento \"prova\" di lunedì 26 ottobre?"))
        assertEquals(true, consentAnswer("va bene", "Sposto l'evento?"))
    }

    @Test fun aShortAnswerThatAnswersNothingIsAskedAgain() {
        // "sì" heard as "se": not an answer, but too short to be a new request
        assertNull(consentAnswer("se", "Elimino l'evento?"))
        assertEquals(true, unclearShortAnswer("se"))
        assertEquals(true, unclearShortAnswer("Sabaudia"))
        assertEquals(false, unclearShortAnswer("quanto dista Gaeta da Roma"))
        assertEquals(false, unclearShortAnswer(""))
    }

    @Test fun spokenAnswers() {
        val move = "Sposto «Dentista» da martedì alle 10 a mercoledì alle 10?"
        for (yes in listOf("sì", "Si.", "va bene", "ok spostalo", "sì, vai pure", "d'accordo", "certo", "approva", "sì per favore"))
            assertEquals(true, consentAnswer(yes, move), yes)
        for (no in listOf("no", "No grazie", "sì, anzi no", "lascia stare", "annulla", "non approvare", "non spostarlo"))
            assertEquals(false, consentAnswer(no, move), no)
        for (other in listOf("", "che tempo fa domani a Roma?", "non so che ore sono adesso scusa", "il meteo"))
            assertNull(consentAnswer(other, move), other)
    }

    @Test fun anAnswerWithMoreInItIsANewRequestNotAYes() {
        val move = "Sposto «Dentista» da martedì alle 10 a mercoledì alle 10?"
        // these approved the move before: each says something else, so the question is refused and the agent hears it
        for (other in listOf("va bene ma alle 11", "spostalo a giovedì", "cancellalo", "sì, però alle undici", "no, spostalo a giovedì"))
            assertNull(consentAnswer(other, move), other)
        // the question's own verb agrees; another verb does not
        assertEquals(true, consentAnswer("cancellala", "Cancello «Cena» di venerdì?"))
        assertNull(consentAnswer("spostala", "Cancello «Cena» di venerdì?"))
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
        // naming or asking about an option is not choosing it
        for (s in listOf("quanto dista Gallipoli?", "non Gallipoli", "no, Gallipoli no", "Gallipoli o Otranto?")) assertNull(choiceAnswer(s, beaches), s)
        // a word only one option has is enough
        assertEquals(2, choiceAnswer("Polignano", beaches))
        assertEquals(1, choiceAnswer("scelgo Gallipoli", beaches))
        // a yes/no question asked as a choice: "sì" and "no" are its options
        val yesNo = listOf("Sì", "No")
        assertEquals(0, choiceAnswer("sì", yesNo))
        assertEquals(0, choiceAnswer("Sì, per favore", yesNo))
        assertEquals(1, choiceAnswer("no", yesNo))
        assertEquals(0, choiceAnswer("sì", listOf("Sì, spostalo", "No, lascialo")))
        assertNull(choiceAnswer("la", listOf("La Spezia", "Genova")))
        assertEquals(1, choiceAnswer("seconda", beaches))
        assertEquals(1, choiceAnswer("opzione due", beaches))
    }
}
