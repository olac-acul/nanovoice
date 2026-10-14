package dev.nanovoice.actions.photo

/**
 * Text written by others (Open Food Facts entries anyone can edit, Pl@ntNet common names) on its way to the agent:
 * one line, no control characters, nothing that looks like the channel's markers, and short.
 */
internal fun thirdPartyText(raw: String, max: Int): String {
    val oneLine = raw.replace(Regex("[\\p{Cntrl}\\u2028\\u2029]+"), " ")
        .replace("[[", "[ [").replace("]]", "] ]")
        .replace(Regex("\\s+"), " ").trim()
    return if (oneLine.length > max) oneLine.take(max).trimEnd() + "…" else oneLine
}
