package dev.nanovoice.router

import dev.nanovoice.router.it.ItalianRules
import java.util.Locale

class LocalRouter(
    private val rules: LocaleRules,
    private val confidenceThreshold: Float = DEFAULT_THRESHOLD,
) {
    /**
     * Best match at or above the confidence threshold, or null (the request should go to the agent). The rules come
     * first; the classifier only gets the sentences they do not recognise.
     */
    fun route(text: String): MatchResult? {
        val result = rules.matchers.mapNotNull { it.match(text) }
            .filter { it.confidence >= confidenceThreshold }
            .maxByOrNull { it.confidence }
            ?: rules.classify(text)?.takeIf { it.confidence >= maxOf(confidenceThreshold, CLASSIFIER_THRESHOLD) }
        val sets = result?.intent is LocalIntent.SetTimer || result?.intent is LocalIntent.SetAlarm
        return if (sets && rules.refusesSetting(text)) null else result
    }

    fun parseDurationReply(text: String) = rules.parseDurationReply(text)

    fun parseClockReply(text: String) = rules.parseClockReply(text)

    companion object {
        const val DEFAULT_THRESHOLD = 0.7f

        /** Measured: at 0.8 no wrong local action on unseen phrasings; at 0.7 two in 120. */
        const val CLASSIFIER_THRESHOLD = 0.8f

        fun forLocale(locale: Locale, confidenceThreshold: Float = DEFAULT_THRESHOLD): LocalRouter? =
            when (locale.language) {
                "it" -> LocalRouter(ItalianRules, confidenceThreshold)
                else -> null
            }
    }
}
