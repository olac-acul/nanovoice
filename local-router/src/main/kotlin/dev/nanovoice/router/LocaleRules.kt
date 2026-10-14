package dev.nanovoice.router

import kotlin.time.Duration

/** Everything the router needs that depends on the language. Add one implementation per locale. */
interface LocaleRules {
    val matchers: List<LocalMatcher>

    /** Interprets the answer to "how long?" ("dieci minuti", "per cinque minuti"); null if not a duration. */
    fun parseDurationReply(text: String): Duration?

    /** Interprets the answer to "a che ora?" ("alle sette e mezza", "le 7:30", "sette di sera"); null if not a time. */
    fun parseClockReply(text: String): ClockTime?

    /** A second opinion for sentences no matcher recognised (a trained classifier); null when there is none. */
    fun classify(text: String): MatchResult? = null

    /**
     * The sentence is not a request to set a timer or an alarm even if it names one: negated, cancelling, moving, or a
     * question about an existing one. Those go to the agent instead of setting something new.
     */
    fun refusesSetting(text: String): Boolean = false
}
