package dev.nanovoice

import androidx.compose.ui.graphics.ImageBitmap
import dev.nanovoice.agent.AgentImage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nanovoice.actions.SpokenPhrases
import dev.nanovoice.voice.SpeechToText
import dev.nanovoice.voice.SpokenOutput
import dev.nanovoice.voice.TtsStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [photo] is what was sent, for a reverse image search from the bubble; [image] its preview. [time] is when it was
 * written (epoch ms); [quote] the start of the message this one answers ("Rispondi"), shown above it.
 */
data class ChatMessage(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
    val image: ImageBitmap? = null,
    val photo: AgentImage? = null,
    val time: Long = System.currentTimeMillis(),
    val quote: String? = null,
)

class ChatViewModel(
    private val engine: ChatEngine,
    private val connection: AgentConnection,
    private val spokenOutput: SpokenOutput,
    private val phrases: SpokenPhrases,
    private val settings: StateFlow<AppSettings>,
    stt: SpeechToText,
    val linkPreviews: LinkPreviews,
    private val consent: ActionConsent,
) : ViewModel() {
    /** The settings, for the screens that depend on them (link previews). */
    val appSettings: StateFlow<AppSettings> get() = settings

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    val status: StateFlow<AgentStatus> = connection.status

    private val _thinking = MutableStateFlow(false)

    private val active = MutableStateFlow(0)

    /** A request is still being answered: the screen offers to interrupt it. */
    val busy: StateFlow<Boolean> = active.map { it > 0 }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** A spoken request is waiting for its reply. */
    val thinking: StateFlow<Boolean> = _thinking

    /** The agent is working on a slow request and will answer in the same reply. */
    private val _working = MutableStateFlow(false)
    private val _workingText = MutableStateFlow("")

    val working: StateFlow<Boolean> = _working

    /** The line to show while [working], e.g. "Cerco sul Web...". */
    val workingText: StateFlow<String> = _workingText

    val speaking: StateFlow<Boolean> = spokenOutput.speaking

    val ttsStatus: StateFlow<TtsStatus> = spokenOutput.status

    private val _photo = MutableStateFlow<PreparedPhoto?>(null)

    /** The photo that goes with the next message, typed or spoken. */
    val photo: StateFlow<PreparedPhoto?> = _photo

    fun attachPhoto(photo: PreparedPhoto?) {
        _photo.value = photo
    }

    private val _barcode = MutableStateFlow<String?>(null)

    /** A product barcode from the scanner, for the next message (Open Food Facts gets only the number). */
    val barcode: StateFlow<String?> = _barcode

    fun attachBarcode(code: String?) {
        _barcode.value = code
    }

    /** Listens for a request, and lets the server start the agent meanwhile (the local router may not need it). */
    fun startListening() {
        if (!isLocked()) connection.warmUp()
        voice.listen()
    }

    /** The keyboard was opened: a typed request is coming, the agent may start meanwhile. */
    fun startTyping() {
        if (!isLocked()) connection.warmUp()
    }

    /** Tap to talk: what is heard becomes a normal message, answered aloud, or the answer to a question of [consent]. */
    val voice = VoiceController(viewModelScope, { stt }, spokenOutput, ::heard)

    /** The agent's question before a change (a calendar event moved or deleted): shown with Approva and Non approvare. */
    val consentRequest: StateFlow<ConsentRequest?> = consent.pending

    fun answerConsent(id: Long, yes: Boolean) = consent.answer(id, yes)

    /** The request being answered was spoken: a question about it is asked aloud too, and answered by voice. */
    private var spokenRequest = false

    /** The question asked aloud, whose answer the next utterance is. */
    private var askedAloud: Long? = null

    private fun heard(text: String) {
        askedAloud = null
        send(text, spoken = true)
    }

    /** Says the question and then listens for the answer, once the voice has finished. */
    private suspend fun askAloud(question: ConsentRequest) {
        askedAloud = question.id
        spokenOutput.speak(question.headline)
        withTimeoutOrNull(SPEECH_START_MS) { spokenOutput.speaking.first { it } }
        spokenOutput.speaking.first { !it }
        if (consent.pending.value?.id == question.id && !voice.state.value.listening) voice.listen()
    }

    /** What a tap on the orb does now. */
    val orbAction: StateFlow<OrbAction> = combine(voice.state, busy, spokenOutput.speaking, ::orbActionFor)
        .stateIn(viewModelScope, SharingStarted.Eagerly, OrbAction.Listen)

    /**
     * One tap starts listening; a pause or a second tap stops it. From then until the reply has been read out, a tap
     * cancels it all.
     */
    fun orbTap() {
        when (orbActionFor(voice.state.value, busy.value, spokenOutput.speaking.value)) {
            OrbAction.Listen -> startListening()
            OrbAction.Finish -> voice.finish()
            OrbAction.Cancel -> {
                voice.cancel()
                interrupt()
                spokenOutput.stop()
            }
        }
    }

    private var nextId = 0L

    /** Set by the assistant sheet: true while the phone is locked, so only local requests are answered. */
    var isLocked: () -> Boolean = { false }

    /** Parent of the replies being written: cancelled by a new conversation so no old answer lands in the new one. */
    private var conversation = Job(viewModelScope.coroutineContext[Job])

    init {
        // On the lock screen the sheet must not show what the agent sends: the system notification still arrives.
        viewModelScope.launch { connection.notifications.collect { if (!isLocked() && !repeatsLastReply(it)) add(it, fromUser = false) } }
        // Only the screen whose spoken request is open asks aloud; the other one just shows the question.
        viewModelScope.launch {
            consent.pending.collectLatest { question ->
                if (question != null && busy.value && spokenRequest && settings.value.speakReplies) {
                    askAloud(question)
                } else if (question == null && askedAloud != null) {
                    // Answered with a tap, or expired: the microphone opened for the answer is not needed any more.
                    askedAloud = null
                    voice.cancel()
                }
            }
        }
    }

    private val _replyingTo = MutableStateFlow<ChatMessage?>(null)

    /** The message the next one answers ("Rispondi" in its menu), shown above the input until sent or dismissed. */
    val replyingTo: StateFlow<ChatMessage?> = _replyingTo

    fun replyTo(message: ChatMessage?) {
        _replyingTo.value = message
    }

    /** "Riprova" on the last request: the same words (and photo) again, as a new request. */
    fun retry(message: ChatMessage) {
        if (!message.fromUser) return
        submit(message.text, message.photo, message.image, barcode = null, spoken = false, quote = message.quote)
    }

    /** "Rileggi ad alta voce": the message said again, as a reply is (no links, no Markdown symbols). */
    fun readAloud(message: ChatMessage) {
        spokenOutput.stop()
        ReplySpeaker(spokenOutput).apply {
            onSnapshot(message.text)
            finish()
        }
    }

    fun send(text: String, spoken: Boolean = false) {
        val trimmed = text.trim()
        val photo = _photo.value
        val barcode = _barcode.value ?: photo?.barcode
        if (trimmed.isEmpty() && photo == null && barcode == null) return
        // While the agent waits for an approval, a short "sì" or "no", said or typed, is the answer. Anything else is a
        // new request, which replaces the one waiting (and so does not approve).
        consent.pending.value?.let { question ->
            val answer = if (photo == null && barcode == null) consentAnswer(trimmed) else null
            if (answer != null) return consent.answer(question.id, answer)
        }
        // A question about the writing needs the larger copy; otherwise the lighter one.
        val sent = photo?.let { if (PhotoDetail.asksAboutText(trimmed)) it.detail else it.image }
        _photo.value = null
        _barcode.value = null
        val quote = _replyingTo.value?.let { quoteOf(it) }
        _replyingTo.value = null
        submit(trimmed, sent, photo?.preview, barcode, spoken, quote)
    }

    private fun submit(trimmed: String, sent: AgentImage?, preview: ImageBitmap?, barcode: String?, spoken: Boolean, quote: String?) {
        // One request at a time: a new one replaces the one still being answered (the server drops the rest of it).
        if (busy.value) stopReplies()
        spokenRequest = spoken
        add(trimmed, fromUser = true, image = preview, photo = sent, quote = quote)
        // The barcode read on the phone goes to the agent (for Open Food Facts), not into the bubble. Scanned or found
        // in a photo, it takes the line the agent's instructions know. A reply carries the start of the message it
        // answers, so the agent knows what "questo" refers to.
        val withQuote = quote?.let { "[in risposta a: «$it»]\n$trimmed" } ?: trimmed
        val forAgent = barcode?.let { "$withQuote\n[codice a barre nella foto: $it]".trim() } ?: withQuote
        viewModelScope.launch(conversation) {
            val speaker = if (spoken && settings.value.speakReplies) ReplySpeaker(spokenOutput) else null
            active.update { it + 1 }
            _thinking.value = true
            var replyId: Long? = null
            var ack = ""
            try {
                engine.respond(forAgent, localOnly = isLocked(), image = sent).collect { snap ->
                    _thinking.value = false
                    _working.value = snap.working
                    if (snap.working) {
                        if (replyId == null) {
                            // What was said before the agent started working is a status line, not part of the answer.
                            ack = snap.text
                            _workingText.value = ack.trim()
                        } else {
                            // The answer has begun: it stays on screen, and the work that follows shows as dots.
                            _workingText.value = ""
                        }
                    } else {
                        _workingText.value = ""
                        val shown = visibleReply(snap.text, ack)
                        if (shown.isNotEmpty()) {
                            val id = replyId
                            if (id == null) replyId = add(shown, fromUser = false) else replace(id, shown)
                        }
                    }
                    speaker?.onSnapshot(snap.text)
                    if (snap.working) speaker?.pause()
                }
                speaker?.finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                val id = replyId
                if (id == null) add(message, fromUser = false) else replace(id, message)
            } finally {
                _thinking.value = false
                _working.value = false
                _workingText.value = ""
                active.update { it - 1 }
            }
        }
    }

    fun stopSpeaking() = spokenOutput.stop()

    /** Messages of the assistant sheet, appended to this chat when the app is opened from it. */
    fun adopt(handed: List<ChatMessage>) {
        for (m in handed) add(m.text, m.fromUser, m.image, m.photo, m.quote)
    }

    /**
     * Stops waiting for the replies in progress: no more text, voice or actions from them. The agent may finish its
     * work on the server, but what it sends for these requests is dropped (see the agent client).
     */
    fun interrupt() {
        if (!busy.value) return
        stopReplies()
        if (_messages.value.lastOrNull()?.fromUser != false) add(phrases.requestInterrupted, fromUser = false)
    }

    private fun stopReplies() {
        engine.resetConversation() // an interrupted "Per quanto tempo?" is not waiting for an answer any more
        consent.refuseAll() // nor is a question about one of its actions
        conversation.cancel()
        conversation = Job(viewModelScope.coroutineContext[Job])
        spokenOutput.stop()
        _thinking.value = false
        _working.value = false
        _workingText.value = ""
    }

    /** Empties the chat and asks the server to start over: the agent forgets this conversation, its memory stays. */
    fun newConversation() {
        conversation.cancel()
        conversation = Job(viewModelScope.coroutineContext[Job])
        voice.cancel()
        spokenOutput.stop()
        engine.resetConversation()
        consent.refuseAll()
        _thinking.value = false
        _photo.value = null
        _barcode.value = null
        _replyingTo.value = null
        _messages.value = emptyList()
        viewModelScope.launch(conversation) {
            if (status.value == AgentStatus.NotConfigured) return@launch
            if (!connection.newConversation()) add(phrases.newConversationFailed, fromUser = false)
        }
    }

    override fun onCleared() {
        voice.cancel()
        spokenOutput.stop()
    }

    /** When the agent last said something, to tell its repeats (the same text again as a separate message). */
    private var lastReplyAt = 0L

    private fun repeatsLastReply(text: String): Boolean {
        val last = _messages.value.lastOrNull { !it.fromUser } ?: return false
        return last.text.trim() == text.trim() && android.os.SystemClock.elapsedRealtime() - lastReplyAt < REPEAT_WINDOW_MS
    }

    private fun add(text: String, fromUser: Boolean, image: ImageBitmap? = null, photo: AgentImage? = null, quote: String? = null): Long {
        if (!fromUser) lastReplyAt = android.os.SystemClock.elapsedRealtime()
        val id = nextId++
        _messages.update { list ->
            val all = list + ChatMessage(id, text, fromUser, image, photo, quote = quote)
            // The photo bytes (for a TinEye search from the bubble) stay for the latest few photos only.
            val keep = all.filter { it.photo != null }.takeLast(MAX_PHOTOS_KEPT).map { it.id }.toSet()
            all.map { if (it.photo != null && it.id !in keep) it.copy(photo = null) else it }
        }
        return id
    }

    private fun remove(id: Long) = _messages.update { list -> list.filter { it.id != id } }

    private fun replace(id: Long, text: String) {
        lastReplyAt = android.os.SystemClock.elapsedRealtime()
        _messages.update { list -> list.map { if (it.id == id) it.copy(text = text) else it } }
    }
}

