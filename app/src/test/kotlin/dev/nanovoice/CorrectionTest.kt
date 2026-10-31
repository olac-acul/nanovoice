package dev.nanovoice

import kotlin.test.Test
import kotlin.test.assertEquals

class CorrectionTest {
    private fun user(id: Long, text: String) = ChatMessage(id, text, fromUser = true)
    private fun agent(id: Long, text: String) = ChatMessage(id, text, fromUser = false)

    @Test fun theCorrectedRequestGoesWithItsAnswerButNotWithItsAlerts() {
        val chat = listOf(
            user(1, "Che tempo fa a Roma?"),
            agent(2, "Sole, 22 gradi."),
            user(3, "Metti un timer di 5 minuti"),
            agent(4, "Ok, timer da 5 minuti."),
            ChatMessage(5, "Recap di Reddit", fromUser = false, alertId = 7),
        )
        assertEquals(listOf(1L, 2L, 5L), withoutRequest(chat, 3).map { it.id })
        assertEquals(chat, withoutRequest(chat, 42))
    }

    @Test fun theAgentIsToldWhatTheCorrectionReplaces() {
        assertEquals("[corregge: «Metti un timer di 5 minuti»]\nMetti un timer di 15 minuti", agentText("Metti un timer di 15 minuti", null, null, corrects = "Metti un timer di 5 minuti"))
        assertEquals(
            "[corregge: «che cos'è»]\n[in risposta a: «Recap»]\nche cosa vuol dire\n[codice a barre: 123, tipo: EAN_13]",
            agentText("che cosa vuol dire", "Recap", "[codice a barre: 123, tipo: EAN_13]", corrects = "che cos'è"),
        )
        assertEquals("[codice a barre: 123, tipo: EAN_13]", agentText("", null, "[codice a barre: 123, tipo: EAN_13]"))
        assertEquals("Ciao", agentText("Ciao", null, null))
    }
}
