package dev.nanovoice.router.it

import dev.nanovoice.router.LocalIntent
import dev.nanovoice.router.LocalMatcher
import dev.nanovoice.router.MatchResult
import dev.nanovoice.router.TextNormalizer

/** "che ore sono", "che ora è", "dimmi l'ora", "mi dici l'ora?", with polite fillers around. */
object ItalianTimeMatcher : LocalMatcher {
    private const val PREFIX =
        "(?:(?:ciao|ehi|senti|scusa|scusami|per favore|per piacere|mi|puoi|potresti|sai|sapresti|dirmi|dire|dimmi|dici|vorrei sapere|vorrei) )*"
    private const val SUFFIX =
        "(?: (?:adesso|ora|attualmente|esattamente|per favore|per piacere|grazie))*"
    private const val CORE =
        "(?:che ore sono|che ora e|che ora fa|(?:dimmi|dirmi|dici|dire|dammi|sapere) l ora|l ora)"

    private val pattern = Regex("^$PREFIX$CORE$SUFFIX$")

    override fun match(text: String): MatchResult? =
        if (pattern.matches(TextNormalizer.normalize(text))) MatchResult(LocalIntent.TellTime, 1f) else null
}
