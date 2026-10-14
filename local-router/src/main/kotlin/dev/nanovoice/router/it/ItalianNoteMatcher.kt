package dev.nanovoice.router.it

import dev.nanovoice.router.LocalIntent
import dev.nanovoice.router.LocalMatcher
import dev.nanovoice.router.MatchResult

/**
 * "prendi nota che domani passa il tecnico", "scrivi una nota: comprare il latte", "annota numero del garage 3".
 * Unlike the other matchers the content is taken from the original text, not the normalised one, so capitals and
 * punctuation survive into the note.
 */
object ItalianNoteMatcher : LocalMatcher {
    private val trigger = Regex(
        "^\\s*(?:(?:ehi|senti|scusa|dai|ok)[,\\s]+)*" +
            "(?:prendi(?:mi)?\\s+(?:una\\s+)?nota|annota(?:mi)?|scrivi(?:mi)?\\s+(?:una|la|nella|nelle|sulle|tra\\s+le)?\\s*not[ae]|" +
            "aggiungi\\s+(?:una|alle|nelle|tra\\s+le)?\\s*not[ae]|salva\\s+(?:una|nelle|tra\\s+le)?\\s*not[ae])\\b" +
            "(?:\\s+(?:su|in)\\s+(?:nextcloud\\s+)?notes)?" +
            "(?:\\s*[:,.\\-]|\\s+che\\b|\\s+di\\b)?\\s*(.+?)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    override fun match(text: String): MatchResult? {
        val content = trigger.find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.') ?: return null
        if (content.length < 2) return null
        return MatchResult(LocalIntent.Note(content.replaceFirstChar { it.uppercase() }), 0.9f)
    }
}
