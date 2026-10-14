package dev.nanovoice.router.it

import dev.nanovoice.router.LocalIntent
import dev.nanovoice.router.LocalMatcher
import dev.nanovoice.router.MatchResult

/**
 * "apri Signal", "apri l'app della fotocamera", "avvia la calcolatrice", with polite fillers. The name is whatever
 * follows; whether such an app exists is decided when the action runs. Things that are clearly not apps
 * ("apri la porta", "apri il file") are not recognized and go to the agent.
 */
object ItalianOpenAppMatcher : LocalMatcher {
    private val fillers = setOf("ciao", "ehi", "senti", "scusa", "scusami", "per", "favore", "piacere", "puoi", "potresti", "vorrei", "mi", "ti")
    private val verbs = setOf("apri", "aprire", "aprimi", "avvia", "avviare", "lancia", "lanciare", "apriamo")
    private val glue = setOf("l", "la", "il", "lo", "un", "una", "app", "applicazione", "di", "dell", "della", "del", "dello", "programma")
    private val notApps = setOf(
        "porta", "finestra", "cancello", "garage", "luce", "tapparella", "serranda", "file", "documento", "link", "sito", "pagina",
        "cartella", "conto", "ticket", "segnalazione",
    )
    private val closing = setOf("per", "favore", "piacere", "grazie", "ora", "adesso", "subito")

    override fun match(text: String): MatchResult? {
        val tokens = ItalianDurationParser.tokenize(text)
        val verbAt = tokens.indexOfFirst { it in verbs }
        if (verbAt < 0 || !tokens.take(verbAt).all { it in fillers }) return null
        val name = tokens.drop(verbAt + 1).dropWhile { it in glue }.dropLastWhile { it in closing }
        if (name.isEmpty() || name.size > 4 || name.any { it in notApps || it in verbs }) return null
        return MatchResult(LocalIntent.OpenApp(name.joinToString(" ")), 0.9f)
    }
}
