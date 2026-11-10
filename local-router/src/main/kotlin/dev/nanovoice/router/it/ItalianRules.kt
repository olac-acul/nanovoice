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

    /**
     * The answer to "a che ora?": a time of day with only a few words around it ("alle sette", "mettine una alle 7",
     * "sette di sera"). Anything more ("tra due ore", "lunedì alle otto", "no, lascia stare") is not taken as the answer:
     * it goes through [LocalRouter.route] as a new request, with all its checks.
     */
    override fun parseClockReply(text: String): ClockTime? {
        if (refusesSetting(text)) return null
        val tokens = ItalianDurationParser.tokenize(text)
        // A time from now, a day the clock app cannot set, a repeating alarm: not an answer to "at what time".
        if (tokens.any { it in relativeWords || it in otherDays || it in repeatWords }) return null
        val (start, found) = alarmTime(tokens) ?: return null
        if (tokens.getOrNull(found.endIndex) in durationUnits) return null // "due ore": a duration
        if (dateAfter(tokens, found.endIndex)) return null // "alle 7 del 12"
        if (asksSomething(tokens, start, found.endIndex, replyWords)) return null
        return ItalianClockTimeParser.withDayPart(tokens, found.time)
    }

    override fun mentionsTomorrow(text: String): Boolean = ItalianDurationParser.tokenize(text).any { it in tomorrowWords }

    /** "tra due ore", "fra un'ora": from now, not a time of day. */
    private val relativeWords = setOf("tra", "fra")
    private val durationUnits = setOf("ora", "ore", "minuto", "minuti", "secondo", "secondi", "mezzora")

    /** A few words of an answer that ask for nothing else ("mettine una alle sette", "facciamo le otto"). */
    private val replyWords by lazy {
        fillers + ItalianClockTimeParser.dayPartWords + setOf(
            "metti", "mettine", "mettila", "mettilo", "mettimela", "mettimelo", "imposta", "impostala", "impostalo",
            "fai", "facciamo", "direi", "va", "bene", "allora", "sveglia", "timer", "no", "anzi",
        )
    }

    /**
     * The time of an alarm in a sentence: the one after "alle", "per le", "verso le"; else the first number that is not
     * the article "una" ("una sveglia alle 7" is at 7, not at one).
     */
    private fun alarmTime(tokens: List<String>): Pair<Int, ItalianClockTimeParser.Found>? {
        val candidates = tokens.indices.filter { ItalianClockTimeParser.parse(tokens, it) != null }
        val start = candidates.firstOrNull { i -> tokens[i] in timeIntroducers || (i > 0 && tokens[i - 1] in timeIntroducers) }
            ?: candidates.firstOrNull { tokens[it] !in setOf("un", "una", "uno") }
            ?: return null
        return ItalianClockTimeParser.parse(tokens, start)?.let { start to it }
    }

    /** A number after the time ("del 12", "il 12 novembre"): a date the clock app cannot target. */
    private fun dateAfter(tokens: List<String>, end: Int): Boolean =
        (end until tokens.size).any { ItalianNumberParser.parse(tokens, it) != null }

    private val tomorrowWords = setOf("domani", "domattina")
    private val otherDays = setOf("dopodomani", "lunedi", "martedi", "mercoledi", "giovedi", "venerdi", "sabato", "domenica")
    /** A second request in the same sentence: the agent can do both, the phone only one. */
    private val chainWords = setOf("poi", "dopo", "inoltre", "anche")
    /** Words that ask for nothing: a sentence of just these plus a duration or a time is not a request. */
    private val fillers = setOf(
        "di", "da", "per", "a", "alle", "le", "un", "una", "uno", "il", "lo", "la", "e", "ok", "si", "grazie", "favore",
        "piacere", "ehi", "dai", "senti", "scusa", "domani", "domattina", "stasera", "oggi", "tra", "fra",
    )

    private fun asksSomething(tokens: List<String>, slotStart: Int, slotEnd: Int, allowed: Set<String> = fillers) =
        tokens.filterIndexed { i, _ -> i < slotStart || i >= slotEnd }.any { it !in allowed }

    /**
     * Told, not asked: in the past ("mi sono svegliato alle sette"), about someone else ("il cane mi sveglia alle 5"),
     * a judgement ("era troppo presto"), or one's own plans ("tra 10 minuti esco"). The classifier hears a timer or an
     * alarm in these; the agent gets them instead.
     */
    private val toldNotAsked = setOf(
        "svegliato", "svegliata", "svegliati", "svegliate", "sveglio", "era", "erano", "ieri", "sempre", "troppo",
        "benissimo", "esco", "usciamo", "arrivo", "arriviamo", "parto", "partiamo", "torno", "torniamo", "vado", "andiamo",
    )

    private fun toldNotAsked(tokens: List<String>) =
        tokens.any { it in toldNotAsked } || tokens.indices.any { i -> tokens[i] == "mi" && tokens.getOrNull(i + 1) == "sveglia" }

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
            ItalianIntentClassifier.Label.SetTimer, ItalianIntentClassifier.Label.SetAlarm ->
                if (toldNotAsked(tokens)) null else setIntent(label, tokens)
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
                val (start, found) = alarmTime(tokens) ?: return null
                if (tokens.any { it in otherDays || it in repeatWords }) null
                else if (dateAfter(tokens, found.endIndex)) null // "sveglia alle 7 del 12": a day it cannot set
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

    /**
     * The answer to "per quanto tempo?": a duration with only a few words around it ("dieci minuti", "per cinque
     * minuti"). "ricordami tra 10 minuti di chiamare Marco" or "no, annulla" are not the answer: a new request.
     */
    override fun parseDurationReply(text: String): Duration? {
        if (refusesSetting(text)) return null
        val tokens = ItalianDurationParser.tokenize(text)
        if (tokens.any { it in chainWords }) return null
        val found = ItalianDurationParser.find(tokens) ?: return null
        if (ItalianDurationParser.find(tokens.drop(found.endIndex)) != null) return null
        if (asksSomething(tokens, found.startIndex, found.endIndex, replyWords)) return null
        return found.duration.takeIf { it > Duration.ZERO }
    }
}
