package dev.nanovoice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyPresenterTest {
    private fun say(text: String, working: Boolean = false) = ReplySnapshot(text, working)

    /** Plays snapshots the way the chat does and keeps what is on screen: the message of the reply and the status line. */
    private class Screen {
        val presenter = ReplyPresenter()
        var bubble: String? = null
        var status = ""
        fun feed(snap: ReplySnapshot) {
            val step = presenter.next(snap, hasBubble = bubble != null)
            if (step.removeBubble) bubble = null
            status = step.status
            step.bubbleText?.let { bubble = it }
        }
    }

    @Test fun theLineBeforeTheWorkBecomesTheStatusAndNotAMessage() {
        val screen = Screen()
        screen.feed(say("Cerco sul Web..."))            // arrives as text first
        assertEquals("Cerco sul Web...", screen.bubble) // for an instant it is a message
        screen.feed(say("Cerco sul Web...", working = true))
        assertNull(screen.bubble)                       // ...then it goes, and becomes the animated line
        assertEquals("Cerco sul Web...", screen.status)
    }

    @Test fun theAnswerThatFollowsIsAMessageWithoutTheLine() {
        val screen = Screen()
        screen.feed(say("Cerco sul Web..."))
        screen.feed(say("Cerco sul Web...", working = true))
        screen.feed(say("Cerco sul Web... Ecco cosa ho trovato."))
        assertEquals("Ecco cosa ho trovato.", screen.bubble)
        assertEquals("", screen.status)
    }

    @Test fun aSecondWorkingKeepsTheAnswerOnScreen() {
        val screen = Screen()
        screen.feed(say("Cerco..."))
        screen.feed(say("Cerco...", working = true))
        screen.feed(say("Cerco... Prima parte."))
        screen.feed(say("Cerco... Prima parte.", working = true)) // more work
        assertEquals("Prima parte.", screen.bubble)
        assertEquals("", screen.status)
        screen.feed(say("Cerco... Prima parte. Seconda parte."))
        assertEquals("Prima parte. Seconda parte.", screen.bubble)
    }

    @Test fun twoWorkingsBeforeAnyAnswerUpdateTheStatus() {
        val screen = Screen()
        screen.feed(say("Cerco...", working = true))
        screen.feed(say("Cerco... Controllo la posta...", working = true))
        assertNull(screen.bubble)
        assertEquals("Cerco... Controllo la posta...", screen.status)
    }

    @Test fun aWorkingWithNoLineShowsOnlyTheDots() {
        val screen = Screen()
        screen.feed(say("", working = true))
        assertEquals("", screen.status)
        assertNull(screen.bubble)
        screen.feed(say("Fatto."))
        assertEquals("Fatto.", screen.bubble)
        screen.feed(say("Fatto.", working = true)) // later work must not take the answer away
        assertEquals("Fatto.", screen.bubble)
    }

    @Test fun anAnswerWithoutAnyWorkingIsJustAMessage() {
        val screen = Screen()
        screen.feed(say("Ciao."))
        screen.feed(say("Ciao. Come stai?"))
        assertEquals("Ciao. Come stai?", screen.bubble)
        assertTrue(screen.status.isEmpty())
    }
}
