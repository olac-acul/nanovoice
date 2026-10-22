package dev.nanovoice.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Debug
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline speech recognition with a Vosk model downloaded by [ModelManager]. Records the microphone itself.
 * Vosk shows the words while the user speaks and decides when they have finished; when [refiner] is installed and
 * [refine] allows it, the recorded utterance is then transcribed again by it, which gets names and short commands
 * right far more often than the small Vosk model.
 */
class VoskSpeechToText(
    context: Context,
    private val models: ModelManager,
    private val refiner: ParakeetTranscriber? = null,
    private val refine: () -> Boolean = { true },
) : SpeechToText {
    private val appContext = context.applicationContext
    /** The stop flag of the latest listening session: each session has its own, so an old one cannot stop a new one. */
    @Volatile private var currentStop = AtomicBoolean(false)

    /** Listening sessions holding the microphone right now (one cancelled while Parakeet works may overlap a new one). */
    private val sessions = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile private var loaded: Pair<File, Model>? = null

    private val _inUse = MutableStateFlow(false)

    /** True while [start] has the microphone, so a wake-word listener knows to step aside. */
    val inUse: StateFlow<Boolean> = _inUse

    override val id = "vosk"

    override fun isAvailable() = models.modelDir() != null

    override fun start(endOnSilence: Boolean): Flow<SttEvent> = callbackFlow {
        val dir = models.modelDir()
        if (dir == null) {
            trySend(SttEvent.Error(SttError.NotAvailable, "Vosk model not installed"))
            close()
            return@callbackFlow
        }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            trySend(SttEvent.Error(SttError.PermissionDenied))
            close()
            return@callbackFlow
        }
        val stopRequested = AtomicBoolean(false).also { currentStop = it }
        _inUse.value = sessions.incrementAndGet() > 0
        val bufferSize = maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING), CHUNK_BYTES) * 2
        var record: AudioRecord? = null
        var recognizer: Recognizer? = null
        var reader: Thread? = null
        val reading = AtomicBoolean(true)
        try {
            val model = model(dir)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                trySend(SttEvent.Error(SttError.Busy, "microphone unavailable"))
                close() // a callbackFlow that returns open throws: the app would crash on a busy microphone
                return@callbackFlow
            }
            val refining = refiner != null && refine() && refiner.isAvailable()
            val audio = if (refining) java.io.ByteArrayOutputStream(MAX_UTTERANCE_BYTES / 4) else null
            record.startRecording()
            // The microphone has its own thread: whatever slows the recognizer down (Parakeet loading 700 MB right now,
            // say) must not leave the recording unread, or Android drops the first words when its small buffer fills.
            val chunks = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
            val mic = record
            reader = Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                while (reading.get()) {
                    val buf = ByteArray(CHUNK_BYTES)
                    val n = mic.read(buf, 0, buf.size)
                    if (n <= 0) break
                    chunks.put(if (n == buf.size) buf else buf.copyOf(n))
                }
                chunks.put(END_OF_AUDIO)
            }, "vosk-mic").apply { start() }
            trySend(SttEvent.Ready)
            if (refining) refiner?.warmUp() // after the microphone, at low priority: ready when the person stops

            var finished = ""
            var heardSomething = false
            val startedAt = SystemClock.elapsedRealtime()
            while (isActive && !stopRequested.get()) {
                val chunk = chunks.poll(POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if (chunk === END_OF_AUDIO) break
                val n = chunk.size
                if (audio != null && audio.size() < MAX_UTTERANCE_BYTES) audio.write(chunk, 0, n)
                if (recognizer.acceptWaveForm(chunk, n)) {
                    // Vosk saw a pause after speech: the end of an utterance.
                    finished = VoskJson.join(finished, VoskJson.text(recognizer.result)).also { trySend(SttEvent.Partial(it)) }
                    if (endOnSilence && finished.isNotEmpty()) break
                } else {
                    val partial = VoskJson.partial(recognizer.partialResult)
                    if (partial.isNotEmpty()) heardSomething = true
                    trySend(SttEvent.Partial(VoskJson.join(finished, partial)))
                }
                if (endOnSilence) {
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    if ((!heardSomething && finished.isEmpty() && elapsed > NO_SPEECH_TIMEOUT_MS) || elapsed > MAX_UTTERANCE_MS) break
                }
            }
            if (isActive) {
                val quick = VoskJson.join(finished, VoskJson.text(recognizer.finalResult))
                reading.set(false)
                runCatching { record.stop() } // free the microphone while the utterance is transcribed again
                SpeechLatency.endOfSpeech()
                trySend(SttEvent.Ended)
                val secondPass = audio != null && (quick.isNotEmpty() || heardSomething)
                val text = if (secondPass) {
                    // Interruptible: a cancelled request does not wait for Parakeet to finish.
                    runInterruptible { refiner?.transcribe(pcmToFloats(audio.toByteArray())) }?.takeIf { it.isNotBlank() } ?: quick
                } else {
                    quick
                }
                if (text.isNotEmpty()) SpeechLatency.textReady(secondPass)
                if (isActive) trySend(if (text.isEmpty()) SttEvent.Error(SttError.NoSpeech) else SttEvent.Final(text))
            }
        } catch (e: Exception) {
            if (isActive) trySend(SttEvent.Error(SttError.Other, e.message))
        } finally {
            reading.set(false)
            runCatching { record?.stop() } // ends a read in progress, so the microphone thread exits
            reader?.join(READER_JOIN_MS)
            record?.release()
            recognizer?.close()
            _inUse.value = sessions.decrementAndGet() > 0
        }
        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    override fun stopListening() = currentStop.set(true)

    /**
     * Listens for [phrase] until cancelled, with the same model as normal recognition (so no second copy in memory).
     * Do not run it together with [start]: both need the microphone.
     */
    fun listenForWakePhrase(phrase: WakePhrase = WakePhrase()): Flow<WakeEvent> = callbackFlow {
        val dir = models.modelDir()
        if (dir == null) {
            trySend(WakeEvent.Failed(SttError.NotAvailable, "Vosk model not installed"))
            close()
            return@callbackFlow
        }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            trySend(WakeEvent.Failed(SttError.PermissionDenied))
            close()
            return@callbackFlow
        }
        val bufferSize = maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING), CHUNK_BYTES) * 2
        var record: AudioRecord? = null
        var recognizer: Recognizer? = null
        try {
            recognizer = Recognizer(model(dir), SAMPLE_RATE.toFloat(), phrase.grammar()).apply { setWords(true) }
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                trySend(WakeEvent.Failed(SttError.Busy, "microphone unavailable"))
                close()
                return@callbackFlow
            }
            record.startRecording()
            trySend(WakeEvent.Listening)
            // The loop never suspends, so it stays on one thread and its CPU clock measures just this work.
            val cpuStart = Debug.threadCpuTimeNanos()
            var bytes = 0L
            var voicedBytes = 0L
            var lastUsage = SystemClock.elapsedRealtime()
            val chunk = ByteArray(CHUNK_BYTES)
            val gate = SpeechGate()
            while (isActive) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) break
                bytes += n
                // Only sound that may be speech reaches Vosk: in silence the recognizer rests.
                fun check(result: String) {
                    VoskJson.text(result).takeIf { it.isNotEmpty() }?.let { trySend(WakeEvent.Understood(it)) }
                    phrase.match(result)?.let { trySend(WakeEvent.Heard(it, SystemClock.elapsedRealtime())) }
                }
                for (part in gate.accept(chunk, n)) {
                    voicedBytes += part.size
                    if (recognizer.acceptWaveForm(part, part.size)) check(recognizer.result)
                }
                // The gate holds back the silence Vosk would use to end the phrase, so end it here.
                if (gate.justClosed) check(recognizer.finalResult)
                val now = SystemClock.elapsedRealtime()
                if (now - lastUsage >= USAGE_EVERY_MS) {
                    lastUsage = now
                    trySend(WakeEvent.Usage(bytes / 2.0 / SAMPLE_RATE, voicedBytes / 2.0 / SAMPLE_RATE, (Debug.threadCpuTimeNanos() - cpuStart) / 1_000_000))
                }
            }
        } catch (e: Exception) {
            if (isActive) trySend(WakeEvent.Failed(SttError.Other, e.message))
        } finally {
            runCatching { record?.stop() }
            record?.release()
            recognizer?.close()
        }
        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    /** Frees the loaded model (about 100 MB of RAM for the small Italian one). */
    /**
     * Loads the model in the background, so the first listening after the app starts does not wait for it (about
     * 100 MB). Cheap when it is already loaded; a no-op without a model.
     */
    fun preload() {
        val dir = models.modelDir() ?: return
        Thread({ runCatching { model(dir) } }, "vosk-preload").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    fun unload() {
        loaded?.second?.close()
        loaded = null
    }

    @Synchronized
    private fun model(dir: File): Model {
        loaded?.takeIf { it.first == dir }?.let { return it.second }
        unload()
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        return Model(dir.absolutePath).also { loaded = dir to it }
    }

    private fun pcmToFloats(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        for (i in out.indices) {
            val s = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
            out[i] = s.toShort() / 32768f
        }
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_UTTERANCE_BYTES = 31 * SAMPLE_RATE * 2
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_BYTES = 4096
        const val POLL_MS = 200L
        const val READER_JOIN_MS = 1_000L
        val END_OF_AUDIO = ByteArray(0)
        const val NO_SPEECH_TIMEOUT_MS = 7_000L
        const val MAX_UTTERANCE_MS = 30_000L
        const val USAGE_EVERY_MS = 2_000L
    }
}
