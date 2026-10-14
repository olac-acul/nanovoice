package dev.nanovoice.router.it

import dev.nanovoice.router.LocalIntent
import dev.nanovoice.router.LocalMatcher
import dev.nanovoice.router.MatchResult

/**
 * "svegliami alle sette e mezza", "metti una sveglia per le 7", "imposta la sveglia alle otto meno un quarto",
 * "sveglia domani alle sei", "metti una sveglia" (no time: the assistant asks). The sentence must be
 * [command words] sveglia [alle] <time> [domani] [polite words]; "cancella la sveglia" or "a che ora suona la sveglia"
 * are not recognized and go to the agent, and so do days the clock app cannot target (dopodomani, weekdays).
 */
object ItalianAlarmMatcher : LocalMatcher {
    private val prefixWords = setOf(
        "ciao", "ehi", "senti", "scusa", "scusami", "per", "favore", "piacere", "puoi", "potresti", "vorrei", "mi", "ti", "ho",
        "bisogno", "di", "una", "la", "un", "il", "lo", "a", "nuova", "mia",
    )
    private val commandStems = listOf("mett", "impost", "avvi", "fai", "far", "part", "sett", "cre", "attiv", "programm")
    private val alarmWords = setOf("sveglia", "svegliami", "svegliarmi")
    private val connectors = setOf("per", "a", "alle", "alla", "all", "le", "il", "domani", "oggi", "stamattina", "stasera", "domattina", "di")
    private val tomorrowWords = setOf("domani", "domattina")
    private val unsupportedDays = setOf(
        "dopodomani", "lunedi", "martedi", "mercoledi", "giovedi", "venerdi", "sabato", "domenica", "prossimo", "prossima",
        "settimana", "tutti", "ogni", "giorni", "feriali",
    )
    private val suffixWords = setOf("per", "favore", "piacere", "grazie", "ok", "adesso", "subito", "domani", "oggi")

    override fun match(text: String): MatchResult? {
        val tokens = ItalianDurationParser.tokenize(text)
        val at = tokens.indexOfFirst { it in alarmWords }
        if (at < 0 || tokens.count { it in alarmWords } != 1) return null
        if (tokens.any { it in unsupportedDays }) return null

        val verbForm = tokens[at] != "sveglia"
        val before = tokens.take(at)
        if (!before.all { it in prefixWords || commandStems.any { s -> it.startsWith(s) } }) return null
        // "svegliami" is itself the command; a bare noun "sveglia" with nothing before it is also accepted
        if (!verbForm && before.isEmpty() && tokens.size == 1) return MatchResult(LocalIntent.SetAlarm(null), 0.9f)

        var i = at + 1
        while (i < tokens.size && tokens[i] in connectors) i++
        val found = ItalianClockTimeParser.parse(tokens, i)
        val rest = if (found != null) found.endIndex else at + 1
        if (!tokens.drop(rest).all { it in suffixWords }) return null

        val tomorrow = tokens.any { it in tomorrowWords }
        return if (found != null) MatchResult(LocalIntent.SetAlarm(found.time, tomorrow), 1f)
        else MatchResult(LocalIntent.SetAlarm(null, tomorrow), 0.9f)
    }
}
