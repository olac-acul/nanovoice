package dev.nanovoice.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import dev.nanovoice.NanoVoiceApp
import dev.nanovoice.wake.WakeWordService

/**
 * Marks NanoVoice as a digital assistant. When the user invokes the assistant the system opens a session
 * ([NanoVoiceSessionService]), which shows [AssistantActivity]. The wake word opens it the same way, through [show].
 */
class NanoVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        current = this
        // The system keeps this service bound, so this runs again after the app was stopped: resume the wake word.
        val settings = (application as NanoVoiceApp).container.settingsStore.settings.value
        if (settings.wakeWord) WakeWordService.apply(this, true)
    }

    override fun onShutdown() {
        if (current === this) current = null
        super.onShutdown()
    }

    companion object {
        /** The running service while NanoVoice is the default assistant, else null. */
        @Volatile private var current: NanoVoiceInteractionService? = null

        /** Opens the assistant sheet as if the user had invoked the assistant. False if NanoVoice is not the assistant. */
        fun show(): Boolean {
            val service = current ?: return false
            service.showSession(Bundle(), 0)
            return true
        }
    }
}
