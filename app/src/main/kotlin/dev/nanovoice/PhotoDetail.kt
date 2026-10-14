package dev.nanovoice

/**
 * When a photo goes at the larger size: a question about its writing ("traducimi", "cosa c'è scritto", "il menu")
 * needs letters big enough to read; anything else is fine, and quicker for the agent, at the normal size.
 */
object PhotoDetail {
    private val ABOUT_TEXT = Regex(
        "(?<![\\p{L}])(tradu\\p{L}*|legg\\p{L}*|scritt[aoie]|scriv\\p{L}*|test[oi]|parol[ae]|men[uù]|cartell\\p{L}*|" +
            "etichett\\p{L}*|ingredient\\p{L}*|document\\p{L}*|pagin[ae]|ricett[ae]|scontrin\\p{L}*|fattur[ae]|bollett[ae]|" +
            "orari\\p{L}*|istruzion\\p{L}*|fogli[oi]?|libro|giornale|lettera|avvis[oi]|insegn[ae]|lingua|inglese|francese|" +
            "tedesco|spagnolo|giapponese|cinese|portoghese|russo|arabo|greco)(?![\\p{L}])",
        RegexOption.IGNORE_CASE,
    )

    fun asksAboutText(question: String): Boolean = ABOUT_TEXT.containsMatchIn(question)
}
