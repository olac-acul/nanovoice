package dev.nanovoice.actions

import dev.nanovoice.router.TextNormalizer

/** Fuzzy matching of spoken names against labels (app names, contact names). Pure Kotlin. */
object NameMatching {
    /** Spoken Italian words for common apps -> words that appear in their (mostly English) labels. */
    private val aliases = mapOf(
        "fotocamera" to listOf("camera"),
        "macchina fotografica" to listOf("camera"),
        "orologio" to listOf("clock", "orologio"),
        "sveglia" to listOf("clock"),
        "calcolatrice" to listOf("calculator", "calcolatrice"),
        "calendario" to listOf("calendar", "calendario"),
        "contatti" to listOf("contacts", "contatti"),
        "rubrica" to listOf("contacts", "contatti"),
        "impostazioni" to listOf("settings", "impostazioni"),
        "telefono" to listOf("phone", "telefono"),
        "mappe" to listOf("maps", "mappe"),
        "musica" to listOf("music", "musica"),
        "galleria" to listOf("gallery", "photos", "galleria"),
        "foto" to listOf("photos", "gallery", "foto"),
        "messaggi" to listOf("messages", "messaggi"),
        "file" to listOf("files"),
        "note" to listOf("notes", "note"),
    )

    /**
     * Higher is better; 0 means no match. [partialWords] lets "whats" stand for "WhatsApp": right for apps, wrong for
     * people ("Anna" is not "Annalisa"), so contacts only match whole words.
     */
    fun score(query: String, label: String, partialWords: Boolean = true): Int {
        val q = TextNormalizer.normalize(query)
        val l = TextNormalizer.normalize(label)
        if (q.isEmpty() || l.isEmpty()) return 0
        if (q == l) return 100
        val qTokens = q.split(" ")
        val lTokens = l.split(" ")
        var best = 0
        if (lTokens.containsAll(qTokens)) best = maxOf(best, 80)          // "mario" in "mario rossi"
        if (partialWords) {
            if (l.startsWith(q) || q.startsWith(l)) best = maxOf(best, 70)
            if (qTokens.all { qt -> lTokens.any { it.startsWith(qt) } }) best = maxOf(best, 60)
        } else if (l.startsWith("$q ") || q.startsWith("$l ")) {
            best = maxOf(best, 70) // "mario" for "mario rossi": whole words only
        }
        aliases[q]?.let { words -> if (words.any { w -> lTokens.contains(w) || l == w }) best = maxOf(best, 90) }
        if (best == 0 && qTokens.all { qt -> lTokens.any { closeEnough(qt, it) } }) best = 40
        return best
    }

    /**
     * Tolerates one wrong letter in long words, the usual speech recognition slip ("alesandro" for "alessandro").
     * Words that differ only in the last letter are different names ("Mario"/"Maria", "Francesco"/"Francesca"):
     * confusing those would dial the wrong person.
     */
    private fun closeEnough(a: String, b: String): Boolean =
        a.length >= 7 && b.length >= 7 && levenshtein(a, b) <= 1 && a.dropLast(1) != b.dropLast(1)

    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** The best candidates for [query]: everything sharing the top score, or empty if nothing matches. */
    fun <T> best(query: String, items: List<T>, partialWords: Boolean = true, label: (T) -> String): List<T> {
        val scored = items.map { it to score(query, label(it), partialWords) }.filter { it.second > 0 }
        val top = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == top }.map { it.first }
    }
}
