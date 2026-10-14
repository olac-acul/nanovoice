package dev.nanovoice.voice

import kotlin.math.abs

/** Raises quiet synthesized speech towards a comfortable peak level without ever clipping or lowering it. */
internal object Loudness {
    private const val TARGET_PEAK = 0.9f
    private const val MAX_GAIN = 4f

    fun gainFor(samples: FloatArray): Float {
        var peak = 0f
        for (s in samples) peak = maxOf(peak, abs(s))
        if (peak <= 1e-4f) return 1f // silence: nothing to scale
        return (TARGET_PEAK / peak).coerceIn(1f, MAX_GAIN)
    }

    /** Scales [samples] in place by [gainFor] and returns the gain used. */
    fun normalize(samples: FloatArray): Float {
        val gain = gainFor(samples)
        if (gain != 1f) for (i in samples.indices) samples[i] *= gain
        return gain
    }
}
