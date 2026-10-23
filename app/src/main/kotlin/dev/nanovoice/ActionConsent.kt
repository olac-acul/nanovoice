package dev.nanovoice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A question the person approves or not, before a change, or, with [options], one they answer by choosing. Its first
 * line is the question itself, said aloud after a spoken request; any further lines are details only shown (the event
 * before and after, for a calendar change).
 */
data class ConsentRequest(val id: Long, val question: String, val options: List<String> = emptyList()) {
    val headline: String get() = question.substringBefore('\n')
    val details: String? get() = question.substringAfter('\n', "").trim().ifEmpty { null }
    val isChoice: Boolean get() = options.isNotEmpty()

    /** What is said aloud: the question, and for a choice its options ("Otranto, Gallipoli o Polignano?"). */
    val spoken: String get() = if (!isChoice) headline else "$headline ${spokenList(options)}"
}

/** "A", "A o B", "A, B o C": the options as they are said. */
internal fun spokenList(options: List<String>): String =
    if (options.size == 1) options[0] else options.dropLast(1).joinToString(", ") + " o " + options.last()

enum class Consent { Yes, No, NoAnswer }

/**
 * The agent's questions before a change it should not make on its own (a calendar event moved or deleted), and its
 * questions with a few options to choose from: the person answers with a button, by voice or by typing. One question at
 * a time; no answer within [timeoutMs] is a no, or no choice.
 */
class ActionConsent(private val timeoutMs: Long = 90_000) {
    private val _pending = MutableStateFlow<ConsentRequest?>(null)

    /** The question waiting for an answer, shown by the chat and the assistant sheet. */
    val pending: StateFlow<ConsentRequest?> = _pending

    private val turn = Mutex()
    /** For a yes or no: 1 or 0. For a choice: the option's index. [REFUSED]: a no, or no choice. */
    private var answer: CompletableDeferred<Int>? = null
    private var nextId = 0L

    suspend fun ask(question: String): Consent = when (waitFor(question, emptyList())) {
        YES -> Consent.Yes
        null -> Consent.NoAnswer
        else -> Consent.No
    }

    /** The index of the option chosen, [REFUSED] when the person turned to something else, or null without an answer. */
    suspend fun choose(question: String, options: List<String>): Int? = waitFor(question, options)

    private suspend fun waitFor(question: String, options: List<String>): Int? = turn.withLock {
        val reply = CompletableDeferred<Int>()
        synchronized(this) {
            answer = reply
            _pending.value = ConsentRequest(nextId++, question, options)
        }
        try {
            withTimeoutOrNull(timeoutMs) { reply.await() }
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
        if (_pending.value?.id == id) answer?.complete(if (yes) YES else REFUSED)
    }

    /** The option the person chose for the question [id]; [REFUSED] for none of them. */
    fun pick(id: Long, index: Int) = synchronized(this) {
        val pending = _pending.value ?: return@synchronized
        if (pending.id == id && (index == REFUSED || index in pending.options.indices)) answer?.complete(index)
    }

    /** The request was interrupted or replaced: whatever it was waiting to be allowed is not, and nothing is chosen. */
    fun refuseAll() = synchronized(this) {
        answer?.complete(REFUSED)
    }

    companion object {
        const val REFUSED = -1
        private const val YES = 1
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

private val ORDINALS = listOf(
    setOf("prima", "primo", "uno", "1"),
    setOf("seconda", "secondo", "due", "2"),
    setOf("terza", "terzo", "tre", "3"),
    setOf("quarta", "quarto", "quattro", "4"),
    setOf("quinta", "quinto", "cinque", "5"),
)
private val NONE = setOf("nessuna", "nessuno", "annulla", "niente")
/** Words before an ordinal that make it one ("la seconda", "opzione due"): "secondo te" is not a choice. */
private val BEFORE_ORDINAL = setOf("la", "il", "l", "lo", "opzione", "numero")
private const val MAX_CHOICE_WORDS = 8

private fun plainWords(text: String): List<String> =
    java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9 ]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }

/**
 * A said or typed answer to a choice: the index of the option ("la seconda", "l'ultima", or the option's own words,
 * "Otranto"), [ActionConsent.REFUSED] for "nessuna" or "annulla", null for something else, a new request. An answer
 * that fits two options fits none.
 */
internal fun choiceAnswer(heard: String, options: List<String>): Int? {
    val words = plainWords(heard)
    if (words.isEmpty() || words.size > MAX_CHOICE_WORDS) return null
    if (words.any { it in NONE }) return ActionConsent.REFUSED
    val byOption = options.indices.filter { i ->
        val option = plainWords(options[i])
        option.isNotEmpty() && words.windowed(option.size).any { it == option }
    }
    if (byOption.size == 1) return byOption[0]
    if (byOption.size > 1) return null
    // An ordinal alone, or after an article ("la seconda", "l'ultima"): in a sentence it is mostly something else.
    fun said(forms: Set<String>) = words.indices.any { i -> words[i] in forms && (words.size == 1 || words.getOrNull(i - 1) in BEFORE_ORDINAL) }
    val byOrdinal = ORDINALS.indices.filter { i -> i < options.size && said(ORDINALS[i]) } +
        listOfNotNull(options.lastIndex.takeIf { said(setOf("ultima", "ultimo")) })
    return byOrdinal.distinct().singleOrNull()
}
