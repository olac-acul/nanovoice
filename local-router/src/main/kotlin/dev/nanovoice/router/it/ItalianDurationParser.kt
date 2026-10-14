package dev.nanovoice.router.it

import dev.nanovoice.router.TextNormalizer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Parses Italian spoken durations from normalized tokens: "dieci minuti", "mezz ora",
 * "un minuto e mezzo", "un ora e un quarto", "un ora e venti", "tre quarti d ora".
 */
object ItalianDurationParser {
    data class Found(val duration: Duration, val startIndex: Int, val endIndex: Int)

    private enum class Unit(val one: Duration) { HOUR(1.hours), MINUTE(1.minutes), SECOND(1.seconds) }

    private val unitWords = mapOf(
        "ora" to Unit.HOUR, "ore" to Unit.HOUR,
        "minuto" to Unit.MINUTE, "minuti" to Unit.MINUTE, "min" to Unit.MINUTE,
        "secondo" to Unit.SECOND, "secondi" to Unit.SECOND, "sec" to Unit.SECOND,
    )
    private val halfWords = setOf("mezzo", "mezza", "mezz")
    private val quarterWords = setOf("quarto", "quarti")

    private class Component(val duration: Duration, val end: Int, val unit: Unit)

    /** Parses a duration starting exactly at [start]. */
    fun parse(tokens: List<String>, start: Int): Found? {
        var comp = firstComponent(tokens, start) ?: return null
        var total = comp.duration
        while (true) {
            val next = followUp(tokens, comp.end, comp.unit) ?: break
            total += next.duration
            comp = next
        }
        return Found(total, start, comp.end)
    }

    /** Finds the first duration anywhere in [tokens]. */
    fun find(tokens: List<String>): Found? {
        for (i in tokens.indices) parse(tokens, i)?.let { return it }
        return null
    }

    fun parseWhole(text: String): Duration? {
        val tokens = tokenize(text)
        return parse(tokens, 0)?.takeIf { it.endIndex == tokens.size }?.duration
    }

    fun tokenize(text: String): List<String> = TextNormalizer.normalize(text).split(" ").filter { it.isNotEmpty() }

    private fun firstComponent(t: List<String>, i: Int): Component? {
        if (i >= t.size) return null
        // "mezz ora", "mezza ora", "mezzo minuto"
        if (t[i] in halfWords) {
            val u = t.getOrNull(i + 1)?.let(unitWords::get) ?: return null
            return when (u) {
                Unit.HOUR -> Component(30.minutes, i + 2, Unit.HOUR)
                Unit.MINUTE -> Component(30.seconds, i + 2, Unit.MINUTE)
                Unit.SECOND -> null
            }
        }
        val num = ItalianNumberParser.parse(t, i) ?: return null
        val after = num.endIndex
        // "un quarto d ora", "tre quarti d ora"
        if (t.getOrNull(after) in quarterWords) {
            if (t.getOrNull(after + 1) == "d" && t.getOrNull(after + 2) == "ora") {
                return Component(15.minutes * num.value, after + 3, Unit.HOUR)
            }
            return null
        }
        val u = t.getOrNull(after)?.let(unitWords::get) ?: return null
        return Component(u.one * num.value, after + 1, u)
    }

    /** Parses what may follow a component of unit [prev]: a smaller unit, a half, a quarter or a bare number. */
    private fun followUp(t: List<String>, i: Int, prev: Unit): Component? {
        val smaller = when (prev) {
            Unit.HOUR -> Unit.MINUTE
            Unit.MINUTE -> Unit.SECOND
            Unit.SECOND -> return null
        }
        val hasE = t.getOrNull(i) == "e"
        val j = if (hasE) i + 1 else i

        // explicit smaller unit, with or without "e"
        firstComponent(t, j)?.let { if (it.unit.ordinal > prev.ordinal) return it }

        if (!hasE) return null
        val half = t.getOrNull(j)
        if (half in halfWords && (j + 1 >= t.size || t[j + 1] !in unitWords)) {
            return Component(prev.one / 2, j + 1, smaller)
        }
        val num = ItalianNumberParser.parse(t, j) ?: return null
        val after = num.endIndex
        if (t.getOrNull(after) in quarterWords) {
            val quarter = if (prev == Unit.HOUR) 15.minutes else 15.seconds
            return Component(quarter * num.value, after + 1, smaller)
        }
        if (t.getOrNull(after) in unitWords) return null // wrong unit order, e.g. "1 minuto e 2 ore"
        return Component(smaller.one * num.value, after, smaller)
    }
}
