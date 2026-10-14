package dev.nanovoice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** Builds a [ChatViewModel] wired to the app container: used by the main screen and the assistant sheet. */
fun chatViewModelFactory(container: AppContainer) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
        engine = container.newChatEngine(),
        connection = container.connection,
        spokenOutput = container.spokenOutput,
        phrases = container.phrases,
        settings = container.settingsStore.settings,
        stt = container.stt,
        linkPreviews = container.linkPreviews,
        consent = container.consent,
    ) as T
}
