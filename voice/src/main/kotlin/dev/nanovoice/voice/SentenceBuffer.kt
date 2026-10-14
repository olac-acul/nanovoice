package dev.nanovoice.voice

/**
 * Collects streamed reply text and hands out complete sentences, so the TTS can start speaking
 * while the agent is still writing. A sentence ends at `.`, `!`, `?` or `…` followed by whitespace,
 * or at a newline. Decimals ("3.5") and common abbreviations ("dott. Rossi") do not end a sentence.
 * Call [flush] when the reply is over to get whatever is left.
 */
class SentenceBuffer {
    private val pending = StringBuilder()

    /** Adds [delta] and returns the sentences that are now complete (possibly none). */
    fun feed(delta: String): List<String> {
        pending.append(delta)
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < pending.length) {
            val c = pending[i]
            val boundary = when {
                c == '\n' -> true
                c in TERMINATORS && i + 1 < pending.length && pending[i + 1].isWhitespace() ->
                    !endsWithAbbreviation(start, i)
                else -> false
            }
            if (boundary) {
                pending.substring(start, i + 1).trim().takeIf { it.isNotEmpty() }?.let(out::add)
                start = i + 1
            }
            i++
        }
        pending.delete(0, start)
        return out
    }

    /** The unfinished tail, or null if there is nothing to say. Resets the buffer. */
    fun flush(): String? {
        val rest = pending.toString().trim()
        pending.clear()
        return rest.takeIf { it.isNotEmpty() }
    }

    fun reset() = pending.clear()

    private fun endsWithAbbreviation(start: Int, dot: Int): Boolean {
        if (pending[dot] != '.') return false
        var s = dot
        while (s > start && pending[s - 1].isLetter()) s--
        return pending.substring(s, dot).lowercase() in ABBREVIATIONS
    }

    private companion object {
        val TERMINATORS = setOf('.', '!', '?', '…')
        val ABBREVIATIONS = setOf("dott", "dr", "sig", "sigra", "prof", "ing", "avv", "ecc", "es", "art", "pag", "ss", "cfr")
    }
}
