package dev.nanovoice.router.it

import dev.nanovoice.router.LocalIntent
import dev.nanovoice.router.LocalMatcher
import dev.nanovoice.router.MatchResult
import dev.nanovoice.router.TextNormalizer

/** "che giorno è oggi", "che data è", "dimmi la data", "quanti ne abbiamo", with polite fillers around. */
object ItalianDateMatcher : LocalMatcher {
    private const val PREFIX =
        "(?:(?:ciao|ehi|senti|scusa|scusami|per favore|per piacere|mi|puoi|potresti|sai|sapresti|dirmi|dire|dimmi|dici|vorrei sapere|vorrei) )*"
    private const val SUFFIX = "(?: (?:di oggi|oggi|adesso|ora|per favore|per piacere|grazie))*"
    private const val CORE =
        "(?:che giorno e|che giorno abbiamo|che giorno della settimana e|che data e|che data abbiamo|che data c e|" +
            "in che giorno siamo|(?:dimmi|dirmi|dici|dire|dammi|sapere) la data|la data|quanti ne abbiamo)"

    private val pattern = Regex("^$PREFIX$CORE$SUFFIX$")

    override fun match(text: String): MatchResult? =
        if (pattern.matches(TextNormalizer.normalize(text))) MatchResult(LocalIntent.TellDate, 1f) else null
}
