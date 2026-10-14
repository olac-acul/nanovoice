package dev.nanovoice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotifierTest {
    @Test fun anAgentMessageIsNotifiedUnlessTheUnlockedChatShowsIt() {
        assertTrue(Notifier.shouldNotify(appInForeground = false, locked = false))
        assertFalse(Notifier.shouldNotify(appInForeground = true, locked = false))
        // Only the sheet is up, over the lock screen: it drops agent messages, so the notification must carry them.
        assertTrue(Notifier.shouldNotify(appInForeground = true, locked = true))
        assertTrue(Notifier.shouldNotify(appInForeground = false, locked = true))
    }
}
