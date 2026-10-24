package dev.nanovoice.router.it

import dev.nanovoice.router.ClockTime

/**
 * Parses a time of day from normalized tokens: "sette e mezza", "7 30" (from "7:30"), "otto e un quarto",
 * "otto meno un quarto", "sette e venti", "sette in punto", "mezzogiorno", "mezzanotte", "diciannove e trenta",
 * optionally followed by a part of the day ("di sera", "del pomeriggio", "di mattina", "di notte") that decides
 * between morning and evening for hours up to eleven.
 */
object ItalianClockTimeParser {
    data class Found(val time: ClockTime, val endIndex: Int)

    private val pmWords = setOf("sera", "pomeriggio")
    private val nightWords = setOf("notte")
    private val amWords = setOf("mattina", "mattino")
    private val partOfDayPrepositions = setOf("di", "del", "della", "nel", "nella", "alla", "al")

    /** Parses a time that starts exactly at [start], or returns null. */
    fun parse(tokens: List<String>, start: Int): Found? {
        if (start >= tokens.size) return null
        when (tokens[start]) {
            "mezzogiorno" -> return Found(ClockTime(12, 0), start + 1)
            "mezzanotte" -> return Found(ClockTime(0, 0), start + 1)
        }
        val hourNumber = ItalianNumberParser.parse(tokens, start) ?: return null
        val hour = hourNumber.value
        if (hour !in 0..24) return null
        var next = hourNumber.endIndex
        var h = hour % 24
        var minute = 0

        when (tokens.getOrNull(next)) {
            "e" -> readMinutesAfterE(tokens, next + 1)?.let { (m, end) -> minute = m; next = end }
            "meno" -> readMinusAfterMeno(tokens, next + 1)?.let { (m, end) ->
                minute = m; next = end; h = (h + 23) % 24 // "otto meno dieci" = 7:50
            }
            "in" -> if (tokens.getOrNull(next + 1) == "punto") next += 2
            else -> {
                // "7:30" is normalized to "7 30": a two-digit number right after a digit hour is the minutes
                val m = tokens.getOrNull(next)
                if (m != null && tokens[start].all { it.isDigit() } && m.length == 2 && m.all { it.isDigit() } && m.toInt() in 0..59) {
                    minute = m.toInt(); next++
                }
            }
        }

        // part of the day: "di sera", "del pomeriggio", "di mattina", "di notte"
        var end = next
        val prep = tokens.getOrNull(end)
        val period = tokens.getOrNull(end + 1)
        if (prep in partOfDayPrepositions && period != null) {
            when (period) {
                in pmWords -> { if (h in 1..11) h += 12; end += 2 }
                in nightWords -> { h = nightHour(h); end += 2 }
                in amWords -> { if (h == 12) h = 0; end += 2 }
            }
        }
        return Found(ClockTime(h, minute), end)
    }

    /** Words for a part of the day said anywhere in a sentence, not only right after the time. */
    val dayPartWords = setOf("stasera", "sera", "pomeriggio", "stanotte", "notte", "stamattina", "mattina", "mattino", "domattina")
    private val eveningWords = setOf("stasera", "sera", "pomeriggio")
    private val nightOnlyWords = setOf("stanotte", "notte")

    /**
     * [time] with a part of the day said elsewhere in the sentence: "stasera alle nove", "alle otto stasera", "alle
     * sette di domani sera", "domani pomeriggio alle tre". Like "di sera" right after the time, it moves a morning
     * hour (1 to 11) to the evening; "stanotte" and "notte" read the hour as at night. A time already in the evening,
     * or a sentence without such a word, is left as it is.
     */
    fun withDayPart(tokens: List<String>, time: ClockTime): ClockTime = when {
        tokens.any { it in eveningWords } && time.hour in 1..11 -> ClockTime(time.hour + 12, time.minute)
        tokens.any { it in nightOnlyWords } && time.hour in 1..11 -> ClockTime(nightHour(time.hour), time.minute)
        else -> time
    }

    /** First time found anywhere in [tokens]. */
    fun find(tokens: List<String>): Found? {
        for (i in tokens.indices) parse(tokens, i)?.let { return it }
        return null
    }

    private fun nightHour(h: Int) = when (h) {
        in 6..11 -> h + 12 // "le dieci di notte" = 22:00
        12 -> 0            // "le dodici di notte" = midnight
        else -> h          // "le tre di notte" = 3:00
    }

    private fun readMinutesAfterE(tokens: List<String>, i: Int): Pair<Int, Int>? {
        when (tokens.getOrNull(i)) {
            "mezza", "mezzo" -> return 30 to i + 1
            "un", "una" -> if (tokens.getOrNull(i + 1) == "quarto") return 15 to i + 2
        }
        val n = ItalianNumberParser.parse(tokens, i) ?: return null
        if (tokens.getOrNull(n.endIndex) == "quarti" && n.value == 3) return 45 to n.endIndex + 1
        if (n.value !in 1..59) return null
        val end = if (tokens.getOrNull(n.endIndex).let { it == "minuti" || it == "minuto" }) n.endIndex + 1 else n.endIndex
        return n.value to end
    }

    private fun readMinusAfterMeno(tokens: List<String>, i: Int): Pair<Int, Int>? {
        if (tokens.getOrNull(i).let { it == "un" || it == "una" } && tokens.getOrNull(i + 1) == "quarto") return 45 to i + 2
        val n = ItalianNumberParser.parse(tokens, i) ?: return null
        if (n.value !in 1..59) return null
        val end = if (tokens.getOrNull(n.endIndex).let { it == "minuti" || it == "minuto" }) n.endIndex + 1 else n.endIndex
        return (60 - n.value) to end
    }
}
