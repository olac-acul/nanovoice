package dev.nanovoice.router

import java.text.Normalizer

/**
 * Normalizes user text before matching: lowercase, accents removed, apostrophes and
 * punctuation turned into spaces, whitespace collapsed. "Un'ora e un quarto?" -> "un ora e un quarto".
 */
object TextNormalizer {
    private val combiningMarks = Regex("\\p{M}+")

    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val stripped = combiningMarks.replace(decomposed, "")
        val sb = StringBuilder(stripped.length)
        for (c in stripped) sb.append(if (c.isLetterOrDigit()) c else ' ')
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }
}
