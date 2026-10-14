package dev.nanovoice.voice

/**
 * Synthesized sentences kept in memory, per voice. Synthesis costs about 0.8 s per sentence however short it is,
 * so phrases that come back (the fixed ones above all) are played instantly. Pinned entries (the fixed phrases,
 * synthesized in advance) are never evicted; the rest is a small least-recently-used cache.
 */
internal class PhraseCache(private val maxEntries: Int = 32) {
    class Audio(val samples: FloatArray, val sampleRate: Int)

    private val recent = object : LinkedHashMap<String, Audio>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Audio>) = size > maxEntries
    }
    private val pinned = HashMap<String, Audio>()

    @Synchronized
    fun get(voiceId: String, text: String): Audio? = pinned[key(voiceId, text)] ?: recent[key(voiceId, text)]

    @Synchronized
    fun put(voiceId: String, text: String, audio: Audio) {
        if (key(voiceId, text) !in pinned) recent[key(voiceId, text)] = audio
    }

    @Synchronized
    fun pin(voiceId: String, text: String, audio: Audio) {
        recent.remove(key(voiceId, text))
        pinned[key(voiceId, text)] = audio
    }

    @Synchronized
    fun isPinned(voiceId: String, text: String) = key(voiceId, text) in pinned

    @Synchronized
    fun clearVoice(voiceId: String) {
        val prefix = "$voiceId\u0000"
        recent.keys.removeAll { it.startsWith(prefix) }
        pinned.keys.removeAll { it.startsWith(prefix) }
    }

    private fun key(voiceId: String, text: String) = "$voiceId\u0000$text"
}
