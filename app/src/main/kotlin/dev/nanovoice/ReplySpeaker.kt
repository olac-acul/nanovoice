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
/** Words after which a day/month without a year is a date. */
private val DATE_WORDS = setOf(
    "il", "l'", "dal", "al", "del", "entro", "fino", "giorno", "data", "lunedì", "martedì", "mercoledì", "giovedì",
    "venerdì", "sabato", "domenica", "oggi", "domani",
)
private val slashDate = Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2}|\d{4}))?\b""")
private val articleOrdinal = Regex("""(?i)\b(il|lo|la|l'|al|allo|alla|del|dello|della|nel|nello|nella|dal|dalla|sul|sulla|un|una)\s+(\d{1,2})([°ºª])""")

/**
 * The voice reads most digits well (times, amounts, percentages). Two forms come out wrong, so they are rewritten
 * before speaking: dates with slashes ("21/10/2026" would be "ventuno barra dieci...") and ordinals with the degree
 * sign after an article ("il 1° piano" would be "uno gradi"). A plain "25°" stays: that one is a temperature. A
 * day/month without a year is a date only after a word that introduces one ("il 21/10", "dal 3/4", "venerdì 5/6"):
 * "1/2 cucchiaino", "24/7" and "voto 4/5" are not.
 */
internal fun spokenNumbers(text: String): String {
    val dates = slashDate.replace(text) { m ->
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        if (day !in 1..31 || month !in 1..12) return@replace m.value
        val year = m.groupValues[3]
        val wordBefore = text.substring(0, m.range.first).trimEnd().substringAfterLast(' ').lowercase()
        if (year.isEmpty() && wordBefore !in DATE_WORDS) return@replace m.value
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

/** Before a word that opens a new part of the sentence, where a person would take a breath ("… al mattino ma nel …"). */
private val breathBreak = Regex("""\s+(?=(?:e|ed|ma|però|perché|che|quando|se|mentre|dove|oppure|quindi|invece)\s)""", RegexOption.IGNORE_CASE)

/**
 * A clause cut before its connecting words, never into a piece shorter than [MIN_PIECE] ("e poi basta" stays with what
 * comes before it). A clause with none stays whole.
 */
internal fun breathPieces(clause: String): List<String> {
    val pieces = mutableListOf<String>()
    for (piece in clause.split(breathBreak)) {
        if (pieces.isNotEmpty() && (pieces.last().length < MIN_PIECE || piece.length < MIN_PIECE)) {
            pieces[pieces.lastIndex] = "${pieces.last()} $piece"
        } else {
            pieces += piece
        }
    }
    return pieces
}

/**
 * A long sentence in pieces that can be synthesized one by one. The voice takes about 37 ms of synthesis per character
 * before any of it is heard (a 170-character sentence: over 6 s of silence; twice that on a hot phone), and
 * synthesizes faster than it speaks. So the first piece is short, to start soon, and the next ones grow, each made
 * while the one before is playing. Cuts after a comma, semicolon or colon followed by a space ("1,5" stays whole), and
 * in a long clause before a connecting word ("ma", "e", "perché"…); a piece cut there ends with a comma, so the voice
 * goes on in the tone of a pause, not of a full stop. A sentence up to [limits]'s first value stays whole.
 */
internal fun spokenChunks(sentence: String, limits: List<Int> = CHUNK_LIMITS): List<String> {
    if (sentence.length <= limits.first()) return listOf(sentence)
    val pieces = sentence.split(clauseBreak).filter { it.isNotBlank() }.flatMap(::breathPieces)
    if (pieces.size < 2) return listOf(sentence)
    val chunks = mutableListOf<String>()
    var current = ""
    for (piece in pieces) {
        val limit = limits[minOf(chunks.size, limits.lastIndex)]
        val joined = if (current.isEmpty()) piece else "$current $piece"
        if (current.isNotEmpty() && joined.length > limit) {
            chunks += current
            current = piece
        } else {
            current = joined
        }
    }
    if (current.isNotEmpty()) chunks += current
    return chunks.mapIndexed { i, chunk -> if (i < chunks.lastIndex && chunk.last().isLetterOrDigit()) "$chunk," else chunk }
}

private const val MIN_PIECE = 20
private val CHUNK_LIMITS = listOf(60, 100, 160)
