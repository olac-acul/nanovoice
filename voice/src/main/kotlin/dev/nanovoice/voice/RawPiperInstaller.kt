package dev.nanovoice.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException

/**
 * Installs a [VoiceSpec]: downloads and verifies both files, adds the metadata sherpa-onnx
 * needs to the model, builds tokens.txt, and makes sure the shared espeak data is there ([espeak]).
 */
class RawPiperInstaller(
    private val baseDir: File,
    private val voice: VoiceSpec,
    private val espeak: ModelManager,
    private val open: Opener = ::openHttpsFrom,
) : ModelInstaller {
    private val _state = MutableStateFlow<ModelState>(if (modelDir() != null) ModelState.Installed else ModelState.NotInstalled)
    override val state: StateFlow<ModelState> = _state

    /** One install at a time. */
    private val busy = Mutex()

    // A download cut short is kept in work.tmp (as *.download): the next install goes on from where it stopped.

    override fun modelDir(): File? = File(baseDir, voice.dirName).takeIf { File(it, READY_MARKER).isFile }

    override suspend fun install() = busy.withLock { installLocked() }

    private suspend fun installLocked() = withContext(Dispatchers.IO) {
        if (modelDir() != null) {
            _state.value = ModelState.Installed
            return@withContext
        }
        val work = File(baseDir, "work.tmp")
        var done = false
        try {
            _state.value = ModelState.Downloading(0f)
            if (espeak.modelDir() == null) {
                espeak.install()
                if (espeak.modelDir() == null) throw IOException("espeak data: ${(espeak.state.value as? ModelState.Failed)?.message ?: "not installed"}")
            }
            work.mkdirs()
            // From an earlier try only the downloads stay: what was made from them is made again.
            work.listFiles()?.filter { !it.name.endsWith(DOWNLOADING) }?.forEach { it.deleteRecursively() }
            val modelDownload = File(work, voice.onnxName + DOWNLOADING)
            val configDownload = File(work, "config.json$DOWNLOADING")
            val kept = listOf(modelDownload, configDownload).sumOf { if (it.isFile) it.length() else 0L }
            ensureSpace(baseDir, voice.onnx.sizeBytes + voice.config.sizeBytes - kept)
            downloadVerified(open, voice.onnx, modelDownload) { _state.value = ModelState.Downloading(it) }
            downloadVerified(open, voice.config, configDownload)
            val model = File(work, voice.onnxName)
            val config = File(work, "config.json")
            if (!modelDownload.renameTo(model) || !configDownload.renameTo(config)) throw IOException("cannot move the voice files")
            // Only now that the original file is verified: add the metadata.
            PiperFiles.appendMetadata(model, voice.metadata)
            File(work, "tokens.txt").writeText(PiperFiles.tokensFromConfig(config.readText()))
            config.delete()
            File(work, READY_MARKER).writeText(voice.onnx.sha256)
            val target = File(baseDir, voice.dirName)
            target.deleteRecursively()
            if (!work.renameTo(target)) throw IOException("cannot move the voice into place")
            done = true
            _state.value = ModelState.Installed
        } catch (e: CancellationException) {
            _state.value = ModelState.NotInstalled
            throw e
        } catch (e: Exception) {
            _state.value = ModelState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            if (done) work.deleteRecursively()
        }
    }

    override fun delete() {
        File(baseDir, voice.dirName).deleteRecursively()
        File(baseDir, "work.tmp").deleteRecursively()
        _state.value = ModelState.NotInstalled
    }

    private companion object {
        const val READY_MARKER = ".nanovoice-ready"
        const val DOWNLOADING = ".download"
    }
}
