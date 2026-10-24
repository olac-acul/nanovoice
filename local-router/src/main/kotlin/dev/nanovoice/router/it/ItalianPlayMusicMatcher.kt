package dev.nanovoice.router.it

import dev.nanovoice.router.LocalIntent
import dev.nanovoice.router.LocalMatcher
import dev.nanovoice.router.MatchResult

/**
 * "riproduci Bohemian Rhapsody dei Queen", "fammi sentire qualcosa dei Pink Floyd", "metti la canzone Volare".
 * "metti" alone is too common ("metti la sveglia"), so it needs a music word after it. What follows is the search,
 * without the little words between title and artist; videos and films are left to the agent.
 */
object ItalianPlayMusicMatcher : LocalMatcher {
    private val fillers = setOf("ciao", "ehi", "senti", "scusa", "scusami", "per", "favore", "piacere", "puoi", "potresti", "vorrei", "mi", "ti", "dai")
    private val verbs = listOf(
        listOf("riproduci"), listOf("riproducimi"), listOf("suona"), listOf("suonami"), listOf("fammi", "sentire"),
        listOf("fammi", "ascoltare"), listOf("voglio", "ascoltare"), listOf("ascoltiamo"), listOf("metti"), listOf("mettimi"),
    )
    private val musicWords = setOf("canzone", "brano", "musica", "album", "playlist", "pezzo", "disco")
    private val glue = setOf("la", "il", "lo", "l", "un", "una", "po", "di", "qualcosa", "canzone", "brano", "musica", "album", "playlist", "pezzo", "disco")
    private val links = setOf("di", "dei", "del", "degli", "della", "delle", "dello")
    private val closing = setOf("per", "favore", "piacere", "grazie", "ora", "adesso", "subito", "su", "con", "tempo")
    private val notMusic = setOf(
        "sveglia", "timer", "video", "film", "serie", "episodio", "podcast",
        // things to hear that are not in the music library: the agent's, or another app's
        "notizie", "notiziario", "telegiornale", "tg", "meteo", "messaggio", "messaggi", "vocale", "vocali", "audio",
        "barzelletta", "barzellette", "storia", "favola", "radio",
        // "metti la musica in pausa", "più forte": a control of what plays, not a search
        "pausa", "ferma", "stop", "volume", "forte", "alta", "bassa", "avanti", "indietro", "prossima", "successiva", "precedente",
    )

    override fun match(text: String): MatchResult? {
        val tokens = ItalianDurationParser.tokenize(text)
        var at = 0
        while (at < tokens.size && tokens[at] in fillers) at++
        val verb = verbs.firstOrNull { tokens.subList(at, minOf(tokens.size, at + it.size)) == it } ?: return null
        var rest = tokens.drop(at + verb.size)
        if (verb.first().startsWith("mett") && rest.take(3).none { it in musicWords }) return null
        // "suona bene", "suona la campanella": "suona" plays music only when the sentence says so
        if (verb.first().startsWith("suon") && rest.take(3).none { it in musicWords || it == "po" || it == "qualcosa" }) return null
        // "una barzelletta": something said with "un", "una" and no music word after it is not a search of the library
        if (rest.firstOrNull() in setOf("un", "una") && rest.getOrNull(1) !in musicWords && rest.getOrNull(1) != "po") return null
        if (rest.any { it in notMusic }) return null
        rest = rest.dropWhile { it in glue }.dropLastWhile { it in closing }
        val query = rest.filter { it !in links }
        if (query.isEmpty() || query.size > 10) return null
        return MatchResult(LocalIntent.PlayMusic(query.joinToString(" ")), 0.9f)
    }
}
