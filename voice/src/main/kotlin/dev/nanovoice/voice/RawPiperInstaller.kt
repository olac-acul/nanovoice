package dev.nanovoice.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Installs a [VoiceSpec]: downloads and verifies both files, adds the metadata sherpa-onnx
 * needs to the model, builds tokens.txt, and makes sure the shared espeak data is there ([espeak]).
 */
class RawPiperInstaller(
    private val baseDir: File,
    private val voice: VoiceSpec,
    private val espeak: ModelManager,
    private val open: (String) -> InputStream = ::openHttps,
) : ModelInstaller {
    private val _state = MutableStateFlow<ModelState>(if (modelDir() != null) ModelState.Installed else ModelState.NotInstalled)
    override val state: StateFlow<ModelState> = _state

    /** Held by an install and by the start-up cleanup, so the cleanup never deletes a download in progress. */
    private val busy = Mutex()

    init {
        // A download cut short by the process dying leaves its partial files (hundreds of MB): gone at the next start.
        Thread {
            runBlocking { busy.withLock { for (name in listOf("work.tmp")) File(baseDir, name).deleteRecursively() } }
        }.apply { isDaemon = true }.start()
    }

    override fun modelDir(): File? = File(baseDir, voice.dirName).takeIf { File(it, READY_MARKER).isFile }

    override suspend fun install() = busy.withLock { installLocked() }

    private suspend fun installLocked() = withContext(Dispatchers.IO) {
        if (modelDir() != null) {
            _state.value = ModelState.Installed
            return@withContext
        }
        val work = File(baseDir, "work.tmp")
        try {
            _state.value = ModelState.Downloading(0f)
            if (espeak.modelDir() == null) {
                espeak.install()
                if (espeak.modelDir() == null) throw IOException("espeak data: ${(espeak.state.value as? ModelState.Failed)?.message ?: "not installed"}")
            }
            work.deleteRecursively()
            work.mkdirs()
            val model = File(work, voice.onnxName)
            val config = File(work, "config.json")
            downloadVerified(open, voice.onnx, model) { _state.value = ModelState.Downloading(it) }
            downloadVerified(open, voice.config, config)
            // Only now that the original file is verified: add the metadata.
            PiperFiles.appendMetadata(model, voice.metadata)
            File(work, "tokens.txt").writeText(PiperFiles.tokensFromConfig(config.readText()))
            config.delete()
            File(work, READY_MARKER).writeText(voice.onnx.sha256)
            val target = File(baseDir, voice.dirName)
            target.deleteRecursively()
            if (!work.renameTo(target)) throw IOException("cannot move the voice into place")
            _state.value = ModelState.Installed
        } catch (e: CancellationException) {
            _state.value = ModelState.NotInstalled
            throw e
        } catch (e: Exception) {
            _state.value = ModelState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            work.deleteRecursively()
        }
    }

    override fun delete() {
        File(baseDir, voice.dirName).deleteRecursively()
        _state.value = ModelState.NotInstalled
    }

    private companion object {
        const val READY_MARKER = ".nanovoice-ready"
    }
}
