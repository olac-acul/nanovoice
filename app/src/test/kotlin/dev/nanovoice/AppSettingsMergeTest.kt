package dev.nanovoice

import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsMergeTest {
    @Test fun onlyWhatTheScreenChangedIsWrittenOverTheCurrentSettings() {
        val atOpen = AppSettings(wakeWord = true, speakReplies = true)
        // Meanwhile the wake word was turned off from its notification.
        val now = atOpen.copy(wakeWord = false)
        // The screen still holds wakeWord = true, and the person turned replies aloud off.
        val edited = atOpen.copy(speakReplies = false)
        val saved = now.withChanges(atOpen, edited)
        assertEquals(false, saved.wakeWord)
        assertEquals(false, saved.speakReplies)
    }
}
