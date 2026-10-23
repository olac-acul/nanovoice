package dev.nanovoice

import dev.nanovoice.voice.SentenceBuffer
import dev.nanovoice.voice.SpokenOutput

/**
 * Reads a reply aloud while it streams. [ChatEngine.respond] emits the whole reply so far each time, so this
 * works out what is new, cuts it into sentences and queues each complete sentence on the TTS.
 */
class ReplySpeaker(private val spoken: SpokenOutput) {
    private val buffer = SentenceBuffer()
    private var seen = ""

    fun onSnapshot(full: String) {
        val delta = if (full.startsWith(seen)) full.substring(seen.length) else full.also { buffer.reset() }
        seen = full
        buffer.feed(delta).forEach(::say)
    }

    private fun say(sentence: String) {
        val text = spokenNumbers(withoutMarkdown(withoutUrls(sentence)))
        if (text.any { it.isLetterOrDigit() } && !isSourceLabel(text)) spokenChunks(text).forEach(spoken::speak)
    }

    /**
     * Say what is waiting now instead of at the end: the agent paused to work, or a whole message of it has arrived
     * (its last sentence may have no space after the full stop, which the sentence buffer waits for).
     */
    fun pause() {
        buffer.flush()?.let(::say)
    }

    /** The reply is over: say what is left and get ready for the next one. */
    fun finish() {
        buffer.flush()?.let(::say)
        seen = ""
    }
}

private val markdownLink = Regex("""\[([^\]]*)]\((?:https?://|www\.)[^)\s]*\)""")
private val bareUrl = Regex("""(?:https?://|www\.)\S+""")

private val sourceLabel = Regex("""^(?:fonte|fonti|source|sources)\s*[:.]?$""", RegexOption.IGNORE_CASE)

/** What is left of "Fonte: https://…" once the address is gone: a label for the link in the chat, not for the voice. */
internal fun isSourceLabel(text: String): Boolean = sourceLabel.matches(text.trim())

/** Links are for the eyes: a markdown link is read as its text and a bare address is not read at all. */
internal fun withoutUrls(text: String): String =
    text.replace(markdownLink, "$1").replace(bareUrl, "").replace(Regex("\\s+"), " ").trim()

private val MONTHS = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre")
private val ORDINALS_M = listOf("primo", "secondo", "terzo", "quarto", "quinto", "sesto", "settimo", "ottavo", "nono", "decimo")
private val slashDate = Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2}|\d{4}))?\b""")
private val articleOrdinal = Regex("""(?i)\b(il|lo|la|l'|al|allo|alla|del|dello|della|nel|nello|nella|dal|dalla|sul|sulla|un|una)\s+(\d{1,2})([°ºª])""")

/**
 * The voice reads most digits well (times, amounts, percentages). Two forms come out wrong, so they are rewritten
 * before speaking: dates with slashes ("21/10/2026" would be "ventuno barra dieci...") and ordinals with the degree
 * sign after an article ("il 1° piano" would be "uno gradi"). A plain "25°" stays: that one is a temperature.
 */
internal fun spokenNumbers(text: String): String {
    val dates = slashDate.replace(text) { m ->
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        if (day !in 1..31 || month !in 1..12) return@replace m.value
        val year = m.groupValues[3]
        buildString {
            append(day).append(' ').append(MONTHS[month - 1])
            if (year.isNotEmpty()) append(' ').append(if (year.length == 2) "20$year" else year)
        }
    }
    return articleOrdinal.replace(dates) { m ->
        val n = m.groupValues[2].toInt()
        if (n !in 1..ORDINALS_M.size) return@replace m.value
        val word = ORDINALS_M[n - 1].let { if (m.groupValues[3] == "ª") it.dropLast(1) + "a" else it }
        "${m.groupValues[1]} $word"
    }
}

private val blockMarker = Regex("""^\s*(#{1,6}|[-*+]|\d{1,3}[.)]|>)\s+""")
private val listNumberAlone = Regex("""^\s*\d{1,3}[.)]\s*$""")
private val fenceLine = Regex("""^\s*```.*$""", RegexOption.MULTILINE)
private val wordUnderscore = Regex("""(?<![\p{L}\p{N}])_+|_+(?![\p{L}\p{N}])""")

/**
 * The chat shows Markdown formatted; the voice must not say the symbols. Removes heading, list and quote markers at
 * the start, code fences and the emphasis characters. A list number on its own ("1." split off as a sentence) is
 * dropped. Underscores inside words (snake_case) stay.
 */
internal fun withoutMarkdown(sentence: String): String {
    if (listNumberAlone.matches(sentence)) return ""
    return sentence
        .replace(fenceLine, "")
        .replace(blockMarker, "")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace("*", "")
        .replace(wordUnderscore, "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private val clauseBreak = Regex("""(?<=[,;:])\s+""")

/**
 * A long sentence in pieces that can be synthesized one by one. The voice takes about 37 ms of synthesis per character
 * before any of it is heard (a 170-character sentence: over 6 s of silence), and synthesizes faster than it speaks.
 * So the first piece is short, to start soon, and the next ones grow, each made while the one before is playing.
 * Cuts only after a comma, semicolon or colon followed by a space ("1,5" stays whole), never inside a clause; a
 * sentence up to [limits]'s first value stays whole.
 */
internal fun spokenChunks(sentence: String, limits: List<Int> = CHUNK_LIMITS): List<String> {
    if (sentence.length <= limits.first()) return listOf(sentence)
    val clauses = sentence.split(clauseBreak).filter { it.isNotBlank() }
    if (clauses.size < 2) return listOf(sentence)
    val chunks = mutableListOf<String>()
    var current = ""
    for (clause in clauses) {
        val limit = limits[minOf(chunks.size, limits.lastIndex)]
        val joined = if (current.isEmpty()) clause else "$current $clause"
        if (current.isNotEmpty() && joined.length > limit) {
            chunks += current
            current = clause
        } else {
            current = joined
        }
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

private val CHUNK_LIMITS = listOf(60, 100, 160)
