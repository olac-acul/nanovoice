package dev.nanovoice.router.it

import dev.nanovoice.router.LocalIntent
import dev.nanovoice.router.LocalMatcher
import dev.nanovoice.router.MatchResult

/**
 * Stopping what the clock app runs: "ferma il timer", "spegni la sveglia", "disattiva la sveglia delle sette",
 * "posticipa la sveglia di dieci minuti", "rimanda la sveglia". The sentence must be [polite words] <verb> [article]
 * <timer|sveglia> [time or duration] [polite words]. A question, a negation or anything else ("quando suona la sveglia",
 * "non fermare il timer", "ferma il timer e metti la musica") is not recognized and goes to the agent.
 */
object ItalianClockControlMatcher : LocalMatcher {
    private val prefixWords = setOf(
        "ciao", "ehi", "senti", "scusa", "scusami", "puoi", "potresti", "per", "favore", "mi", "ti", "dai", "ok", "subito",
    )
    private val stopStems = listOf("ferm", "spegn", "speng", "stop", "annull", "cancell", "togl", "elimin", "interromp", "disattiv", "zitt")
    /** Only for an alarm: "salta la sveglia delle sette". */
    private val skipStems = listOf("salt")
    private val snoozeStems = listOf("posticip", "rimand", "rinvi")
    private val articles = setOf("il", "lo", "la", "l", "quel", "quella", "questo", "questa", "mio", "mia")
    private val timeConnectors = setOf("delle", "alle", "all", "dell", "di", "della", "de", "per", "le", "domani", "domattina", "stamattina", "stasera", "oggi")
    private val suffixWords = setOf("per", "favore", "piacere", "grazie", "ok", "adesso", "subito", "ora", "domani", "stamattina", "domattina")
    /** Asks something, or says not to: not a request to stop. */
    private val refusals = setOf("non", "quando", "quanto", "come", "perche", "se", "manca", "mancano", "rimane", "rimasto", "cosa", "che")

    override fun match(text: String): MatchResult? {
        if (text.trim().endsWith("?")) return null
        val tokens = ItalianDurationParser.tokenize(text)
        if (tokens.isEmpty() || tokens.any { it in refusals }) return null
        val timerAt = tokens.indexOf("timer")
        val alarmAt = tokens.indexOf("sveglia")
        if (tokens.count { it == "timer" || it == "sveglia" } > 1) return null

        val verbAt = tokens.indexOfFirst { t -> (stopStems + skipStems + snoozeStems).any { t.startsWith(it) } }
        if (verbAt < 0 || tokens.count { t -> (stopStems + skipStems + snoozeStems).any { t.startsWith(it) } } > 1) return null
        val verb = tokens[verbAt]
        val snooze = snoozeStems.any { verb.startsWith(it) }
        val skip = skipStems.any { verb.startsWith(it) }
        if (!tokens.take(verbAt).all { it in prefixWords }) return null

        return when {
            snooze -> snooze(tokens, verbAt, alarmAt, timerAt)
            timerAt >= 0 && !skip -> stopTimer(tokens, verbAt, timerAt)
            alarmAt >= 0 -> stopAlarm(tokens, verbAt, alarmAt)
            else -> null
        }
    }

    /** <verb> [article] timer [polite words] */
    private fun stopTimer(tokens: List<String>, verbAt: Int, timerAt: Int): MatchResult? {
        if (timerAt < verbAt || !tokens.subList(verbAt + 1, timerAt).all { it in articles }) return null
        if (!tokens.drop(timerAt + 1).all { it in suffixWords }) return null
        return MatchResult(LocalIntent.StopTimer, 1f)
    }

    /** <verb> [article] sveglia [delle <time>] [polite words] */
    private fun stopAlarm(tokens: List<String>, verbAt: Int, alarmAt: Int): MatchResult? {
        if (alarmAt < verbAt || !tokens.subList(verbAt + 1, alarmAt).all { it in articles }) return null
        var i = alarmAt + 1
        while (i < tokens.size && tokens[i] in timeConnectors) i++
        val found = ItalianClockTimeParser.parse(tokens, i)
        val rest = found?.endIndex ?: (alarmAt + 1)
        if (!tokens.drop(rest).all { it in suffixWords || it in timeConnectors }) return null
        // "salta la sveglia" needs to know which one: without a time it is left to the agent.
        if (found == null && skipStems.any { tokens[verbAt].startsWith(it) }) return null
        return MatchResult(LocalIntent.StopAlarm(found?.time), 1f)
    }

    /** <verb> [article sveglia] [di <minutes>] [polite words] */
    private fun snooze(tokens: List<String>, verbAt: Int, alarmAt: Int, timerAt: Int): MatchResult? {
        if (timerAt >= 0) return null
        var i = verbAt + 1
        if (alarmAt >= 0) {
            if (alarmAt < verbAt || !tokens.subList(verbAt + 1, alarmAt).all { it in articles }) return null
            i = alarmAt + 1
        }
        val rest = tokens.drop(i)
        if (rest.all { it in suffixWords }) return MatchResult(LocalIntent.SnoozeAlarm(null), 1f)
        val found = ItalianDurationParser.find(rest) ?: return null
        if (!rest.take(found.startIndex).all { it in setOf("di", "per", "ancora", "altri", "un", "una") }) return null
        if (!rest.drop(found.endIndex).all { it in suffixWords }) return null
        val minutes = found.duration.inWholeMinutes.toInt()
        if (minutes !in 1..60) return null
        return MatchResult(LocalIntent.SnoozeAlarm(minutes), 1f)
    }
}
