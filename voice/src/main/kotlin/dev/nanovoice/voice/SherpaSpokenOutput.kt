package dev.nanovoice.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline text-to-speech with a Piper voice run by sherpa-onnx. Sentences are synthesized one ahead of the one
 * being played, so the pause between them stays short. The voice follows [preferredVoice] and the library:
 * installing a first voice makes it ready, deleting the active one falls back to another installed voice.
 */
class SherpaSpokenOutput(
    context: Context,
    private val library: VoiceLibrary,
    preferredVoice: Flow<String>,
    private val scope: CoroutineScope,
    /** Fixed phrases synthesized as soon as a voice is ready, so they play with no delay. */
    private val warmPhrases: List<String> = emptyList(),
) : SpokenOutput {
    private class Loaded(val id: String, val tts: OfflineTts)
    private class Item(val epoch: Int, val text: String)
    private class Clip(val epoch: Int, val samples: FloatArray, val sampleRate: Int)

    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    // USAGE_MEDIA, not USAGE_ASSISTANT: assistant audio plays on its own stream with a separate, often low,
    // volume that the hardware buttons do not control. As media it follows the volume the user already uses.
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private val _status = MutableStateFlow(TtsStatus.Initializing)
    override val status: StateFlow<TtsStatus> = _status

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking

    private val engineLock = Any()
    private val cache = PhraseCache()
    private var engine: Loaded? = null

    /** Bumped by [stop]: queued sentences and clips of an older epoch are dropped. */
    private val epoch = AtomicInteger(0)

    /** Sentences accepted and not yet finished (queued, being synthesized or playing). */
    private val pending = AtomicInteger(0)

    private val texts = Channel<Item>(Channel.UNLIMITED)
    private val clips = Channel<Clip>(1)
    /** Nothing is left to say: the playing coroutine releases the kept track (it alone touches it). */
    private val idle = Channel<Unit>(Channel.CONFLATED)
    /** [begin] and [done] change the count and the audio focus together, so neither can undo the other's change. */
    private val focusLock = Any()

    /**
     * The output track. One is kept for all the sentences of a reply (same sample rate, no stop in between), so the
     * next sentence starts without building and starting a new track; it is released when nothing is left to say.
     * Used by the playing coroutine only, except [stop], which pauses and flushes it.
     */
    @Volatile private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackEpoch = -1
    /** Frames written to [track] since it was made: where the playback head is when everything written was heard. */
    private var trackFrames = 0L

    init {
        scope.launch(Dispatchers.Default) {
            combine(preferredVoice, library.states) { preferred, _ -> library.choose(preferred)?.id }
                .distinctUntilChanged()
                .collect { id -> load(id) }
        }
        scope.launch(Dispatchers.Default) { for (item in texts) synthesize(item) }
        scope.launch(Dispatchers.IO) {
            while (true) {
                select<Unit> {
                    clips.onReceive { play(it) }
                    idle.onReceive { if (pending.get() == 0) releaseTrack() }
                }
            }
        }
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        texts.trySend(Item(begin(), text.trim()))
    }

    /**
     * Plays [text] with an installed voice that need not be the active one, so voices can be compared.
     * A voice other than the active one is loaded just for this and released right after.
     */
    fun preview(voiceId: String, text: String) {
        val spec = library.spec(voiceId) ?: return
        val dir = library.modelDir(voiceId) ?: return
        stop()
        val myEpoch = begin()
        scope.launch(Dispatchers.Default) {
            val previewStart = System.nanoTime()
            val audioOut = runCatching {
                synchronized(engineLock) {
                    val active = engine?.takeIf { it.id == voiceId }?.tts
                    if (active != null) {
                        active.generate(text = text, sid = 0, speed = 1.0f)
                    } else {
                        val tts = createTts(dir, spec)
                        try { tts.generate(text = text, sid = 0, speed = 1.0f) } finally { tts.release() }
                    }
                }
            }.onFailure { Log.w(TAG, "preview of $voiceId failed", it) }.getOrNull()
            if (audioOut == null || audioOut.samples.isEmpty() || myEpoch != epoch.get()) return@launch done(myEpoch)
            Log.d(TAG, "preview $voiceId: ${audioOut.samples.size} samples at ${audioOut.sampleRate} Hz in ${(System.nanoTime() - previewStart) / 1_000_000} ms")
            Loudness.normalize(audioOut.samples)
            clips.send(Clip(myEpoch, audioOut.samples, audioOut.sampleRate))
        }
    }

    /** One more sentence to say: counted, with the audio focus taken for the first one. Returns its epoch. */
    private fun begin(): Int = synchronized(focusLock) {
        if (pending.getAndIncrement() == 0) {
            audio.requestAudioFocus(focusRequest)
            _speaking.value = true
        }
        epoch.get()
    }

    override fun stop() {
        synchronized(focusLock) {
            epoch.incrementAndGet()
            // Silent now, also for the screen: a sentence still being synthesized (seconds, for a long one) ends by
            // itself later and no longer counts, so the orb does not go on "speaking" after the stop.
            if (pending.getAndSet(0) > 0) {
                audio.abandonAudioFocusRequest(focusRequest)
                _speaking.value = false
            }
        }
        while (texts.tryReceive().getOrNull() != null) Unit
        while (clips.tryReceive().getOrNull() != null) Unit
        track?.runCatching { pause(); flush() }
        idle.trySend(Unit)
    }

    override fun shutdown() {
        stop()
        synchronized(engineLock) {
            engine?.tts?.release()
            engine = null
        }
    }

    private fun load(voiceId: String?) {
        synchronized(engineLock) {
            if (engine?.id == voiceId) return
            engine?.let {
                it.tts.release()
                cache.clearVoice(it.id) // its phrases, the pinned ones too, are of no use to another voice
            }
            engine = null
            if (voiceId == null) {
                _status.value = TtsStatus.NeedsVoice
                return
            }
            _status.value = TtsStatus.Initializing
            val spec = library.spec(voiceId)
            val dir = library.modelDir(voiceId)
            if (spec == null || dir == null) {
                _status.value = TtsStatus.NeedsVoice
                return
            }
            try {
                engine = Loaded(voiceId, createTts(dir, spec))
                _status.value = TtsStatus.Ready
                Log.i(TAG, "voice $voiceId loaded")
                prewarm(voiceId)
            } catch (e: Throwable) {
                Log.e(TAG, "cannot load voice $voiceId", e)
                _status.value = TtsStatus.Failed
            }
        }
    }

    /** Synthesizes the fixed phrases in the background, one at a time and only while nothing else needs the engine. */
    private fun prewarm(voiceId: String) {
        if (warmPhrases.isEmpty()) return
        scope.launch(Dispatchers.Default) {
            for (text in warmPhrases) {
                if (pending.get() > 0) return@launch // real speech has priority; these get cached when needed
                if (cache.isPinned(voiceId, text)) continue
                val started = System.nanoTime()
                val audioOut = synchronized(engineLock) {
                    engine?.takeIf { it.id == voiceId }?.tts?.let { runCatching { it.generate(text = text, sid = 0, speed = 1.0f) }.getOrNull() }
                } ?: return@launch
                Loudness.normalize(audioOut.samples)
                cache.pin(voiceId, text, PhraseCache.Audio(audioOut.samples, audioOut.sampleRate))
                Log.d(TAG, "warmed a fixed phrase (${text.length} chars) in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
        }
    }

    private fun createTts(dir: File, spec: VoiceSpec): OfflineTts {
        val dataDir = library.dataDir() ?: throw java.io.IOException("espeak phoneme data is missing")
        val vits = OfflineTtsVitsModelConfig(
            model = File(dir, spec.onnxName).path,
            lexicon = "",
            tokens = File(dir, "tokens.txt").path,
            dataDir = dataDir.path,
            dictDir = "",
            noiseScale = spec.noiseScale,
            noiseScaleW = spec.noiseScaleW,
            lengthScale = 1.0f,
        )
        val model = OfflineTtsModelConfig(vits = vits, numThreads = SYNTH_THREADS, debug = false, provider = "cpu")
        return OfflineTts(config = OfflineTtsConfig(model = model, maxNumSentences = 1))
    }

    private suspend fun synthesize(item: Item) {
        if (item.epoch != epoch.get()) return done(item.epoch)
        // A voice may still be loading (first sentence right after startup): wait for it, but not forever.
        if (_status.value == TtsStatus.Initializing) {
            kotlinx.coroutines.withTimeoutOrNull(LOAD_WAIT_MS) { _status.first { it != TtsStatus.Initializing } }
        }
        val startedAt = System.nanoTime()
        val voice = synchronized(engineLock) { engine?.id }
        val cached = voice?.let { cache.get(it, item.text) }
        if (cached != null) {
            Log.d(TAG, "voice=$voice ${item.text.length} chars -> cache hit") // never the text: it is what the person hears
            clips.send(Clip(item.epoch, cached.samples, cached.sampleRate))
            return
        }
        // The voice that actually speaks, read under the same lock: a switch in between must not file this audio
        // under the other voice.
        val (spokenBy, audioOut) = synchronized(engineLock) {
            val current = engine
            current?.id to current?.tts?.let { tts ->
                runCatching { tts.generate(text = item.text, sid = 0, speed = 1.0f) }
                    .onFailure { Log.w(TAG, "synthesis failed", it) }
                    .getOrNull()
            }
        }
        Log.d(TAG, "voice=$voice ${item.text.length} chars -> ${audioOut?.samples?.size} samples in ${(System.nanoTime() - startedAt) / 1_000_000} ms")
        if (audioOut == null || audioOut.samples.isEmpty() || item.epoch != epoch.get()) return done(item.epoch)
        Loudness.normalize(audioOut.samples)
        if (spokenBy != null) cache.put(spokenBy, item.text, PhraseCache.Audio(audioOut.samples, audioOut.sampleRate))
        clips.send(Clip(item.epoch, audioOut.samples, audioOut.sampleRate))
    }

    private suspend fun play(clip: Clip) {
        if (clip.epoch != epoch.get()) return done(clip.epoch)
        val audioTrack = outputTrack(clip)
        try {
            var offset = 0
            val chunk = clip.sampleRate / 5
            while (offset < clip.samples.size && clip.epoch == epoch.get()) {
                val n = audioTrack.write(clip.samples, offset, minOf(chunk, clip.samples.size - offset), AudioTrack.WRITE_BLOCKING)
                if (n <= 0) break
                if (offset == 0) SpeechLatency.firstAudio()
                offset += n
            }
            trackFrames += offset
            val heard = trackFrames
            // Bounded by what is left to play: a head that stops moving (an audio route change, a dead track) must not
            // leave the app "speaking" for ever, waiting to listen for an answer.
            val leftMs = (heard - audioTrack.playbackHeadPosition.toLong()).coerceAtLeast(0) * 1000 / clip.sampleRate
            withTimeoutOrNull(leftMs + HEAD_WAIT_MARGIN_MS) {
                while (clip.epoch == epoch.get() && audioTrack.playbackHeadPosition.toLong() < heard) delay(15)
            }
        } finally {
            done(clip.epoch)
            // Kept for the next sentence of this reply; released when the reply is over or was stopped.
            if (pending.get() == 0 || clip.epoch != epoch.get()) releaseTrack()
        }
    }

    /** The track for [clip]: the one already playing if it fits (same rate, nothing stopped since), otherwise a new one. */
    private fun outputTrack(clip: Clip): AudioTrack {
        track?.takeIf { trackRate == clip.sampleRate && trackEpoch == clip.epoch }?.let { return it }
        releaseTrack()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(clip.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(clip.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, clip.sampleRate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        trackRate = clip.sampleRate
        trackEpoch = clip.epoch
        trackFrames = 0L
        track = audioTrack
        audioTrack.play()
        return audioTrack
    }

    private fun releaseTrack() {
        val audioTrack = track ?: return
        track = null
        runCatching { audioTrack.stop() }
        audioTrack.release()
    }

    /** A sentence of [epoch] is over (played, dropped or failed). One from before a [stop] was uncounted by it. */
    private fun done(epoch: Int) {
        synchronized(focusLock) {
            if (epoch == this.epoch.get()) {
                if (pending.updateAndGet { (it - 1).coerceAtLeast(0) } != 0) return
                audio.abandonAudioFocusRequest(focusRequest)
                _speaking.value = false
            }
        }
        // The last sentence may have ended without playing (failed or empty synthesis): the track is not kept for it.
        idle.trySend(Unit)
    }

    private companion object {
        const val TAG = "SherpaSpokenOutput"
        const val LOAD_WAIT_MS = 15_000L
        const val HEAD_WAIT_MARGIN_MS = 1_500L
        const val SYNTH_THREADS = 4
    }
}
