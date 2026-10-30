package dev.nanovoice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyActionsTest {
    private val request = ChatMessage(1, "che ore sono", fromUser = true)
    private val reply = ChatMessage(2, "Sono le 20.", fromUser = false)
    private val reminder = ChatMessage(3, "Promemoria: innaffiare le piante.", fromUser = false)

    @Test fun theReplyRightAfterTheRequestAnswersIt() = assertTrue(answers(listOf(request, reply), reply, request))

    @Test fun aMessageTheAgentSentLaterDoesNot() = assertFalse(answers(listOf(request, reply, reminder), reminder, request))
}
