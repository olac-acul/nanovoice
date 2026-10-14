package dev.nanovoice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A question the person approves or not, before a change. Its first line is the question itself, said aloud after a
 * spoken request; any further lines are details only shown (the event before and after, for a calendar change).
 */
data class ConsentRequest(val id: Long, val question: String) {
    val headline: String get() = question.substringBefore('\n')
    val details: String? get() = question.substringAfter('\n', "").trim().ifEmpty { null }
}

enum class Consent { Yes, No, NoAnswer }

/**
 * The agent's questions before a change it should not make on its own (a calendar event moved or deleted): the person
 * answers with a button, by voice or by typing. One question at a time; no answer within [timeoutMs] is a no.
 */
class ActionConsent(private val timeoutMs: Long = 90_000) {
    private val _pending = MutableStateFlow<ConsentRequest?>(null)

    /** The question waiting for an answer, shown by the chat and the assistant sheet. */
    val pending: StateFlow<ConsentRequest?> = _pending

    private val turn = Mutex()
    private var answer: CompletableDeferred<Boolean>? = null
    private var nextId = 0L

    suspend fun ask(question: String): Consent = turn.withLock {
        val reply = CompletableDeferred<Boolean>()
        synchronized(this) {
            answer = reply
            _pending.value = ConsentRequest(nextId++, question)
        }
        try {
            when (withTimeoutOrNull(timeoutMs) { reply.await() }) {
                true -> Consent.Yes
                false -> Consent.No
                null -> Consent.NoAnswer
            }
        } finally {
            synchronized(this) {
                if (answer === reply) {
                    answer = null
                    _pending.value = null
                }
            }
        }
    }

    /** The person's answer to the question [id]; one about a question no longer shown is ignored. */
    fun answer(id: Long, yes: Boolean) = synchronized(this) {
        if (_pending.value?.id == id) answer?.complete(yes)
    }

    /** The request was interrupted or replaced: whatever it was waiting to be allowed is not. */
    fun refuseAll() = synchronized(this) {
        answer?.complete(false)
    }
}

private val YES = setOf("si", "certo", "ok", "okay", "vai", "fallo", "procedi", "conferma", "confermo", "esatto", "daccordo", "pure", "bene",
    "approva", "approvo", "approvato", "sposta", "spostalo", "cancella", "cancellalo", "cambia", "cambialo")
private val NO = setOf("no", "non", "annulla", "niente", "stop", "negativo", "aspetta", "fermo", "ferma", "lascia")
private const val MAX_ANSWER_WORDS = 5

/**
 * A said or typed answer to a [ConsentRequest]: true for a short yes ("sì", "va bene, spostalo"), false for a short answer
 * with a no in it ("no", "non approvare", "sì, anzi no": a doubt is a no), null for something else, a new request.
 */
internal fun consentAnswer(heard: String): Boolean? {
    val plain = java.text.Normalizer.normalize(heard.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace("d'accordo", "daccordo")
        .replace(Regex("[^a-z ]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
    val words = plain.split(' ').filter { it.isNotEmpty() }
    // A long sentence is a new request (which refuses the question anyway), even with a "non" in it.
    if (words.isEmpty() || words.size > MAX_ANSWER_WORDS) return null
    if (words.any { it in NO }) return false
    return if (words.any { it in YES }) true else null
}
