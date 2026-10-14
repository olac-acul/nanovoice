package dev.nanovoice.router.it

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A small linear classifier for requests the rules do not recognise, worded freely ("fai partire sul telefono un
 * conto alla rovescia di tre minuti"). Trained offline on synthetic Italian sentences (tools/intent_classifier);
 * on phrasings it never saw, rules plus classifier at 0.8 recognise 80 of 120 local requests (rules alone 27) with no
 * wrong local action. Features and normalisation must match train.py exactly; the parity test checks it.
 */
object ItalianIntentClassifier {
    enum class Label { TellTime, TellDate, SetTimer, SetAlarm, Other }

    private class Model(val buckets: Int, val classes: Int, val weights: FloatArray, val bias: FloatArray)

    private val model: Model by lazy { load() }
    private val marks = Regex("\\p{Mn}+")
    private val notAlnum = Regex("[^0-9a-z]+")
    private val spaces = Regex("\\s+")

    /** The most likely label and its probability. */
    fun predict(text: String): Pair<Label, Float> {
        val p = probabilities(text)
        val best = p.indices.maxBy { p[it] }
        return Label.entries[best] to p[best]
    }

    internal fun probabilities(text: String): FloatArray {
        val m = model
        val logits = DoubleArray(m.classes) { m.bias[it].toDouble() }
        for ((bucket, value) in features(text)) {
            val row = bucket * m.classes
            for (c in 0 until m.classes) logits[c] += value * m.weights[row + c]
        }
        val max = logits.max()
        val e = logits.map { exp(it - max) }
        val sum = e.sum()
        return FloatArray(m.classes) { (e[it] / sum).toFloat() }
    }

    internal fun normalize(text: String): String {
        val noMarks = marks.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")
        return spaces.replace(notAlnum.replace(noMarks, " "), " ").trim()
    }

    /** Hashed words, word bigrams and character 4-grams, L2-normalised: bucket -> weight. */
    internal fun features(text: String): Map<Int, Double> {
        val words = normalize(text).split(' ').filter { it.isNotEmpty() }
        val names = ArrayList<String>()
        words.forEach { names += "w:$it" }
        words.zipWithNext().forEach { (a, b) -> names += "b:${a}_$b" }
        val s = " " + words.joinToString(" ") + " "
        for (i in 0..s.length - 4) names += "c:" + s.substring(i, i + 4)
        val counts = HashMap<Int, Double>()
        for (name in names) {
            val k = (fnv1a(name).toLong() and 0xFFFFFFFFL).rem(model.buckets).toInt()
            counts[k] = (counts[k] ?: 0.0) + 1.0
        }
        val norm = sqrt(counts.values.sumOf { it * it }).takeIf { it > 0 } ?: 1.0
        return counts.mapValues { it.value / norm }
    }

    private fun fnv1a(s: String): Int {
        var h = 0x811C9DC5.toInt()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xFF)
            h *= 0x01000193
        }
        return h
    }

    private fun load(): Model {
        val stream = ItalianIntentClassifier::class.java.getResourceAsStream("/dev/nanovoice/router/it/intent-it.bin")
            ?: error("intent-it.bin missing from the resources")
        val bytes = DataInputStream(stream).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val buckets = buf.int
        val classes = buf.int
        require(classes == Label.entries.size) { "model has $classes classes, expected ${Label.entries.size}" }
        val weights = FloatArray(buckets * classes) { buf.float }
        val bias = FloatArray(classes) { buf.float }
        return Model(buckets, classes, weights, bias)
    }
}
