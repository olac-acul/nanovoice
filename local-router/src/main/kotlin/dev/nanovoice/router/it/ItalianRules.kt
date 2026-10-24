package dev.nanovoice.router.it

import dev.nanovoice.router.ClockTime
import dev.nanovoice.router.LocalMatcher
import dev.nanovoice.router.LocalIntent
import dev.nanovoice.router.LocaleRules
import dev.nanovoice.router.MatchResult
import kotlin.time.Duration

object ItalianRules : LocaleRules {
    override val matchers: List<LocalMatcher> = listOf(
        ItalianTimeMatcher, ItalianDateMatcher, ItalianTimerMatcher, ItalianAlarmMatcher, ItalianCallMatcher, ItalianOpenAppMatcher, ItalianPlayMusicMatcher, ItalianNoteMatcher,
        ItalianClockControlMatcher,
    )

    override fun parseClockReply(text: String): ClockTime? {
        val tokens = ItalianDurationParser.tokenize(text)
        return ItalianClockTimeParser.find(tokens)?.time?.let { ItalianClockTimeParser.withDayPart(tokens, it) }
    }

    private val tomorrowWords = setOf("domani", "domattina")
    private val otherDays = setOf("dopodomani", "lunedi", "martedi", "mercoledi", "giovedi", "venerdi", "sabato", "domenica")
    /** A second request in the same sentence: the agent can do both, the phone only one. */
    private val chainWords = setOf("poi", "dopo", "inoltre", "anche")
    /** Words that ask for nothing: a sentence of just these plus a duration or a time is not a request. */
    private val fillers = setOf(
        "di", "da", "per", "a", "alle", "le", "un", "una", "uno", "il", "lo", "la", "e", "ok", "si", "grazie", "favore",
        "piacere", "ehi", "dai", "senti", "scusa", "domani", "domattina", "stasera", "oggi", "tra", "fra",
    )

    private fun asksSomething(tokens: List<String>, slotStart: Int, slotEnd: Int) =
        tokens.filterIndexed { i, _ -> i < slotStart || i >= slotEnd }.any { it !in fillers }

    /** Repeating alarms, which the clock intent cannot set. */
    private val repeatWords = setOf("tutti", "ogni", "feriali", "settimana", "weekend")

    /**
     * Not a request to set something: negated, cancelling, moving, or about one that exists ("hai messo la sveglia",
     * "la sveglia suona alle sette", "ho impostato un timer"). Speech recognition writes no question mark, so a
     * question has to be told by its words.
     */
    private val notASetRequest = setOf(
        "non", "annulla", "cancella", "togli", "elimina", "rimuovi", "disattiva", "spegni", "sposta", "cambia", "ferma",
        "suonato", "suonata", "manca", "mancano", "quanto", "quando", "rimasto", "rimasti", "rimane",
        "hai", "avevi", "avevo", "messo", "messa", "impostato", "impostata", "programmato", "programmata", "puntato",
        "puntata", "attivato", "attivata", "suona", "suonano", "suonera", "squilla", "c",
    )
    private val articles = setOf("un", "una", "uno", "il", "lo", "la", "l")
    private val clockNouns = setOf("sveglia", "timer", "allarme")

    /** What introduces the alarm time: "alle sette", "per le 7", "verso le otto"; never the article in "una sveglia". */
    private val timeIntroducers = setOf("alle", "all", "le", "per", "verso", "entro", "a")

    /** Words of "che ore sono" and "che giorno è": anything else (a city, an event) is a question for the agent. */
    private val timeCore = setOf(
        "che", "ora", "ore", "e", "sono", "giorno", "data", "oggi", "adesso", "ora", "quanti", "ne", "abbiamo", "siamo",
        "mi", "dici", "dimmi", "sai", "puoi", "dire", "quale", "qual", "della", "settimana", "mese", "il", "la", "l",
        "di", "per", "favore", "scusa", "ciao", "ehi", "senti", "esatta", "esatte", "precisa",
    )

