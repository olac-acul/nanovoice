package dev.nanovoice.voice

import kotlin.math.abs

/**
 * The voice puts silence before and after every piece it makes. A reply is said in several pieces, so those silences
 * add up to a pause at each cut, on top of any wait for the next piece: they are cut down to a short lead-in and the
 * length of a breath.
 */
internal object Silence {
    /** Under this share of the loudest sample, a sample counts as silence. */
    private const val THRESHOLD = 0.02f
    private const val LEAD_MS = 40
    private const val TAIL_MS = 120

    /** [samples] without the silence around the speech, but [LEAD_MS] before it and [TAIL_MS] after it. */
    fun trim(samples: FloatArray, sampleRate: Int): FloatArray {
        var peak = 0f
        for (s in samples) peak = maxOf(peak, abs(s))
        if (peak <= 1e-4f) return samples // all silence: nothing to measure against
        val floor = peak * THRESHOLD
        val first = samples.indexOfFirst { abs(it) > floor }
        val last = samples.indexOfLast { abs(it) > floor }
        val from = (first - LEAD_MS * sampleRate / 1000).coerceAtLeast(0)
        val to = (last + 1 + TAIL_MS * sampleRate / 1000).coerceAtMost(samples.size)
        return if (from == 0 && to == samples.size) samples else samples.copyOfRange(from, to)
    }
}
