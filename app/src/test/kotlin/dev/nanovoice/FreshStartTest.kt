package dev.nanovoice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreshStartTest {
    private fun user(id: Long, earlier: Boolean = false) = ChatMessage(id, "domanda", fromUser = true, earlier = earlier)
    private fun agent(id: Long, earlier: Boolean = false) = ChatMessage(id, "risposta", fromUser = false, earlier = earlier)
    private fun headline(id: Long) = ChatMessage(id, "Ricomincio da tre.", fromUser = false, notice = true)
    private fun alertLine(id: Long) = ChatMessage(id, "Recap", fromUser = false, alertId = id)

    @Test fun aConversationOnScreenIsFoldedOnceWhenThePauseIsOver() {
        val chat = listOf(user(1), agent(2))
        assertTrue(hasNewsBefore(chat, chat.size))
        // folded at the end of the pause: the request that follows finds the headline and adds none
        val folded = listOf(user(1, earlier = true), agent(2, earlier = true), headline(3), alertLine(4), user(5))
        assertFalse(hasNewsBefore(folded, 4))
        assertFalse(hasNewsBefore(folded.take(4), 4))
        // a new conversation under the headline is folded at the next pause
        assertTrue(hasNewsBefore(folded + agent(6), 6))
    }

    @Test fun nothingToFoldInAnEmptyChatOrUnderAlertsAlone() {
        assertFalse(hasNewsBefore(emptyList(), 0))
        assertFalse(hasNewsBefore(listOf(alertLine(1), alertLine(2)), 2))
        // the request that came after the pause is not part of what is folded
        assertFalse(hasNewsBefore(listOf(user(1)), 0))
    }

    @Test fun theGreetingOfAConversationFoldedAwayGoes() {
        val chat = listOf(user(1, earlier = true), headline(2), user(3), agent(4))
        val folded = foldedForANewConversation(chat, chat.size) { headline(5) }
        assertEquals(listOf(1L, 3L, 4L, 5L), folded.map { it.id })
        assertEquals(listOf(true, true, true, false), folded.map { it.earlier })
        // a chat saved before they went: dropped when it is loaded, the current one stays
        val saved = listOf(user(1, earlier = true), headline(2).copy(earlier = true), user(3, earlier = true), headline(5))
        assertEquals(listOf(1L, 3L, 5L), withoutEarlierHeadlines(saved).map { it.id })
    }

    @Test fun aNewConversationFoldsWhatCameBeforeUnderItsLine() {
        val chat = listOf(user(1), agent(2))
        val folded = foldedForANewConversation(chat, chat.size) { headline(3) }
        assertEquals(listOf(true, true, false), folded.map { it.earlier })
        assertEquals(3L, folded.last().id)
        // again at once: nothing new to fold, no second line
        assertEquals(folded, foldedForANewConversation(folded, folded.size) { headline(4) })
        // the request the pause came before stays in the new conversation
        assertEquals(listOf(1L, 3L, 2L), foldedForANewConversation(chat, 1) { headline(3) }.map { it.id })
    }
}
