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
    private val glue = setOf("a", "al", "alla", "allo", "ai", "il", "lo", "la", "numero", "di", "una", "chiamata", "telefonata")
    /** "chiama un taxi", "un'ambulanza": a kind of service, not someone in the address book. */
    private val indefinite = setOf("un", "una", "uno")
    /** A clause instead of a name: "chiama il numero che ti ho dato". */
    private val clauseWords = setOf("che", "ti", "mi", "ho", "hai", "quello", "quella", "questo", "questa", "dato", "detto", "scritto")
    private val closing = setOf("per", "favore", "piacere", "grazie", "ora", "adesso", "subito")
    private val notTargets = setOf("me", "te", "lui", "lei", "chiunque", "qualcuno", "nessuno")

    override fun match(text: String): MatchResult? {
        val tokens = ItalianDurationParser.tokenize(text)
        // "fai/fare una chiamata a Mario" has no call verb: the noun "chiamata" does the same job
        val at = tokens.indexOfFirst { it in callVerbs || it == "chiamata" || it == "telefonata" }
        if (at < 0) return null
        val before = tokens.take(at)
        if (!before.all { it in fillers || it == "fai" || it == "fare" || it == "una" || it == "fammi" }) return null
        // The noun needs "fai una chiamata a ...": "chiamata persa" is about a call, not a request to make one.
        if (tokens[at] !in callVerbs && before.none { it == "fai" || it == "fare" || it == "fammi" }) return null
        if (tokens[at] in callVerbs && tokens.getOrNull(at + 1) in indefinite) return null
        val target = tokens.drop(at + 1).dropWhile { it in glue }.dropLastWhile { it in closing }
        if (target.isEmpty() || target.size > 4 || target.any { it in notTargets || it in callVerbs || it in clauseWords }) return null
        val digits = target.joinToString("")
        return if (digits.all { it.isDigit() }) MatchResult(LocalIntent.Call(digits), 0.9f)
        else MatchResult(LocalIntent.Call(target.joinToString(" ")), 0.9f)
    }
}