enum class OrbAction { Listen, Finish, Cancel }

internal fun orbActionFor(voice: VoiceUiState, busy: Boolean, speaking: Boolean): OrbAction = when {
    voice.listening && !voice.finishing -> OrbAction.Finish
    voice.listening || busy || speaking -> OrbAction.Cancel
    else -> OrbAction.Listen
}

private const val MAX_PHOTOS_KEPT = 5

/** How much of the answered message goes with a reply: enough to recognise it, one line on screen. */
private const val QUOTE_CHARS = 160

/** The start of [message] as plain text, for "Rispondi": Markdown symbols and line breaks out. */
internal fun quoteOf(message: ChatMessage): String {
    val plain = withoutMarkdown(message.text.lines().joinToString(" ") { it.trim() }).ifEmpty { if (message.image != null) "foto" else "" }
    return if (plain.length > QUOTE_CHARS) plain.take(QUOTE_CHARS).trimEnd() + "…" else plain
}

/** An agent message identical to its last reply within this time is the same reply delivered twice. */
private const val REPEAT_WINDOW_MS = 60_000L

/** How long a question may take to start being spoken before listening for the answer anyway. */
private const val SPEECH_START_MS = 2_000L

/** The reply without the line the agent said before it started working. */
internal fun visibleReply(reply: String, ack: String): String =
    if (ack.isNotEmpty() && reply.startsWith(ack)) reply.removePrefix(ack).trim() else reply
