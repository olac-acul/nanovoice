package dev.nanovoice.router.it

import dev.nanovoice.router.LocalIntent
import dev.nanovoice.router.LocalMatcher
import dev.nanovoice.router.MatchResult

/**
 * Stopping what the clock app runs: "ferma il timer", "spegni la sveglia", "disattiva la sveglia delle sette",
 * "posticipa la sveglia di dieci minuti", "rimanda la sveglia", "ferma il timer di dieci secondi che avevi impostato".
 * The sentence must be [polite words] <verb> [article] <timer|sveglia> [time or duration] [che <clause about it>]
 * [polite words], where the clause only says which one ("che sta suonando", "che avevi impostato"). A question, a
 * negation or anything else ("quando suona la sveglia", "non fermare il timer", "ferma il timer e metti la musica") is
 * not recognized and goes to the agent.
 */
object ItalianClockControlMatcher : LocalMatcher {
    private val prefixWords = setOf(
        "ciao", "ehi", "senti", "scusa", "scusami", "puoi", "potresti", "per", "favore", "mi", "ti", "dai", "ok", "subito",
    )
    private val stopStems = listOf("ferm", "spegn", "speng", "stop", "annull", "cancell", "togl", "elimin", "interromp", "disattiv", "zitt")
    /** Only for an alarm: "salta la sveglia delle sette". */
    private val skipStems = listOf("salt")
    private val snoozeStems = listOf("posticip", "rimand", "rinvi")
    private val bareSnoozeVerbs = setOf("rimanda", "posticipa", "rinvia", "rimandala", "posticipala", "rinviala")
    private val articles = setOf("il", "lo", "la", "l", "quel", "quella", "questo", "questa", "mio", "mia")
    private val timeConnectors = setOf("delle", "alle", "all", "dell", "di", "della", "de", "per", "le", "domani", "oggi") +
        ItalianClockTimeParser.dayPartWords
    private val suffixWords = setOf("per", "favore", "piacere", "grazie", "ok", "adesso", "subito", "ora", "domani", "stamattina", "domattina")
    /** Asks something, or says not to: not a request to stop. */
    private val refusals = setOf("non", "quando", "quanto", "come", "perche", "se", "manca", "mancano", "rimane", "rimasto", "cosa", "che")
    /** "che sta suonando", "che avevi impostato prima": words that only point at the timer or alarm meant. */
    private val clauseWords = setOf(
        "sta", "stanno", "suona", "suonando", "squilla", "squillando", "avevi", "hai", "ho", "avevo", "abbiamo", "avevamo",
        "impostato", "impostata", "messo", "messa", "puntato", "puntata", "programmato", "programmata", "appena", "prima",
        "poco", "fa", "mi", "ti", "gia", "partito", "finito", "scaduto",
    )
    /** How the speech recognizer may write "timer". */
    private val timerSpellings = setOf("time", "timers", "taimer", "tymer")

    override fun match(text: String): MatchResult? {
        if (text.trim().endsWith("?")) return null
        val tokens = withoutClause(ItalianDurationParser.tokenize(text).map { if (it in timerSpellings) "timer" else it }) ?: return null
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

    /**
     * Drops a "che ..." clause after the timer or alarm when it only says which one is meant; null when the clause says
     * anything else, so the sentence goes to the agent.
     */
    private fun withoutClause(tokens: List<String>): List<String>? {
        val nounAt = tokens.indexOfFirst { it == "timer" || it == "sveglia" }
        if (nounAt < 0) return tokens
        val clauseAt = tokens.subList(nounAt + 1, tokens.size).indexOf("che").takeIf { it >= 0 }?.let { it + nounAt + 1 } ?: return tokens
        val clause = tokens.drop(clauseAt + 1)
        if (clause.none { it in clauseWords } || !clause.all { it in clauseWords || it in suffixWords || it in articles }) return null
        return tokens.take(clauseAt) + clause.filter { it in suffixWords }
    }

    /** <verb> [article] timer [di|da <duration>] [polite words] */
    private fun stopTimer(tokens: List<String>, verbAt: Int, timerAt: Int): MatchResult? {
        if (timerAt < verbAt || !tokens.subList(verbAt + 1, timerAt).all { it in articles }) return null
        var rest = tokens.drop(timerAt + 1)
        if (rest.firstOrNull() in setOf("di", "da", "de")) {
            val length = ItalianDurationParser.find(rest)?.takeIf { it.startIndex == 1 } ?: return null
            rest = rest.drop(length.endIndex)
        }
        if (!rest.all { it in suffixWords }) return null
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
        return MatchResult(LocalIntent.StopAlarm(found?.time?.let { ItalianClockTimeParser.withDayPart(tokens, it) }), 1f)
    }

    /** <verb> article sveglia [di <minutes>] [polite words], or <verb> di <minutes>: the alarm or a length says what. */
    private fun snooze(tokens: List<String>, verbAt: Int, alarmAt: Int, timerAt: Int): MatchResult? {
        if (timerAt >= 0) return null
        var i = verbAt + 1
        if (alarmAt >= 0) {
            if (alarmAt < verbAt || !tokens.subList(verbAt + 1, alarmAt).all { it in articles }) return null
            i = alarmAt + 1
        }
        val rest = tokens.drop(i)
        // "rimanda la sveglia" says what, and so does the bare "rimanda!" said to a ringing alarm; with a pronoun on the
        // verb ("rimandalo", "rimandamelo": send it again, "rinvialo") it is about something else
        if (rest.all { it in suffixWords }) {
            return if (alarmAt >= 0 || tokens[verbAt] in bareSnoozeVerbs) MatchResult(LocalIntent.SnoozeAlarm(null), 1f) else null
        }
        val found = ItalianDurationParser.find(rest) ?: return null
        if (!rest.take(found.startIndex).all { it in setOf("di", "per", "ancora", "altri", "un", "una") }) return null
        if (!rest.drop(found.endIndex).all { it in suffixWords }) return null
        val minutes = found.duration.inWholeMinutes.toInt()
        if (minutes !in 1..60) return null
        return MatchResult(LocalIntent.SnoozeAlarm(minutes), 1f)
    }
}