    /**
     * The classifier's guess, kept only when the sentence also holds what the action needs: a duration for a timer,
     * a time the clock app can set (today or tomorrow) for an alarm. A vague sentence therefore never starts anything.
     */
    override fun classify(text: String): MatchResult? {
        val (label, p) = ItalianIntentClassifier.predict(text)
        val tokens = ItalianDurationParser.tokenize(text)
        // The same refusals the matchers make on purpose: a lone word, two requests in one, repeating alarms.
        if (tokens.size < 2 || tokens.any { it in chainWords }) return null
        val onlyAboutNow = tokens.all { it in timeCore || it in fillers }
        val intent = when (label) {
            ItalianIntentClassifier.Label.TellTime -> LocalIntent.TellTime.takeIf { onlyAboutNow }
            ItalianIntentClassifier.Label.TellDate -> LocalIntent.TellDate.takeIf { onlyAboutNow }
            ItalianIntentClassifier.Label.SetTimer, ItalianIntentClassifier.Label.SetAlarm -> setIntent(label, tokens)
            ItalianIntentClassifier.Label.Other -> null
        } ?: return null
        return MatchResult(intent, p)
    }

    private fun setIntent(label: ItalianIntentClassifier.Label, tokens: List<String>): LocalIntent? =
        when (label) {
            ItalianIntentClassifier.Label.SetTimer -> {
                val found = ItalianDurationParser.find(tokens)
                val second = found?.let { ItalianDurationParser.find(tokens.drop(it.endIndex)) }
                if (found == null || tokens.count { it == "timer" } > 1 || second != null) null
                else if (!asksSomething(tokens, found.startIndex, found.endIndex)) null // "cinque minuti" alone
                else found.duration.takeIf { it > Duration.ZERO }?.let { LocalIntent.SetTimer(it) }
            }
            ItalianIntentClassifier.Label.SetAlarm -> {
                val candidates = tokens.indices.filter { ItalianClockTimeParser.parse(tokens, it) != null }
                val start = candidates.firstOrNull { i -> tokens[i] in timeIntroducers || (i > 0 && tokens[i - 1] in timeIntroducers) }
                    ?: candidates.firstOrNull { tokens[it] !in setOf("un", "una", "uno") }
                val found = start?.let { ItalianClockTimeParser.parse(tokens, it) }
                if (found == null || tokens.any { it in otherDays || it in repeatWords }) null
                else if (!asksSomething(tokens, start, found.endIndex)) null // "alle sette" alone
                else LocalIntent.SetAlarm(ItalianClockTimeParser.withDayPart(tokens, found.time), tokens.any { it in tomorrowWords })
            }
            else -> null
        }

    override fun refusesSetting(text: String): Boolean {
        if (text.trim().endsWith("?")) return true
        val tokens = ItalianDurationParser.tokenize(text)
        if (tokens.any { it in notASetRequest }) return true
        // "ho la sveglia alle sette", "ho un timer": having one, not asking for one ("ho bisogno di una sveglia" asks)
        if (tokens.indices.any { i -> tokens[i] == "ho" && tokens.getOrNull(i + 1) in articles && tokens.getOrNull(i + 2) in clockNouns }) return true
        // "la sveglia di domani è alle sette": "è" (written "e") before the time; "sette e mezza" has it after a number
        if (tokens.indices.any { i -> tokens[i] == "e" && tokens.getOrNull(i + 1) in timeIntroducers && (i == 0 || ItalianNumberParser.parse(tokens, i - 1) == null) }) return true
        // "il timer", "la sveglia": an article and the noun alone say nothing to do ("timer" or "sveglia" alone do)
        return tokens.size == 2 && tokens[0] in articles && tokens[1] in clockNouns
    }

    override fun parseDurationReply(text: String): Duration? =
        ItalianDurationParser.find(ItalianDurationParser.tokenize(text))?.duration?.takeIf { it > Duration.ZERO }
}
