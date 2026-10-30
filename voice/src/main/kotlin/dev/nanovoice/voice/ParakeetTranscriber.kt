package dev.nanovoice.voice

import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Transcribes a whole recorded utterance with Parakeet, more accurately than Vosk does while listening. The model
 * takes about 700 MB of RAM, so it is loaded when listening starts and freed after a few idle minutes.
 */
class ParakeetTranscriber(private val installer: ModelInstaller) {
    // Normal priority. A background one put the loading and the model's own threads (they inherit it) on the slow
    // cores only: 21 s to load and the text late after the person stopped speaking. The microphone's reader runs at
    // audio priority, so it is not starved.
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "parakeet").apply { isDaemon = true }
    }
    private var recognizer: Pair<File, OfflineRecognizer>? = null
    private var unloadTask: ScheduledFuture<*>? = null

    fun isAvailable() = installer.modelDir() != null

    /** Starts loading in the background, so the model is ready when the user stops speaking. */
    fun warmUp() {
        if (!isAvailable()) return
        worker.execute { runCatching { load() }.onFailure { Log.w(TAG, "cannot load the model", it) } }
    }

    /** The text of 16 kHz mono [samples], or null if the model is missing or fails. Blocks: call it off the main thread. */
    fun transcribe(samples: FloatArray): String? {
        if (!isAvailable() || samples.isEmpty()) return null
        return runCatching {
            worker.submit<String> {
                val started = SystemClock.elapsedRealtime()
                val rec = load()
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    rec.decode(stream)
                    rec.getResult(stream).text.trim()
                } finally {
                    stream.release()
                    Log.i(TAG, "transcribed ${samples.size * 1000L / SAMPLE_RATE} ms of audio in ${SystemClock.elapsedRealtime() - started} ms")
                }
            }.get()
        }.onFailure { Log.w(TAG, "transcription failed", it) }.getOrNull()
    }

    /** Runs on [worker] only. */
    private fun load(): OfflineRecognizer {
        val dir = installer.modelDir() ?: error("Parakeet model not installed")
        scheduleUnload()
        recognizer?.takeIf { it.first == dir }?.let { return it.second }
        release()
        val started = SystemClock.elapsedRealtime()
        val model = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = File(dir, "encoder.int8.onnx").path,
                decoder = File(dir, "decoder.int8.onnx").path,
                joiner = File(dir, "joiner.int8.onnx").path,
            ),
            tokens = File(dir, "tokens.txt").path,
            modelType = "nemo_transducer",
            numThreads = THREADS,
            debug = false,
            provider = "cpu",
        )
        val rec = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = model))
        Log.i(TAG, "model loaded in ${SystemClock.elapsedRealtime() - started} ms")
        recognizer = dir to rec
        return rec
    }

    private fun scheduleUnload() {
        unloadTask?.cancel(false)
        unloadTask = worker.schedule({ release() }, IDLE_UNLOAD_MINUTES, TimeUnit.MINUTES)
    }

    private fun release() {
        recognizer?.second?.release()
        recognizer = null
    }

    private companion object {
        const val TAG = "Parakeet"
        const val SAMPLE_RATE = 16_000
        const val THREADS = 4
        const val IDLE_UNLOAD_MINUTES = 3L
    }
}
