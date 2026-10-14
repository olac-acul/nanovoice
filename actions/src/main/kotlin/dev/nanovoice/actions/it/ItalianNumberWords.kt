package dev.nanovoice.actions.it

/** Italian words for 0..99; larger numbers fall back to digits. */
internal object ItalianNumberWords {
    private val small = listOf(
        "zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove", "dieci",
        "undici", "dodici", "tredici", "quattordici", "quindici", "sedici", "diciassette", "diciotto", "diciannove",
    )
    private val tens = listOf("", "", "venti", "trenta", "quaranta", "cinquanta", "sessanta", "settanta", "ottanta", "novanta")

    fun of(n: Int): String {
        if (n < 0 || n > 99) return n.toString()
        if (n < 20) return small[n]
        val ten = tens[n / 10]
        val unit = n % 10
        return when (unit) {
            0 -> ten
            1, 8 -> ten.dropLast(1) + small[unit] // ventuno, ventotto
            3 -> ten + "tré"
            else -> ten + small[unit]
        }
    }
}
