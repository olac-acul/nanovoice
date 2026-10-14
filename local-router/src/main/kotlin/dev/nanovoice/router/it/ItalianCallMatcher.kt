package dev.nanovoice.router.it

import dev.nanovoice.router.LocalIntent
import dev.nanovoice.router.LocalMatcher
import dev.nanovoice.router.MatchResult

/**
 * "chiama Mario Rossi", "telefona a Mario", "fai una chiamata a Mario", "chiama il 112". The target is a contact
 * name (matched against the address book when the action runs) or a number written in digits.
 */
object ItalianCallMatcher : LocalMatcher {
    private val fillers = setOf("ciao", "ehi", "senti", "scusa", "scusami", "per", "favore", "piacere", "puoi", "potresti", "vorrei", "mi", "ti")
    private val callVerbs = setOf("chiama", "chiamare", "telefona", "telefonare")
    private val glue = setOf("a", "al", "il", "lo", "la", "numero", "di", "una", "chiamata", "telefonata")
    private val closing = setOf("per", "favore", "piacere", "grazie", "ora", "adesso", "subito")
    private val notTargets = setOf("me", "te", "lui", "lei", "chiunque", "qualcuno", "nessuno")

    override fun match(text: String): MatchResult? {
        val tokens = ItalianDurationParser.tokenize(text)
        // "fai/fare una chiamata a Mario" has no call verb: the noun "chiamata" does the same job
        val at = tokens.indexOfFirst { it in callVerbs || it == "chiamata" || it == "telefonata" }
        if (at < 0) return null
        val before = tokens.take(at)
        if (!before.all { it in fillers || it == "fai" || it == "fare" || it == "una" || it == "fammi" }) return null
        val target = tokens.drop(at + 1).dropWhile { it in glue }.dropLastWhile { it in closing }
        if (target.isEmpty() || target.size > 4 || target.any { it in notTargets || it in callVerbs }) return null
        val digits = target.joinToString("")
        return if (digits.all { it.isDigit() }) MatchResult(LocalIntent.Call(digits), 0.9f)
        else MatchResult(LocalIntent.Call(target.joinToString(" ")), 0.9f)
    }
}
