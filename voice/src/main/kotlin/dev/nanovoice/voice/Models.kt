package dev.nanovoice.voice

/** The speech recognition model (Vosk, Italian, small). */
object VoskModels {
    val ITALIAN_SMALL = ModelSpec(
        url = "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip",
        sha256 = "9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267",
        sizeBytes = 49_665_141,
        dirName = "vosk-model-small-it-0.22",
    )
}

/** A single file to download, pinned by size and SHA-256. */
data class RemoteFile(val url: String, val sha256: String, val sizeBytes: Long)

/** A model published as loose files: each is downloaded and verified into [dirName]. */
data class FilesSpec(val dirName: String, val files: Map<String, RemoteFile>) {
    val sizeBytes: Long get() = files.values.sumOf { it.sizeBytes }
}

/**
 * The accurate recognizer: NVIDIA Parakeet TDT 0.6B v3 (CC BY 4.0), multilingual with Italian, int8 for sherpa-onnx.
 * Chosen over Whisper small: on the Pixel 8 it transcribes 3 s of speech in about 0.4 s, Whisper small would take
 * several seconds.
 */
object ParakeetModels {
    private const val HF = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"

    val V3_INT8 = FilesSpec(
        dirName = "parakeet-tdt-0.6b-v3-int8",
        files = mapOf(
            "encoder.int8.onnx" to RemoteFile("$HF/encoder.int8.onnx", "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247", 652_184_281),
            "decoder.int8.onnx" to RemoteFile("$HF/decoder.int8.onnx", "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e", 11_845_275),
            "joiner.int8.onnx" to RemoteFile("$HF/joiner.int8.onnx", "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3", 6_355_277),
            "tokens.txt" to RemoteFile("$HF/tokens.txt", "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d", 93_939),
        ),
    )
}

/**
 * A voice exactly as Piper publishes it (onnx + json). sherpa-onnx cannot read that as is, so the installer adds the
 * ONNX [metadata] it needs, builds tokens.txt from the json and uses the shared espeak data.
 * [licenseNote] is shown to the user before downloading.
 */
data class VoiceSpec(
    val id: String,
    val displayName: String,
    val quality: String,
    val licenseNote: String,
    val onnx: RemoteFile,
    val config: RemoteFile,
    val metadata: Map<String, String>,
    /** Directory the installed voice lives in, inside the voice's own folder. */
    val dirName: String,
    /** Model file inside that directory. */
    val onnxName: String,
    val noiseScale: Float = 0.667f,
    val noiseScaleW: Float = 0.8f,
) {
    val sizeBytes: Long get() = onnx.sizeBytes + config.sizeBytes
}

/**
 * Only Serena: it was clearly the best of the Italian voices tried (peak 0.73-0.91 and 30-48 dB speech-to-noise
 * range without any gain, against 33-36 dB for Paola, Dii and Riccardo, and a hissy, ~4x quieter Miro), so the
 * others were dropped. The catalog stays a list so another voice can be added with one entry.
 */
object VoiceCatalog {
    private const val SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    private const val PIPER_VOICES = "https://huggingface.co/rhasspy/piper-voices/resolve/main/it/it_IT"

    /** espeak-ng phoneme data shared by the voices. */
    val ESPEAK_DATA = ModelSpec(
        url = "$SHERPA/espeak-ng-data.zip",
        sha256 = "bc4525eafe31b4e3f5e43aea495f3169e97dd2544f1bbfe95514ce8a61baee39",
        sizeBytes = 9_037_020,
        dirName = "espeak-ng-data",
    )

    /** Piper 1.x voice, full-precision model: a big download and more CPU than the int8 voices. */
    val SERENA = VoiceSpec(
        id = "serena-high",
        displayName = "Serena",
        quality = "alta qualità",
        licenseNote = "Dataset sintetico CC BY 4.0 (committa/serena-synthetic-it-27h)",
        onnx = RemoteFile("$PIPER_VOICES/serena/high/it_IT-serena-high.onnx", "7462fbdf03727fb4c0c161286ad538fe6436b7e4b1c3059ef52930032475955f", 114_204_024),
        config = RemoteFile("$PIPER_VOICES/serena/high/it_IT-serena-high.onnx.json", "60597bac737a20766591fe24d1315df9a3811dc3509e69bc394cdee19eccbae7", 5_252),
        metadata = mapOf(
            "model_type" to "vits", "comment" to "piper", "language" to "Italian", "voice" to "it",
            "has_espeak" to "1", "n_speakers" to "1", "sample_rate" to "22050",
        ),
        dirName = "piper-serena-high",
        onnxName = "it_IT-serena-high.onnx",
        noiseScale = 0.6f,
        noiseScaleW = 0.4f,
    )

    val all: List<VoiceSpec> = listOf(SERENA)
    val default: VoiceSpec = SERENA

    fun byId(id: String): VoiceSpec? = all.firstOrNull { it.id == id }
}
