package dev.nanovoice.router.it

import dev.nanovoice.router.LocalIntent
import dev.nanovoice.router.LocalMatcher
import dev.nanovoice.router.MatchResult
import kotlin.time.Duration

/**
 * "metti un timer di 10 minuti", "timer 30 secondi", "un timer di un'ora e venti", "metti un timer".
 * The sentence must be: [command words] timer [di|da|per] [duration] [polite words]. Anything else
 * ("quanto dura un timer", "ferma il timer") is not recognized and goes to the agent.
 */
object ItalianTimerMatcher : LocalMatcher {
    private val prefixWords = setOf(
        "ciao", "ehi", "senti", "scusa", "scusami", "per", "favore", "piacere", "puoi", "potresti",
        "vorrei", "mi", "ti", "ho", "bisogno", "di", "un", "uno", "il", "lo", "a",
    )
    private val commandStems = listOf("mett", "impost", "avvi", "fai", "far", "part", "sett", "cre", "attiv", "programm")
    private val connectors = setOf("di", "da", "per", "a")
    private val suffixWords = setOf("per", "favore", "piacere", "grazie", "ok", "adesso", "subito")

    override fun match(text: String): MatchResult? {
        val tokens = ItalianDurationParser.tokenize(text)
        val at = tokens.indexOf("timer")
        if (at < 0 || tokens.lastIndexOf("timer") != at) return null
        if (!tokens.take(at).all { it in prefixWords || commandStems.any { s -> it.startsWith(s) } }) return null

        var i = at + 1
        if (tokens.getOrNull(i) in connectors) i++
        val found = ItalianDurationParser.parse(tokens, i)
        val rest = if (found != null) found.endIndex else at + 1
        if (!tokens.drop(rest).all { it in suffixWords }) return null

        val duration = found?.duration?.takeIf { it > Duration.ZERO }
        return MatchResult(LocalIntent.SetTimer(duration), if (duration != null) 1f else 0.9f)
    }
}
