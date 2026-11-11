package dev.nanovoice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Builds a [ChatViewModel] wired to the app container: the main screen's chat, or (with [sheet]) the assistant sheet's
 * own conversation, which starts empty and is kept nowhere.
 */
fun chatViewModelFactory(container: AppContainer, sheet: Boolean = false) = object : ViewModelProvider.Factory {
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
        saveSettings = container.settingsStore::save,
        chatStore = container.chatStore.takeUnless { sheet },
        inbox = container.inbox.takeUnless { sheet },
        fileInbox = container.fileInbox.takeUnless { sheet },
        sheet = sheet,
    ) as T
}
