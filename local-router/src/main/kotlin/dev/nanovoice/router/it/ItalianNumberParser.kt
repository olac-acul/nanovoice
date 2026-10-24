package dev.nanovoice.router.it

/**
 * Parses Italian numbers (digits or words, 0..999999) from normalized tokens
 * (see [dev.nanovoice.router.TextNormalizer]). Accepts glued words ("centoventitre", "duemila")
 * and words split by a speech recognizer ("venti tre", "due mila").
 */
object ItalianNumberParser {
    data class Parsed(val value: Int, val endIndex: Int)

    private const val HUNDRED = 100
    private const val THOUSAND = 1000

    private val atoms: Map<String, Int> = buildMap {
        val units = listOf("uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove")
        units.forEachIndexed { i, w -> put(w, i + 1) }
        put("un", 1); put("una", 1); put("zero", 0)
        listOf(
            "dieci", "undici", "dodici", "tredici", "quattordici", "quindici",
            "sedici", "diciassette", "diciotto", "diciannove",
        ).forEachIndexed { i, w -> put(w, 10 + i) }
        listOf("venti", "trenta", "quaranta", "cinquanta", "sessanta", "settanta", "ottanta", "novanta")
            .forEachIndexed { i, w ->
                put(w, 20 + 10 * i)
                // elided forms: ventuno, ventotto
                put(w.dropLast(1) + "uno", 21 + 10 * i)
                put(w.dropLast(1) + "otto", 28 + 10 * i)
            }
        put("cento", HUNDRED); put("mille", THOUSAND); put("mila", THOUSAND)
    }
    private val atomsLongestFirst = atoms.keys.sortedByDescending { it.length }

    /** Parses the longest number starting at [start], or null if tokens[start] is not a number. */
    fun parse(tokens: List<String>, start: Int): Parsed? {
        if (start >= tokens.size) return null
        val first = tokens[start]
        if (first.all { it.isDigit() }) {
            val v = first.toIntOrNull() ?: return null
            return Parsed(v, start + 1)
        }
        val builder = Builder()
        var end = start
        var best: Parsed? = null
        while (end < tokens.size) {
            val parts = decompose(tokens[end]) ?: break
            if (!builder.addAll(parts)) break
            end++
            best = Parsed(builder.value(), end)
        }
        return best
    }

    private fun decompose(word: String): List<Int>? {
        if (word.isEmpty()) return emptyList()
        for (atom in atomsLongestFirst) {
            if (word.startsWith(atom)) {
                val rest = decompose(word.substring(atom.length)) ?: continue
                return listOf(atoms.getValue(atom)) + rest
            }
        }
        return null
    }

    private class Builder {
        private var total = 0
        private var current = 0
        private var used = false

        fun addAll(parts: List<Int>): Boolean {
            val snapshot = Triple(total, current, used)
            for (p in parts) if (!add(p)) {
                total = snapshot.first; current = snapshot.second; used = snapshot.third
                return false
            }
            return true
        }

        private fun add(a: Int): Boolean {
            when {
                a == THOUSAND -> {
                    if (current >= THOUSAND) return false
                    total += (if (current == 0) 1 else current) * THOUSAND
                    current = 0
                }
                a == HUNDRED -> {
                    if (current >= 10) return false
                    current = (if (current == 0) 1 else current) * HUNDRED
                }
                else -> {
                    val r = current % HUNDRED
                    val ok = r == 0 || (r >= 20 && r % 10 == 0 && a in 1..9)
                    if (!ok || (a == 0 && used)) return false
                    current += a
                }
            }
            used = true
            return true
        }

        fun value() = total + current
    }
}
